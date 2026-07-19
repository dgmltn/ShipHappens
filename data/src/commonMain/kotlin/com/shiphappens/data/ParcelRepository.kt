package com.shiphappens.data

import com.shiphappens.data.db.*
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import com.shiphappens.source.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

sealed interface AddResult {
    data class Added(val parcel: Parcel) : AddResult
    data object Duplicate : AddResult
    data object NoCarrier : AddResult
}

data class RefreshSummary(val attempted: Int, val failed: Int, val firstFailureReason: FailureReason? = null)

class ParcelRepository(
    private val dao: ParcelDao,
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    private val _refreshingIds = MutableStateFlow<Set<String>>(emptySet())

    /** Ids of parcels with a refresh currently in flight — drives per-parcel loading indicators. */
    val refreshingIds: StateFlow<Set<String>> = _refreshingIds.asStateFlow()

    fun observeParcels(archived: Boolean): Flow<List<Parcel>> =
        dao.observe(archived).map { rows ->
            val sorted = if (archived) {
                rows.sortedByDescending { it.parcel.archivedAt ?: 0L }
            } else {
                rows.sortedWith(
                    compareBy<ParcelWithEvents> { it.parcel.status == TrackingStatus.DELIVERED.name }
                        .thenBy { it.parcel.etaDate ?: "9999-12-31" }
                        .thenBy { it.parcel.createdAt }
                )
            }
            sorted.map { it.toDomain() }
        }

    fun observeParcel(id: String): Flow<Parcel?> = dao.observeById(id).map { it?.toDomain() }

    suspend fun addParcel(name: String, trackingNumber: String, carrier: Carrier?): AddResult {
        val trimmed = trackingNumber.trim()
        val norm = normalizeTracking(trimmed)
        if (norm.isEmpty()) return AddResult.NoCarrier
        if (dao.normalizedNumbers().contains(norm)) return AddResult.Duplicate
        val resolved = carrier ?: registry.detectCarrier(trimmed) ?: return AddResult.NoCarrier
        val parcel = Parcel(
            id = Uuid.random().toString(),
            name = name.trim().ifEmpty { "New package" },
            trackingNumber = trimmed,
            carrier = resolved,
            createdAt = clock.now(),
        )
        dao.upsertParcel(parcel.toEntity())
        refresh(parcel.id)  // best effort; failure leaves status UNKNOWN
        return AddResult.Added(parcel)
    }

    suspend fun archive(id: String) = dao.archive(id, clock.now().toEpochMilliseconds())
    suspend fun restore(id: String) = dao.restore(id)
    suspend fun delete(id: String) = dao.deleteParcel(id)

    suspend fun refresh(id: String): Boolean = refreshRow(id) is RefreshOutcome.Success

    /**
     * Persist a snapshot produced OUTSIDE the normal track() path (e.g. the visible web view's
     * scrape-on-view). Identical merge semantics to a successful refresh: UNKNOWN status and null
     * fields never clobber existing values, events replace wholesale when non-empty, and the
     * parcel is pinned to [sourceId]. Returns false when the parcel no longer exists.
     */
    suspend fun applySnapshot(id: String, snapshot: TrackingSnapshot, sourceId: String): Boolean {
        val row = dao.getById(id) ?: return false
        // etaWindowStart/etaWindowEnd are two halves of one value, so they're merged as a unit:
        // if the snapshot carries either bound, take both from the snapshot (a start-less
        // snapshot clears a stored start rather than leaving it paired with a new end); only
        // when the snapshot carries neither bound do we preserve the existing row's window.
        val newWindow = snapshot.etaWindowStart != null || snapshot.etaWindowEnd != null
        val updated = row.parcel.copy(
            status = if (snapshot.status == TrackingStatus.UNKNOWN) row.parcel.status else snapshot.status.name,
            etaDate = snapshot.etaDate?.toString() ?: row.parcel.etaDate,
            etaWindowStart = if (newWindow) snapshot.etaWindowStart?.toString() else row.parcel.etaWindowStart,
            etaWindowEnd = if (newWindow) snapshot.etaWindowEnd?.toString() else row.parcel.etaWindowEnd,
            latestLocation = snapshot.latestLocation ?: row.parcel.latestLocation,
            sourceId = sourceId,
            lastRefreshedAt = clock.now().toEpochMilliseconds(),
        )
        dao.upsertParcel(updated)
        if (snapshot.events.isNotEmpty()) dao.replaceEvents(id, snapshot.events.map { it.toEntity(id) })
        return true
    }

    /**
     * Outcome of a single-row refresh attempt. [NoSource] (no enabled source resolves for this
     * parcel) is NOT a failure — per spec §9 it must not count toward [RefreshSummary.failed] or
     * surface a "couldn't refresh" toast; the parcel simply stays at whatever status it has.
     */
    private sealed interface RefreshOutcome {
        data object Success : RefreshOutcome
        data object NoSource : RefreshOutcome
        data class Failed(val reason: FailureReason) : RefreshOutcome
    }

    private suspend fun refreshRow(id: String): RefreshOutcome {
        val row = dao.getById(id) ?: return RefreshOutcome.Failed(FailureReason.UNKNOWN)
        val parcel = row.toDomain()
        val source = registry.sourceFor(parcel) ?: return RefreshOutcome.NoSource
        _refreshingIds.update { it + id }
        try {
            // Guard against a misbehaving source implementation throwing instead of returning
            // SourceResult.Failure — see TrackingSource.track KDoc.
            val result = runCatching { source.track(parcel.trackingNumber, parcel.carrier) }
                .getOrElse { SourceResult.Failure(FailureReason.UNKNOWN, it.message) }
            return when (result) {
                is SourceResult.Failure -> RefreshOutcome.Failed(result.reason)
                is SourceResult.Success -> {
                    applySnapshot(id, result.value, source.descriptor.id)
                    RefreshOutcome.Success
                }
            }
        } finally {
            _refreshingIds.update { it - id }
        }
    }

    suspend fun refreshAll(force: Boolean): RefreshSummary {
        val staleAfter = settings.settings.first().refreshFrequency.staleAfterMinutes
        if (!force && staleAfter == null) return RefreshSummary(0, 0)  // MANUAL
        val cutoff = staleAfter?.let { clock.now() - it.minutes }
        val candidates = dao.allActive().filter { e ->
            e.status != TrackingStatus.DELIVERED.name &&
                (force || e.lastRefreshedAt == null ||
                    (cutoff != null && e.lastRefreshedAt < cutoff.toEpochMilliseconds()))
        }
        var failed = 0
        var firstReason: FailureReason? = null
        for (e in candidates) {
            val outcome = refreshRow(e.id)
            if (outcome is RefreshOutcome.Failed) {
                failed++
                if (firstReason == null) firstReason = outcome.reason
            }
        }
        return RefreshSummary(candidates.size, failed, firstReason)
    }
}

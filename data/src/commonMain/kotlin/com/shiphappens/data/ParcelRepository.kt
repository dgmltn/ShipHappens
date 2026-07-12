package com.shiphappens.data

import com.shiphappens.data.db.*
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import com.shiphappens.source.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
        // Guard against a misbehaving source implementation throwing instead of returning
        // SourceResult.Failure — see TrackingSource.track KDoc.
        val result = runCatching { source.track(parcel.trackingNumber, parcel.carrier) }
            .getOrElse { SourceResult.Failure(FailureReason.UNKNOWN, it.message) }
        return when (result) {
            is SourceResult.Failure -> RefreshOutcome.Failed(result.reason)
            is SourceResult.Success -> {
                val snap = result.value
                val updated = row.parcel.copy(
                    status = if (snap.status == TrackingStatus.UNKNOWN) row.parcel.status else snap.status.name,
                    etaDate = snap.etaDate?.toString() ?: row.parcel.etaDate,
                    etaTime = snap.etaTime?.toString() ?: row.parcel.etaTime,
                    latestLocation = snap.latestLocation ?: row.parcel.latestLocation,
                    sourceId = source.descriptor.id,
                    lastRefreshedAt = clock.now().toEpochMilliseconds(),
                )
                dao.upsertParcel(updated)
                if (snap.events.isNotEmpty()) dao.replaceEvents(id, snap.events.map { it.toEntity(id) })
                RefreshOutcome.Success
            }
        }
    }

    suspend fun refreshAll(force: Boolean): RefreshSummary {
        seedEnabledSources()
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

    private suspend fun seedEnabledSources() {
        registry.enabled().filterIsInstance<SeedingSource>().forEach { seeder ->
            seeder.seeds().forEach { addParcel(it.name, it.trackingNumber, it.carrier) }
        }
    }
}

package com.shiphappens.core.data

import com.shiphappens.core.data.db.*
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.core.model.*
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

    suspend fun refresh(id: String): Boolean = refreshRow(id) == null

    /** @return null on success, failure reason otherwise (NO source resolves to UNKNOWN). */
    private suspend fun refreshRow(id: String): FailureReason? {
        val row = dao.getById(id) ?: return FailureReason.UNKNOWN
        val parcel = row.toDomain()
        val source = registry.sourceFor(parcel) ?: return FailureReason.UNKNOWN
        return when (val result = source.track(parcel.trackingNumber, parcel.carrier)) {
            is SourceResult.Failure -> result.reason
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
                null
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
            val reason = refreshRow(e.id)
            if (reason != null) { failed++; if (firstReason == null) firstReason = reason }
        }
        return RefreshSummary(candidates.size, failed, firstReason)
    }

    private suspend fun seedEnabledSources() {
        registry.enabled().filterIsInstance<SeedingSource>().forEach { seeder ->
            seeder.seeds().forEach { addParcel(it.name, it.trackingNumber, it.carrier) }
        }
    }
}

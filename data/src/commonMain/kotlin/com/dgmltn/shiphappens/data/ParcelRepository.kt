package com.dgmltn.shiphappens.data

import com.dgmltn.shiphappens.data.db.*
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

sealed interface AddResult {
    data class Added(val parcel: Parcel) : AddResult
    data object Duplicate : AddResult
    data object NoCarrier : AddResult
}

data class RefreshSummary(
    val attempted: Int,
    val failed: Int,
    val firstFailureReason: FailureReason? = null,
    /** Every successful refresh's diff, notable or not. */
    val changes: List<ParcelChange> = emptyList(),
    /** Sources that failed with AUTH — their session needs re-establishing. */
    val authFailedSourceIds: Set<String> = emptySet(),
    /** Sources that refreshed at least one parcel successfully — clears a stale sign-in nag. */
    val succeededSourceIds: Set<String> = emptySet(),
)

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

    /**
     * Insert only — deliberately does NOT fetch tracking data, so it returns as soon as the row
     * exists. Callers that want the parcel populated kick off [refresh] themselves, after any
     * UI response to [AddResult.Added] (clearing the add card must not wait on the network).
     */
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
        return AddResult.Added(parcel)
    }

    /**
     * Blank is a deliberate no-op rather than a fallback to "New package": a rename always has a
     * prior name to keep, so clearing the field reverts instead of retitling the parcel.
     */
    suspend fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) dao.rename(id, trimmed)
    }

    suspend fun archive(id: String) = dao.archive(id, clock.now().toEpochMilliseconds())
    suspend fun restore(id: String) = dao.restore(id)
    suspend fun delete(id: String) = dao.deleteParcel(id)

    /**
     * Deletes the row NOW and returns the inverse action: a faithful reinsert of the row and its
     * events (archived state included). Undo-as-inverse means there is never a window where the
     * UI says "deleted" while the row still blocks re-adding its number, and no pending job whose
     * cancellation quietly resurrects the parcel (both halves seen in Pixel QA 2026-09-03 under
     * the old grace-period delete). NonCancellable: once the user has been told "deleted", a
     * dying caller scope must not abort the write. The undo no-ops if the number was re-added in
     * the meantime — reinserting alongside the replacement would duplicate it. Null only when the
     * row doesn't exist.
     */
    suspend fun deleteReturningUndo(id: String): (suspend () -> Unit)? = withContext(NonCancellable) {
        val row = dao.getById(id) ?: return@withContext null
        dao.deleteParcel(id)
        suspend {
            if (!dao.normalizedNumbers().contains(row.parcel.normalizedTracking)) {
                dao.upsertParcel(row.parcel)
                dao.replaceEvents(row.parcel.id, row.events)
            }
        }
    }

    suspend fun refresh(id: String): Boolean = refreshRow(id) is RefreshOutcome.Success

    /**
     * Persist a snapshot produced OUTSIDE the normal track() path (e.g. the visible web view's
     * scrape-on-view). Identical merge semantics to a successful refresh: UNKNOWN status and null
     * fields never clobber existing values, events replace wholesale when non-empty, and the
     * parcel is pinned to [sourceId]. Returns false when the parcel no longer exists.
     */
    suspend fun applySnapshot(id: String, snapshot: TrackingSnapshot, sourceId: String): Boolean =
        applyAndDiff(id, snapshot, sourceId) != null

    /** [applySnapshot] plus the before/after diff. Null ONLY when the row doesn't exist. */
    private suspend fun applyAndDiff(id: String, snapshot: TrackingSnapshot, sourceId: String): ParcelChange? {
        val row = dao.getById(id) ?: return null
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
            // Three-way, and none of the simpler rules work. A snapshot that carries a note
            // always wins — delay wording asserts no stage, so a newly-delayed parcel typically
            // arrives as UNKNOWN + note, and deferring to the old value there would drop every
            // new delay on the floor. Absent a note, a snapshot that DID classify a stage clears
            // the stored one, because a delay ends and a stale "Delayed" chip on a back-on-
            // schedule package is worse than none. Only a scrape that learned nothing at all
            // (UNKNOWN, no note) leaves the stored note alone.
            delayNote = snapshot.delayNote
                ?: row.parcel.delayNote.takeIf { snapshot.status == TrackingStatus.UNKNOWN },
            sourceId = sourceId,
            lastRefreshedAt = clock.now().toEpochMilliseconds(),
        )
        dao.upsertParcel(updated)
        if (snapshot.events.isNotEmpty()) dao.replaceEvents(id, snapshot.events.map { it.toEntity(id) })
        return ParcelChange(
            parcelId = id,
            parcelName = updated.name,
            statusBefore = row.parcel.status.toTrackingStatus(),
            statusAfter = updated.status.toTrackingStatus(),
            etaBefore = row.parcel.etaDate?.let(LocalDate::parse),
            etaAfter = updated.etaDate?.let(LocalDate::parse),
            // The two vantage points an unmoved ETA is judged from — an ETA grows imminent
            // between checks without the snapshot changing at all. The epoch column is a DB
            // boundary, so it converts here and travels no further.
            checkedOn = clock.today(),
            previouslyCheckedOn = row.parcel.lastRefreshedAt
                ?.let { clock.dateOf(Instant.fromEpochMilliseconds(it)) },
            delayNoteBefore = row.parcel.delayNote,
            delayNoteAfter = updated.delayNote,
        )
    }

    private fun String.toTrackingStatus(): TrackingStatus =
        runCatching { TrackingStatus.valueOf(this) }.getOrDefault(TrackingStatus.UNKNOWN)

    /**
     * Outcome of a single-row refresh attempt. [NoSource] (no enabled source resolves for this
     * parcel) is NOT a failure — per spec §9 it must not count toward [RefreshSummary.failed] or
     * surface a "couldn't refresh" toast; the parcel simply stays at whatever status it has.
     */
    private sealed interface RefreshOutcome {
        data class Success(val change: ParcelChange?, val sourceId: String) : RefreshOutcome
        data object NoSource : RefreshOutcome
        data class Failed(val reason: FailureReason, val sourceId: String?) : RefreshOutcome
    }

    private suspend fun refreshRow(id: String): RefreshOutcome {
        val row = dao.getById(id) ?: return RefreshOutcome.Failed(FailureReason.UNKNOWN, null)
        val parcel = row.toDomain()
        val source = registry.sourceFor(parcel) ?: return RefreshOutcome.NoSource
        _refreshingIds.update { it + id }
        try {
            // Guard against a misbehaving source implementation throwing instead of returning
            // SourceResult.Failure — see TrackingSource.track KDoc.
            val result = runCatching { source.track(parcel.trackingNumber, parcel.carrier) }
                .getOrElse { SourceResult.Failure(FailureReason.UNKNOWN, it.message) }
            return when (result) {
                is SourceResult.Failure -> RefreshOutcome.Failed(result.reason, source.descriptor.id)
                is SourceResult.Success ->
                    RefreshOutcome.Success(applyAndDiff(id, result.value, source.descriptor.id), source.descriptor.id)
            }
        } finally {
            _refreshingIds.update { it - id }
        }
    }

    suspend fun refreshAll(
        force: Boolean,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RefreshSummary {
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
        val changes = mutableListOf<ParcelChange>()
        val authFailed = mutableSetOf<String>()
        val succeeded = mutableSetOf<String>()
        onProgress(0, candidates.size)
        for ((index, e) in candidates.withIndex()) {
            when (val outcome = refreshRow(e.id)) {
                is RefreshOutcome.Success -> {
                    outcome.change?.let(changes::add)
                    succeeded += outcome.sourceId
                }
                is RefreshOutcome.Failed -> {
                    failed++
                    if (firstReason == null) firstReason = outcome.reason
                    if (outcome.reason == FailureReason.AUTH && outcome.sourceId != null) {
                        authFailed += outcome.sourceId
                    }
                }
                RefreshOutcome.NoSource -> Unit
            }
            onProgress(index + 1, candidates.size)
        }
        return RefreshSummary(candidates.size, failed, firstReason, changes, authFailed, succeeded)
    }
}

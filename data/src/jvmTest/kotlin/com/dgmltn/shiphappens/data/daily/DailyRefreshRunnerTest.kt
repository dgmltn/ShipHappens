package com.dgmltn.shiphappens.data.daily

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.AddResult
import com.dgmltn.shiphappens.data.FixedClock
import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.ParcelRepository
import com.dgmltn.shiphappens.data.RefreshSummary
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.FakeSource
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.*

private class RecordingNotifier : StatusNotifier {
    val changes = mutableListOf<ParcelChange>()
    val signIns = mutableListOf<String>()
    val progress = mutableListOf<Pair<Int, Int>>()
    val finished = mutableListOf<RefreshSummary>()
    val failures = mutableListOf<String>()
    override suspend fun notifyStatusChange(change: ParcelChange) { changes += change }
    override suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) { signIns += sourceId }
    override suspend fun notifyRunProgress(done: Int, total: Int) { progress += done to total }
    override suspend fun notifyRunFinished(summary: RefreshSummary) { finished += summary }
    override suspend fun notifyRunFailed(message: String) { failures += message }
}

class DailyRefreshRunnerTest {
    private lateinit var settings: SettingsRepository
    private lateinit var repository: ParcelRepository
    private lateinit var registry: SourceRegistry
    private val notifier = RecordingNotifier()

    private fun runner(scope: CoroutineScope, vararg sources: TrackingSource): DailyRefreshRunner {
        val dir = kotlin.io.path.createTempDirectory("daily").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        registry = SourceRegistry(sources.toList(), settings)
        repository = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        return DailyRefreshRunner(repository, settings, registry, notifier)
    }

    private suspend fun enableAll(vararg ids: String) {
        settings.setDailyUpdateEnabled(true)
        ids.forEach { settings.setSourceConfig(it, SourceConfig(enabled = true)) }
    }

    @Test fun disabled_setting_skips_the_run_entirely() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS)
        val r = runner(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)

        val summary = r.runOnce()

        assertEquals(0, summary.attempted)
        assertTrue(src.trackedNumbers.isEmpty())
        assertTrue(notifier.changes.isEmpty())
    }

    @Test fun notable_change_notifies_once_per_parcel() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = runner(scope, src)
        enableAll("u")
        val added = repository.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added

        r.runOnce()  // UNKNOWN -> IN_TRANSIT is notable

        assertEquals(listOf(added.parcel.id), notifier.changes.map { it.parcelId })
    }

    @Test fun unchanged_parcel_does_not_notify() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = runner(scope, src)
        enableAll("u")
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.runOnce()
        notifier.changes.clear()

        r.runOnce()

        assertTrue(notifier.changes.isEmpty())
    }

    @Test fun auth_failure_nags_once_then_stays_quiet() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Failure(FailureReason.AUTH, "expired"))
        val r = runner(scope, src)
        enableAll("u")
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)

        r.runOnce()
        r.runOnce()

        assertEquals(listOf("u"), notifier.signIns)
        assertEquals(setOf("u"), settings.settings.first().signInNaggedSourceIds)
    }

    @Test fun a_later_success_rearms_the_nag() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Failure(FailureReason.AUTH, "expired"))
        val r = runner(scope, src)
        enableAll("u")
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.runOnce()

        src.trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT))
        r.runOnce()
        assertEquals(emptySet(), settings.settings.first().signInNaggedSourceIds)

        src.trackResult = SourceResult.Failure(FailureReason.AUTH, "expired again")
        r.runOnce()

        assertEquals(listOf("u", "u"), notifier.signIns)
    }

    @Test fun every_run_reports_finished_even_when_nothing_changed() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = runner(scope, src)
        enableAll("u")
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.runOnce()  // first run: UNKNOWN -> IN_TRANSIT

        r.runOnce()  // second run: no change at all

        assertEquals(2, notifier.finished.size)
        assertEquals(1, notifier.finished.last().attempted)
        assertTrue(notifier.finished.last().changes.none { it.isNotable })
    }

    @Test fun run_reports_per_parcel_progress() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = runner(scope, src)
        enableAll("u")
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)
        repository.addParcel("Mouse", "1Z999AA10123456785", null)
        notifier.progress.clear()

        r.runOnce()

        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), notifier.progress)
    }

    @Test fun disabled_setting_reports_neither_progress_nor_finished() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS)
        val r = runner(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)

        r.runOnce()

        assertTrue(notifier.progress.isEmpty())
        assertTrue(notifier.finished.isEmpty())
    }

    @Test fun archived_and_delivered_parcels_are_never_candidates() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.DELIVERED)))
        val r = runner(scope, src)
        enableAll("u")
        val added = repository.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        r.runOnce()          // becomes DELIVERED, notifies
        notifier.changes.clear()
        repository.archive(added.parcel.id)

        val summary = r.runOnce()

        assertEquals(0, summary.attempted)
        assertTrue(notifier.changes.isEmpty())
    }
}

package com.dgmltn.shiphappens.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.FakeSource
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import kotlin.test.*

class ParcelChangeTest {
    private lateinit var settings: SettingsRepository

    private fun repo(scope: CoroutineScope, vararg sources: TrackingSource): ParcelRepository {
        val dir = kotlin.io.path.createTempDirectory("change").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        return ParcelRepository(db.parcelDao(), SourceRegistry(sources.toList(), settings), settings, FixedClock())
    }

    private suspend fun enable(id: String) = settings.setSourceConfig(id, SourceConfig(enabled = true))

    @Test fun status_transition_is_notable() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = repo(scope, src)
        enable("u")
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        r.refreshAll(force = true)  // UNKNOWN -> IN_TRANSIT

        src.trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY))
        val summary = r.refreshAll(force = true)

        val change = summary.changes.single { it.parcelId == added.parcel.id }
        assertEquals(TrackingStatus.IN_TRANSIT, change.statusBefore)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, change.statusAfter)
        assertTrue(change.statusChanged)
        assertTrue(change.isNotable)
        assertEquals("Keyboard", change.parcelName)
    }

    @Test fun identical_snapshot_is_not_notable() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.refreshAll(force = true)

        val summary = r.refreshAll(force = true)

        assertEquals(1, summary.changes.size)
        assertFalse(summary.changes.single().isNotable)
    }

    @Test fun eta_date_shift_is_notable_without_status_change() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(
                TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 8, 19))))
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.refreshAll(force = true)

        src.trackResult = SourceResult.Success(
            TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 8, 21)))
        val change = r.refreshAll(force = true).changes.single()

        assertFalse(change.statusChanged)
        assertTrue(change.etaChanged)
        assertTrue(change.isNotable)
        assertEquals(LocalDate(2026, 8, 19), change.etaBefore)
        assertEquals(LocalDate(2026, 8, 21), change.etaAfter)
    }

    @Test fun unknown_status_after_is_never_notable() {
        val change = ParcelChange("p1", "Keyboard", TrackingStatus.UNKNOWN, TrackingStatus.UNKNOWN, null, null)
        assertFalse(change.isNotable)
    }

    @Test fun auth_failure_is_reported_with_its_source_id() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Failure(FailureReason.AUTH, "session expired"))
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)

        val summary = r.refreshAll(force = true)

        assertEquals(setOf("u"), summary.authFailedSourceIds)
        assertTrue(summary.changes.isEmpty())
        assertEquals(1, summary.failed)
    }

    @Test fun success_reports_its_source_id_for_nag_clearing() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS)
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)

        assertEquals(setOf("u"), r.refreshAll(force = true).succeededSourceIds)
    }
}

package com.dgmltn.shiphappens.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.data.settings.RefreshFrequency
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.FakeSource
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.test.*

class FixedClock(var instant: Instant = Instant.fromEpochMilliseconds(1_752_148_800_000), // 2026-07-10T12:00Z
                 var date: LocalDate = LocalDate(2026, 7, 10)) : AppClock {
    override fun now() = instant
    override fun today() = date
}

/** Always throws instead of returning a Failure — exercises the runCatching guard in refreshRow. */
class ThrowingSource : TrackingSource {
    override val descriptor = SourceDescriptor("boom", "Boom")
    override fun detectCarrier(trackingNumber: String): Carrier? = WellKnownCarriers.UPS
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> =
        throw IllegalStateException("source exploded")
}

class ParcelRepositoryTest {
    private lateinit var settings: SettingsRepository
    private lateinit var clock: FixedClock

    private fun repo(scope: CoroutineScope, vararg sources: TrackingSource): ParcelRepository {
        val dir = kotlin.io.path.createTempDirectory("repo").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        clock = FixedClock()
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        return ParcelRepository(db.parcelDao(), SourceRegistry(sources.toList(), settings), settings, clock)
    }

    @Test fun add_detects_carrier_and_dedupes() = runTest {
        val r = repo(CoroutineScope(coroutineContext + SupervisorJob()))
        val added = r.addParcel("Keyboard", "1Z 999 AA1 01 2345 6784", carrier = null)
        assertIs<AddResult.Added>(added)
        assertEquals(WellKnownCarriers.UPS, added.parcel.carrier)
        assertIs<AddResult.Duplicate>(r.addParcel("Again", "1z999aa101 2345-6784", null))
        assertIs<AddResult.NoCarrier>(r.addParcel("Mystery", "ZZZZZZZZZZZZ!!", null))
    }

    @Test fun add_inserts_without_refreshing_then_refresh_populates() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 15))))
        val r = repo(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        // Insert-only contract: the row exists immediately, untracked, so callers can respond
        // to Added without waiting on the network.
        assertEquals(TrackingStatus.UNKNOWN, r.observeParcel(added.parcel.id).first()!!.status)
        r.refresh(added.parcel.id)
        val p = r.observeParcel(added.parcel.id).first()!!
        assertEquals(TrackingStatus.IN_TRANSIT, p.status)
        assertEquals(LocalDate(2026, 7, 15), p.etaDate)
        assertEquals("u", p.sourceId)
    }

    @Test fun refresh_failure_keeps_existing_data() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS)
        val r = repo(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        r.refresh(added.parcel.id)
        src.trackResult = SourceResult.Failure(FailureReason.AUTH, "bad key")
        val summary = r.refreshAll(force = true)
        assertEquals(1, summary.failed)
        assertEquals(FailureReason.AUTH, summary.firstFailureReason)
        val p = r.observeParcel(added.parcel.id).first()!!
        assertEquals(TrackingStatus.IN_TRANSIT, p.status)  // from the first, successful refresh
    }

    @Test fun refreshAll_honors_staleness_and_force() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS)
        val r = repo(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        r.refresh(added.parcel.id)
        src.trackedNumbers.clear()
        r.refreshAll(force = false)                          // just refreshed -> not stale
        assertTrue(src.trackedNumbers.isEmpty())
        clock.instant += 16.minutes                          // past FIFTEEN_MIN threshold
        r.refreshAll(force = false)
        assertEquals(1, src.trackedNumbers.size)
        settings.setRefreshFrequency(RefreshFrequency.MANUAL)
        clock.instant += 16.minutes
        r.refreshAll(force = false)                          // MANUAL: never automatic
        assertEquals(1, src.trackedNumbers.size)
        r.refreshAll(force = true)                           // force works even on MANUAL
        assertEquals(2, src.trackedNumbers.size)
    }

    @Test fun refreshAll_does_not_count_no_source_parcels_as_failures() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u")
        val r = repo(scope, src)
        // "u" is never enabled -> the parcel has no resolvable source.
        val added = r.addParcel("Mystery box", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        val summary = r.refreshAll(force = true)
        assertEquals(0, summary.failed)
        assertNull(summary.firstFailureReason)
        val p = r.observeParcel(added.parcel.id).first()!!
        assertEquals(TrackingStatus.UNKNOWN, p.status)
    }

    @Test fun refreshAll_survives_a_throwing_source_and_counts_one_failure() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = ThrowingSource()
        val r = repo(scope, src)
        settings.setSourceConfig("boom", SourceConfig(enabled = true))
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
        val summary = r.refreshAll(force = true)
        assertEquals(1, summary.failed)
        assertEquals(FailureReason.UNKNOWN, summary.firstFailureReason)
    }

    @Test fun archive_restore_and_sorting() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val a = r.addParcel("A", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        r.addParcel("B", "9400111899223300112", WellKnownCarriers.USPS)
        r.archive(a.parcel.id)
        assertEquals(listOf("B"), r.observeParcels(false).first().map { it.name })
        assertEquals(listOf("A"), r.observeParcels(true).first().map { it.name })
        r.restore(a.parcel.id)
        assertEquals(2, r.observeParcels(false).first().size)
    }

    @Test fun rename_updates_the_name_and_trims() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val a = r.addParcel("New package", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        r.rename(a.parcel.id, "  Baseball cap  ")
        assertEquals("Baseball cap", r.observeParcel(a.parcel.id).first()!!.name)
    }

    /**
     * A rename always has a prior name to fall back to, unlike addParcel (which has to invent
     * "New package"), so clearing the field reverts rather than writing a blank title.
     */
    @Test fun rename_to_blank_keeps_the_existing_name() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val a = r.addParcel("Baseball cap", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        r.rename(a.parcel.id, "   ")
        assertEquals("Baseball cap", r.observeParcel(a.parcel.id).first()!!.name)
    }

    @Test fun delete_removes_parcel() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val a = r.addParcel("A", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        r.delete(a.parcel.id)
        assertEquals(0, r.observeParcels(false).first().size)
        assertNull(r.observeParcel(a.parcel.id).first())
    }
}

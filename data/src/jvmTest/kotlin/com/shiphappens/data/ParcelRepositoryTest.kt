package com.shiphappens.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.db.ShipHappensDb
import com.shiphappens.data.settings.RefreshFrequency
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.FakeSource
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import com.shiphappens.source.api.*
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

class SeedingFake : TrackingSource, SeedingSource {
    override val descriptor = SourceDescriptor("seeder", "Seeder", SourceKind.UNIVERSAL)
    override fun detectCarrier(trackingNumber: String): Carrier? = null
    override suspend fun track(trackingNumber: String, carrier: Carrier?) =
        SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT))
    override suspend fun testConnection(config: SourceConfig) = SourceResult.Success(Unit)
    override fun seeds() = listOf(SeedParcel("Baseball cap", "1ZW463200377332024", WellKnownCarriers.USPS))
}

/** Always throws instead of returning a Failure — exercises the runCatching guard in refreshRow. */
class ThrowingSource : TrackingSource {
    override val descriptor = SourceDescriptor("boom", "Boom", SourceKind.UNIVERSAL)
    override fun detectCarrier(trackingNumber: String): Carrier? = null
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> =
        throw IllegalStateException("source exploded")
    override suspend fun testConnection(config: SourceConfig) = SourceResult.Success(Unit)
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

    @Test fun add_refreshes_immediately_when_source_available() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", kind = SourceKind.UNIVERSAL,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 15))))
        val r = repo(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        val p = r.observeParcel(added.parcel.id).first()!!
        assertEquals(TrackingStatus.IN_TRANSIT, p.status)
        assertEquals(LocalDate(2026, 7, 15), p.etaDate)
        assertEquals("u", p.sourceId)
    }

    @Test fun refresh_failure_keeps_existing_data() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", kind = SourceKind.UNIVERSAL)
        val r = repo(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        src.trackResult = SourceResult.Failure(FailureReason.AUTH, "bad key")
        val summary = r.refreshAll(force = true)
        assertEquals(1, summary.failed)
        assertEquals(FailureReason.AUTH, summary.firstFailureReason)
        val p = r.observeParcel(added.parcel.id).first()!!
        assertEquals(TrackingStatus.IN_TRANSIT, p.status)  // from the add-time refresh
    }

    @Test fun refreshAll_honors_staleness_and_force() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", kind = SourceKind.UNIVERSAL)
        val r = repo(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
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

    @Test fun refreshAll_skips_delivered_and_seeds_seeding_sources() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val seeder = SeedingFake()
        val r = repo(scope, seeder)
        settings.setSourceConfig("seeder", SourceConfig(enabled = true))
        r.refreshAll(force = true)
        val parcels = r.observeParcels(archived = false).first()
        assertEquals(listOf("Baseball cap"), parcels.map { it.name })
        r.refreshAll(force = true)  // seeding is idempotent (dedupe)
        assertEquals(1, r.observeParcels(archived = false).first().size)
    }

    @Test fun refreshAll_does_not_count_no_source_parcels_as_failures() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", kind = SourceKind.UNIVERSAL)
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

    @Test fun delete_removes_parcel() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val a = r.addParcel("A", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        r.delete(a.parcel.id)
        assertEquals(0, r.observeParcels(false).first().size)
        assertNull(r.observeParcel(a.parcel.id).first())
    }
}

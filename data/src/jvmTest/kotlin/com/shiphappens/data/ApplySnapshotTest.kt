package com.shiphappens.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.db.ShipHappensDb
import com.shiphappens.data.db.toEntity
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import okio.Path.Companion.toPath

class ApplySnapshotTest {
    private class FixedClock : AppClock {
        override fun now() = Instant.fromEpochMilliseconds(1_752_300_000_000)
        override fun today() = LocalDate(2026, 7, 12)
    }

    private lateinit var db: ShipHappensDb

    private fun repo(scope: kotlinx.coroutines.CoroutineScope): ParcelRepository {
        val dir = createTempDirectory("applysnap").toString()
        val settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        return ParcelRepository(db.parcelDao(), SourceRegistry(emptyList(), settings), settings, FixedClock())
    }

    private val parcel = Parcel(
        id = "p1", name = "Web-scraped parcel", trackingNumber = "1Z999AA10123456784",
        carrier = WellKnownCarriers.UPS, status = TrackingStatus.UNKNOWN,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    @Test fun applies_status_eta_events_and_source_pin() = runTest {
        val r = repo(backgroundScope)
        db.parcelDao().upsertParcel(parcel.toEntity())
        val ok = r.applySnapshot(
            "p1",
            TrackingSnapshot(
                status = TrackingStatus.IN_TRANSIT,
                etaDate = LocalDate(2026, 7, 15), etaTime = LocalTime(21, 0),
                latestLocation = "Louisville, KY",
                events = listOf(TrackingEvent(Instant.parse("2026-07-11T12:15:00Z"), "Departed from Facility", "Louisville, KY", TrackingStatus.IN_TRANSIT)),
            ),
            sourceId = "ups",
        )
        assertTrue(ok)
        val row = assertNotNull(db.parcelDao().getById("p1"))
        assertEquals(TrackingStatus.IN_TRANSIT.name, row.parcel.status)
        assertEquals("2026-07-15", row.parcel.etaDate)
        assertEquals("ups", row.parcel.sourceId)
        assertEquals(1_752_300_000_000, row.parcel.lastRefreshedAt)
        assertEquals(1, row.events.size)
    }

    @Test fun unknown_status_and_null_fields_preserve_existing() = runTest {
        val r = repo(backgroundScope)
        db.parcelDao().upsertParcel(
            parcel.copy(status = TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 14), latestLocation = "Memphis, TN").toEntity(),
        )
        assertTrue(r.applySnapshot("p1", TrackingSnapshot(status = TrackingStatus.UNKNOWN), sourceId = "ups"))
        val row = assertNotNull(db.parcelDao().getById("p1"))
        assertEquals(TrackingStatus.IN_TRANSIT.name, row.parcel.status)
        assertEquals("2026-07-14", row.parcel.etaDate)
        assertEquals("Memphis, TN", row.parcel.latestLocation)
    }

    @Test fun missing_parcel_returns_false() = runTest {
        val r = repo(backgroundScope)
        assertFalse(r.applySnapshot("nope", TrackingSnapshot(status = TrackingStatus.DELIVERED), sourceId = "ups"))
    }

    @Test fun empty_events_snapshot_preserves_existing_events() = runTest {
        val r = repo(backgroundScope)
        db.parcelDao().upsertParcel(parcel.toEntity())
        db.parcelDao().replaceEvents("p1", listOf(TrackingEvent(Instant.parse("2026-07-10T08:00:00Z"), "Picked up", "Origin, IL", TrackingStatus.IN_TRANSIT).toEntity("p1")))
        val ok = r.applySnapshot("p1", TrackingSnapshot(status = TrackingStatus.DELIVERED), sourceId = "ups")
        assertTrue(ok)
        val row = assertNotNull(db.parcelDao().getById("p1"))
        assertEquals(TrackingStatus.DELIVERED.name, row.parcel.status)
        assertEquals(1, row.events.size)
        assertEquals("Picked up", row.events[0].description)
    }
}

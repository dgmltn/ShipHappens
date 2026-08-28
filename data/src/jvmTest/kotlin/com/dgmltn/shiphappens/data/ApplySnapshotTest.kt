package com.dgmltn.shiphappens.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.data.db.toEntity
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
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
                etaDate = LocalDate(2026, 7, 15), etaWindowEnd = LocalTime(21, 0),
                latestLocation = "Louisville, KY",
                events = listOf(TrackingEvent(Instant.parse("2026-07-11T12:15:00Z"), "Departed from Facility", "Louisville, KY", TrackingStatus.IN_TRANSIT)),
            ),
            sourceId = "ups",
        )
        assertTrue(ok)
        val row = assertNotNull(db.parcelDao().getById("p1"))
        assertEquals(TrackingStatus.IN_TRANSIT.name, row.parcel.status)
        assertEquals("2026-07-15", row.parcel.etaDate)
        assertEquals("21:00", row.parcel.etaWindowEnd)
        assertNull(row.parcel.etaWindowStart)
        assertEquals("ups", row.parcel.sourceId)
        assertEquals(1_752_300_000_000, row.parcel.lastRefreshedAt)
        assertEquals(1, row.events.size)
    }

    @Test fun a_delay_note_is_stored_alongside_the_stage() = runTest {
        val r = repo(backgroundScope)
        db.parcelDao().upsertParcel(parcel.toEntity())
        assertTrue(r.applySnapshot(
            "p1",
            TrackingSnapshot(status = TrackingStatus.IN_TRANSIT, delayNote = "Due to weather, delayed by one business day."),
            sourceId = "ups",
        ))
        val row = assertNotNull(db.parcelDao().getById("p1"))
        // The stage is untouched by the delay — that is the whole point of the modifier.
        assertEquals(TrackingStatus.IN_TRANSIT.name, row.parcel.status)
        assertEquals("Due to weather, delayed by one business day.", row.parcel.delayNote)
    }

    @Test fun a_resolved_delay_clears_the_stored_note() = runTest {
        val r = repo(backgroundScope)
        db.parcelDao().upsertParcel(parcel.copy(delayNote = "Delayed by weather").toEntity())
        // A classified snapshot that reports no delay means the delay is over. Unlike
        // latestLocation, the note must NOT survive on a null — a stale "Delayed" chip on a
        // package that is back on schedule is worse than no chip at all.
        assertTrue(r.applySnapshot("p1", TrackingSnapshot(status = TrackingStatus.OUT_FOR_DELIVERY), sourceId = "ups"))
        assertNull(assertNotNull(db.parcelDao().getById("p1")).parcel.delayNote)
    }

    @Test fun an_unclassified_snapshot_preserves_the_delay_note() = runTest {
        val r = repo(backgroundScope)
        db.parcelDao().upsertParcel(parcel.copy(delayNote = "Delayed by weather").toEntity())
        // UNKNOWN means "the scrape learned nothing", not "the delay resolved" — same rule the
        // status and ETA already follow.
        assertTrue(r.applySnapshot("p1", TrackingSnapshot(status = TrackingStatus.UNKNOWN), sourceId = "ups"))
        assertEquals("Delayed by weather", assertNotNull(db.parcelDao().getById("p1")).parcel.delayNote)
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

    @Test fun end_only_snapshot_clears_stored_window_start() = runTest {
        val r = repo(backgroundScope)
        db.parcelDao().upsertParcel(
            parcel.copy(etaWindowStart = LocalTime(15, 0), etaWindowEnd = LocalTime(17, 0)).toEntity(),
        )
        val ok = r.applySnapshot(
            "p1",
            TrackingSnapshot(status = TrackingStatus.UNKNOWN, etaWindowEnd = LocalTime(22, 0)),
            sourceId = "ups",
        )
        assertTrue(ok)
        val row = assertNotNull(db.parcelDao().getById("p1"))
        // The window is one value split across two columns: a coarser end-only snapshot must
        // clear the stale start rather than pairing it with the new end (which would render a
        // window the carrier never quoted, e.g. "3:00 PM - 10:00 PM").
        assertNull(row.parcel.etaWindowStart)
        assertEquals("22:00", row.parcel.etaWindowEnd)
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

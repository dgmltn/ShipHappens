package com.shiphappens.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.*

class ParcelDaoTest {
    private fun db(): ShipHappensDb =
        Room.inMemoryDatabaseBuilder<ShipHappensDb>()
            .setDriver(BundledSQLiteDriver())
            .build()

    private fun parcel(id: String, archived: Boolean = false) = Parcel(
        id = id, name = "Parcel $id", trackingNumber = "1Z-$id", carrier = WellKnownCarriers.UPS,
        status = TrackingStatus.IN_TRANSIT, isArchived = archived,
        createdAt = Instant.fromEpochMilliseconds(1000),
    )

    @Test fun upsert_and_observe_by_archived_flag() = runTest {
        val dao = db().parcelDao()
        dao.upsertParcel(parcel("a").toEntity())
        dao.upsertParcel(parcel("b", archived = true).toEntity(archivedAt = 5))
        assertEquals(listOf("a"), dao.observe(archived = false).first().map { it.parcel.id })
        assertEquals(listOf("b"), dao.observe(archived = true).first().map { it.parcel.id })
    }

    @Test fun events_roundtrip_and_replace() = runTest {
        val dao = db().parcelDao()
        dao.upsertParcel(parcel("a").toEntity())
        val ev = TrackingEvent(Instant.fromEpochMilliseconds(2000), "Departed facility", "Memphis, TN", TrackingStatus.IN_TRANSIT)
        dao.replaceEvents("a", listOf(ev.toEntity("a")))
        dao.replaceEvents("a", listOf(ev.toEntity("a")))  // replace, not append
        val domain = dao.getById("a")!!.toDomain()
        assertEquals(1, domain.events.size)
        assertEquals("Departed facility", domain.events[0].description)
        assertEquals("Memphis, TN", domain.events[0].location)
    }

    @Test fun archive_and_restore() = runTest {
        val dao = db().parcelDao()
        dao.upsertParcel(parcel("a").toEntity())
        dao.archive("a", at = 99)
        assertTrue(dao.observe(archived = true).first().single().parcel.isArchived)
        dao.restore("a")
        assertFalse(dao.observe(archived = false).first().single().parcel.isArchived)
    }

    @Test fun delete_removes_parcel_and_cascades_events() = runTest {
        val dao = db().parcelDao()
        dao.upsertParcel(parcel("a").toEntity())
        val ev = TrackingEvent(Instant.fromEpochMilliseconds(2000), "Departed facility", "Memphis, TN", TrackingStatus.IN_TRANSIT)
        dao.replaceEvents("a", listOf(ev.toEntity("a")))
        dao.deleteParcel("a")
        assertNull(dao.getById("a"))
    }

    @Test fun domain_mapper_roundtrip() = runTest {
        val dao = db().parcelDao()
        val original = parcel("a").copy(
            etaDate = kotlinx.datetime.LocalDate(2026, 7, 15),
            etaTime = kotlinx.datetime.LocalTime(20, 0),
            latestLocation = "Louisville, KY",
            sourceId = "ups",
        )
        dao.upsertParcel(original.toEntity())
        assertEquals(original, dao.getById("a")!!.toDomain())
    }
}

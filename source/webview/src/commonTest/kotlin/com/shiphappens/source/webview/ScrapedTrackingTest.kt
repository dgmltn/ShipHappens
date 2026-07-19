package com.shiphappens.source.webview

import com.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrapedTrackingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decodes_canonical_json() {
        val decoded = json.decodeFromString<ScrapedTracking>(
            """{"status":"IN_TRANSIT","etaDate":"2026-07-15","etaWindowEnd":"21:00","location":"Louisville, KY",
                "events":[{"timestamp":"2026-07-11T12:15:00Z","description":"Departed from Facility","location":"Louisville, KY","status":"IN_TRANSIT"}]}""",
        )
        assertEquals("IN_TRANSIT", decoded.status)
        assertEquals(1, decoded.events.size)
    }

    @Test fun toSnapshot_maps_all_fields() {
        val snap = ScrapedTracking(
            status = "OUT_FOR_DELIVERY", etaDate = "2026-07-15", etaWindowEnd = "21:00", location = "Memphis, TN",
            events = listOf(ScrapedEvent("2026-07-11T12:15:00Z", "Departed", "Louisville, KY", "IN_TRANSIT")),
        ).toSnapshot()
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, snap.status)
        assertEquals(LocalDate(2026, 7, 15), snap.etaDate)
        assertEquals(LocalTime(21, 0), snap.etaWindowEnd)
        assertNull(snap.etaWindowStart)
        assertEquals("Memphis, TN", snap.latestLocation)
        assertEquals(1, snap.events.size)
        assertEquals(TrackingStatus.IN_TRANSIT, snap.events[0].status)
    }

    @Test fun toSnapshot_tolerates_garbage() {
        val snap = ScrapedTracking(
            status = "SOMETHING_NEW", etaDate = "not-a-date", etaWindowEnd = "late",
            events = listOf(
                ScrapedEvent("garbage-timestamp", "dropped", null, null),
                ScrapedEvent("2026-07-11T12:15:00Z", "kept", null, "NOT_A_STATUS"),
            ),
        ).toSnapshot()
        assertEquals(TrackingStatus.UNKNOWN, snap.status)
        assertNull(snap.etaDate)
        assertNull(snap.etaWindowEnd)
        assertEquals(1, snap.events.size)          // bad-timestamp event dropped
        assertEquals("kept", snap.events[0].description)
        assertNull(snap.events[0].status)          // unknown status string -> null
    }

    @Test fun toSnapshot_parses_eta_window_text() {
        val snap = ScrapedTracking(
            status = "OUT_FOR_DELIVERY", etaDate = "2026-07-15",
            etaWindowText = "Arriving today 3:00 PM - 5:00 PM",
        ).toSnapshot()
        assertEquals(LocalTime(15, 0), snap.etaWindowStart)
        assertEquals(LocalTime(17, 0), snap.etaWindowEnd)
    }

    @Test fun explicit_window_times_win_over_text() {
        val snap = ScrapedTracking(
            status = "IN_TRANSIT", etaWindowEnd = "21:00",
            etaWindowText = "3:00 PM - 5:00 PM",
        ).toSnapshot()
        assertNull(snap.etaWindowStart)
        assertEquals(LocalTime(21, 0), snap.etaWindowEnd)
    }

    @Test fun unparseable_window_text_leaves_both_bounds_null() {
        val snap = ScrapedTracking(status = "IN_TRANSIT", etaWindowText = "sometime tomorrow").toSnapshot()
        assertNull(snap.etaWindowStart)
        assertNull(snap.etaWindowEnd)
    }
}

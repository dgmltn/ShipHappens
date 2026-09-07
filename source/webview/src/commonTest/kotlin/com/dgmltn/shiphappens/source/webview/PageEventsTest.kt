package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class PageEventsTest {
    private val utc = TimeZone.UTC
    private val vocabulary = StatusVocabulary()

    @Test fun event_at_pins_a_missing_time_to_midnight() {
        assertEquals(Instant.parse("2026-08-18T14:33:00Z"), eventAt(LocalDate(2026, 8, 18), LocalTime(14, 33), utc))
        assertEquals(Instant.parse("2026-08-18T00:00:00Z"), eventAt(LocalDate(2026, 8, 18), null, utc))
    }

    @Test fun us_zone_abbreviations_resolve_to_iana_zones() {
        assertEquals(TimeZone.of("America/New_York"), zoneForAbbreviation("ET"))
        assertEquals(TimeZone.of("America/Chicago"), zoneForAbbreviation(" cst "))
        assertEquals(TimeZone.of("America/Puerto_Rico"), zoneForAbbreviation("AST"))
        assertEquals(TimeZone.UTC, zoneForAbbreviation("Z"))
        assertNull(zoneForAbbreviation("XYZ"))
        assertNull(zoneForAbbreviation(null))
    }

    @Test fun today_reads_the_page_date_and_ignores_junk() {
        assertEquals(LocalDate(2026, 8, 18), DomRaw(kind = "tracker", todayIso = "2026-08-18").today())
        assertNull(DomRaw(kind = "tracker", todayIso = "yesterday").today())
        assertNull(DomRaw(kind = "tracker").today())
    }

    @Test fun iso_timestamp_wins_and_the_description_is_classified() {
        val e = assertNotNull(DomRawEvent("2026-08-18T14:33:00Z", "Departed FedEx location", "MEMPHIS, TN").toTrackingEvent(vocabulary, utc))
        assertEquals(Instant.parse("2026-08-18T14:33:00Z"), e.timestamp)
        assertEquals("Departed FedEx location", e.description)
        assertEquals("MEMPHIS, TN", e.location)
        assertEquals(TrackingStatus.IN_TRANSIT, e.status)
    }

    @Test fun when_text_builds_the_timestamp_from_date_and_clock() {
        val e = assertNotNull(DomRawEvent(description = "Picked up", whenText = "Tuesday, 8/18/26 4:16 PM").toTrackingEvent(vocabulary, utc))
        assertEquals(Instant.parse("2026-08-18T16:16:00Z"), e.timestamp)
        assertEquals(TrackingStatus.SHIPPED, e.status)
        val midnight = assertNotNull(DomRawEvent(description = "Picked up", whenText = "Tuesday, 8/18/26").toTrackingEvent(vocabulary, utc))
        assertEquals(Instant.parse("2026-08-18T00:00:00Z"), midnight.timestamp)
    }

    @Test fun relative_and_yearless_when_text_resolve_against_today() {
        val today = LocalDate(2026, 8, 18)
        val y = assertNotNull(DomRawEvent(description = "Shipped", whenText = "Yesterday 9:05 AM").toTrackingEvent(vocabulary, utc, today))
        assertEquals(Instant.parse("2026-08-17T09:05:00Z"), y.timestamp)
        val d = assertNotNull(DomRawEvent(description = "Shipped", whenText = "Tuesday, July 15 3:40 PM").toTrackingEvent(vocabulary, utc, today))
        assertEquals(Instant.parse("2026-07-15T15:40:00Z"), d.timestamp)
        assertNull(DomRawEvent(description = "Shipped", whenText = "Yesterday 9:05 AM").toTrackingEvent(vocabulary, utc, today = null))
    }

    @Test fun a_row_with_no_resolvable_date_is_dropped() {
        assertNull(DomRawEvent(description = "Picked up", whenText = "sometime").toTrackingEvent(vocabulary, utc))
        assertNull(DomRawEvent(description = "Picked up").toTrackingEvent(vocabulary, utc))
    }
}

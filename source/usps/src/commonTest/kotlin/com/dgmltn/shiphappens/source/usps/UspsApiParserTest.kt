package com.dgmltn.shiphappens.source.usps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// PROVISIONAL fixture: tools.usps.com sits behind Akamai, so this shape could not be captured
// off-device (see design spec §Decisions). Vocabulary mirrors USPS's tracking-API field names
// (statusCategory/expectedDeliveryDate/trackingEvents). Live QA (see
// docs/superpowers/qa/2026-07-14-webview-usps-qa.md) replaces this with a recorded body and
// adjusts the DTOs — same workflow that produced UpsApiParserTest's fixture.
private const val FIXTURE = """
{
  "statusCategory": "In Transit",
  "statusSummary": "Your item departed our USPS facility in SAN FRANCISCO CA DISTRIBUTION CENTER on July 14, 2026.",
  "expectedDeliveryDate": "2026-07-16",
  "expectedDeliveryTime": "20:00:00",
  "trackingEvents": [
    {"eventType": "Departed USPS Regional Facility", "eventTimestamp": "2026-07-14T02:18:00", "eventCity": "SAN FRANCISCO", "eventState": "CA"},
    {"eventType": "Arrived at USPS Regional Facility", "eventTimestamp": "2026-07-13T21:04:00", "eventCity": "SAN FRANCISCO", "eventState": "CA"},
    {"eventType": "Accepted at USPS Origin Facility", "eventTimestamp": "2026-07-13T15:47:00", "eventCity": "SANTA ROSA", "eventState": "CA"},
    {"eventType": "Shipping Label Created, USPS Awaiting Item", "eventTimestamp": "2026-07-12T09:12:00", "eventCity": "SANTA ROSA", "eventState": "CA"}
  ]
}
"""

class UspsApiParserTest {

    @Test fun parses_status_eta_location_and_events() {
        val t = assertNotNull(UspsApiParser.parse(FIXTURE))
        assertEquals("IN_TRANSIT", t.status)
        assertEquals("2026-07-16", t.etaDate)
        assertEquals("20:00", t.etaWindowEnd)
        assertEquals("SAN FRANCISCO, CA", t.location)  // newest event's city/state
        assertEquals(4, t.events.size)
        // Events chronological ASCENDING (domain expectation); USPS sends newest-first.
        assertTrue(t.events.first().description.startsWith("Shipping Label Created"))
        assertEquals("LABEL_CREATED", t.events.first().status)
        assertEquals("SHIPPED", t.events[1].status)     // "Accepted at USPS Origin Facility"
        assertEquals("IN_TRANSIT", t.events.last().status)
        assertTrue(t.events.all { runCatching { kotlin.time.Instant.parse(it.timestamp) }.isSuccess })
    }

    @Test fun status_wordings_map_to_canonical() {
        fun withCategory(c: String) = """{"statusCategory":"$c"}"""
        assertEquals("LABEL_CREATED", UspsApiParser.parse(withCategory("Pre-Shipment"))!!.status)
        assertEquals("SHIPPED", UspsApiParser.parse(withCategory("Accepted"))!!.status)
        assertEquals("IN_TRANSIT", UspsApiParser.parse(withCategory("Moving Through Network"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", UspsApiParser.parse(withCategory("Out for Delivery"))!!.status)
        assertEquals("DELIVERED", UspsApiParser.parse(withCategory("Delivered to Agent"))!!.status)
        assertEquals("EXCEPTION", UspsApiParser.parse(withCategory("Alert"))!!.status)
        assertEquals("UNKNOWN", UspsApiParser.parse(withCategory("Some New Wording"))!!.status)
    }

    @Test fun overall_status_falls_back_to_newest_event() {
        val t = assertNotNull(UspsApiParser.parse(
            """{"statusCategory":"Something Novel","trackingEvents":[
                 {"eventType":"Delivered, In/At Mailbox","eventTimestamp":"2026-07-14T13:02:00"}]}""",
        ))
        assertEquals("DELIVERED", t.status)
    }

    @Test fun tolerates_alternate_date_time_formats() {
        val t = assertNotNull(UspsApiParser.parse(
            """{"statusCategory":"In Transit","expectedDeliveryDate":"Wednesday, July 16, 2026","expectedDeliveryTime":"8:00pm"}""",
        ))
        assertEquals("2026-07-16", t.etaDate)
        assertEquals("20:00", t.etaWindowEnd)
        val slash = assertNotNull(UspsApiParser.parse(
            """{"statusCategory":"In Transit","expectedDeliveryDate":"07/16/2026"}""",
        ))
        assertEquals("2026-07-16", slash.etaDate)
    }

    @Test fun accepts_instant_event_timestamps() {
        val t = assertNotNull(UspsApiParser.parse(
            """{"statusCategory":"In Transit","trackingEvents":[
                 {"eventType":"Arrived at USPS Facility","eventTimestamp":"2026-07-14T02:18:00Z"}]}""",
        ))
        assertEquals("2026-07-14T02:18:00Z", t.events.single().timestamp)
    }

    @Test fun rejects_non_tracking_json() {
        assertNull(UspsApiParser.parse("""{"unrelated": true}"""))
        assertNull(UspsApiParser.parse("""{"trackingEvents": []}"""))
        assertNull(UspsApiParser.parse("not json"))
    }

    @Test fun location_comes_from_newest_event_regardless_of_feed_order() {
        // Ascending feed (opposite of the main fixture's newest-first order).
        val t = assertNotNull(UspsApiParser.parse(
            """{"statusCategory":"In Transit","trackingEvents":[
                 {"eventType":"Accepted at USPS Origin Facility","eventTimestamp":"2026-07-13T15:47:00","eventCity":"SANTA ROSA","eventState":"CA"},
                 {"eventType":"Departed USPS Regional Facility","eventTimestamp":"2026-07-14T02:18:00","eventCity":"SAN FRANCISCO","eventState":"CA"}]}""",
        ))
        assertEquals("SAN FRANCISCO, CA", t.location)
    }

    @Test fun tolerates_missing_fields() {
        val t = assertNotNull(UspsApiParser.parse("""{"statusCategory":"Delivered"}"""))
        assertEquals("DELIVERED", t.status)
        assertNull(t.etaDate)
        assertNull(t.etaWindowEnd)
        assertNull(t.location)
        assertTrue(t.events.isEmpty())
    }
}

package com.shiphappens.source.ups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Recorded shape of ups.com's in-page tracking API — captured live from webapis.ups.com's
// GetStatus response on 2026-07-13 (see QA checklist for re-recording steps). Trimmed to the
// fields the parser reads; the live response also carries a large languageOptions block etc.
// Note: ETA is the compact "sdd"/"sdt" pair, NOT the older "scheduledDeliveryDate".
private const val FIXTURE = """
{
  "statusCode": "200",
  "trackDetails": [{
    "trackingNumber": "1ZC1E9250328137742",
    "packageStatus": "On the Way",
    "packageStatusType": "I",
    "sdd": "20260714",
    "sdst": "11:30:00",
    "sdt": "14:30:00",
    "shipmentProgressActivities": [
      {"date": "07/12/2026", "time": "7:03 A.M.", "location": "Riverside, CA, United States", "activityScan": "Arrived at Facility"},
      {"date": "07/11/2026", "time": "2:31 P.M.", "location": "El Paso, TX, United States", "activityScan": "Departed from Facility"},
      {"date": "07/10/2026", "time": "3:31 P.M.", "location": "El Paso, TX, United States", "activityScan": "Arrived at Facility"},
      {"date": "07/10/2026", "time": "3:26 A.M.", "location": "United States", "activityScan": "Shipper created a label, UPS has not received the package yet. "}
    ]
  }]
}
"""

class UpsApiParserTest {

    @Test fun parses_status_eta_and_events() {
        val t = assertNotNull(UpsApiParser.parse(FIXTURE))
        assertEquals("IN_TRANSIT", t.status)
        assertEquals("2026-07-14", t.etaDate)  // from "sdd":"20260714"
        assertEquals("14:30", t.etaTime)        // from "sdt":"14:30:00" (end of delivery window)
        assertEquals("Riverside, CA, United States", t.location)  // newest activity's location
        assertEquals(4, t.events.size)
        // Events must be chronological ASCENDING (domain expectation); UPS sends newest-first.
        assertTrue(t.events.first().description.startsWith("Shipper created a label"))
        assertEquals("LABEL_CREATED", t.events.first().status)
        assertEquals("IN_TRANSIT", t.events.last().status)
        // Timestamps are ISO instants (parseable by the canonical layer).
        assertTrue(t.events.all { runCatching { kotlin.time.Instant.parse(it.timestamp) }.isSuccess })
    }

    @Test fun falls_back_to_legacy_scheduled_delivery_date() {
        // Older responses used "scheduledDeliveryDate" (MM/DD/YYYY) with no sdd/sdt — keep parsing it.
        val t = assertNotNull(UpsApiParser.parse(
            """{"trackDetails":[{"packageStatusType":"I","scheduledDeliveryDate":"07/15/2026"}]}""",
        ))
        assertEquals("2026-07-15", t.etaDate)
        assertNull(t.etaTime)
    }

    @Test fun status_type_codes_map_to_canonical() {
        fun withType(type: String, text: String = "x") = """{"trackDetails":[{"packageStatus":"$text","packageStatusType":"$type"}]}"""
        assertEquals("LABEL_CREATED", UpsApiParser.parse(withType("M"))!!.status)
        assertEquals("IN_TRANSIT", UpsApiParser.parse(withType("I"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", UpsApiParser.parse(withType("O"))!!.status)
        assertEquals("DELIVERED", UpsApiParser.parse(withType("D"))!!.status)
        assertEquals("EXCEPTION", UpsApiParser.parse(withType("X"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", UpsApiParser.parse(withType("", "Out for Delivery Today"))!!.status)
        assertEquals("UNKNOWN", UpsApiParser.parse(withType("", "Some New Wording"))!!.status)
    }

    @Test fun rejects_non_tracking_json() {
        assertNull(UpsApiParser.parse("""{"unrelated": true}"""))
        assertNull(UpsApiParser.parse("""{"trackDetails": []}"""))
        assertNull(UpsApiParser.parse("not json"))
    }

    @Test fun tolerates_missing_fields() {
        val t = assertNotNull(UpsApiParser.parse("""{"trackDetails":[{"packageStatusType":"D"}]}"""))
        assertEquals("DELIVERED", t.status)
        assertNull(t.etaDate)
        assertTrue(t.events.isEmpty())
    }
}

package com.dgmltn.shiphappens.source.ups

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

// Captured live from webapis.ups.com GetStatus on 2026-07-15 for an out-for-delivery package,
// trimmed to the fields the parser reads. Note UPS keeps packageStatusType "I" (coarse
// in-transit bucket) even when out for delivery — only the text/progressBar say OFD.
private const val OFD_FIXTURE = """
{
  "statusCode": "200",
  "trackDetails": [{
    "trackingNumber": "1ZW463200377332024",
    "packageStatus": "Out for Delivery",
    "packageStatusType": "I",
    "packageStatusCode": "021",
    "progressBarType": "OutForDelivery",
    "sdd": "20260715",
    "sdst": "11:30:00",
    "sdt": "14:30:00",
    "shipmentProgressActivities": [
      {"date": "07/15/2026", "time": "7:47 A.M.", "location": "Carlsbad, CA, United States", "activityScan": "Out For Delivery Today"},
      {"date": "07/15/2026", "time": "6:14 A.M.", "location": "Carlsbad, CA, United States", "activityScan": "Loaded on Delivery Vehicle "},
      {"date": "07/10/2026", "time": "11:30 A.M.", "location": "United States", "activityScan": "Shipper created a label, UPS has not received the package yet. "}
    ]
  }]
}
"""

// Captured live from webapis.ups.com GetStatus on 2026-08-28 for a weather-delayed ground
// package, trimmed to the fields the parser reads; the tracking number is synthetic. This is the
// shape that motivated delay-as-a-modifier: the package is plainly still moving ("On the Way"),
// UPS supplies a fresh ETA and a plain-English reason, yet packageStatusType is "X" and
// progressBarType "Exception".
private const val DELAYED_FIXTURE = """
{
  "statusCode": "200",
  "trackDetails": [{
    "trackingNumber": "1ZX9Y8Z70311111111",
    "packageStatus": "On the Way: Delayed",
    "packageStatusType": "X",
    "packageStatusCode": "048",
    "progressBarType": "Exception",
    "simplifiedText": "Due to weather, your package is delayed by one business day.",
    "sdd": "20260829",
    "sdst": "14:00:00",
    "sdt": "18:00:00",
    "shipmentProgressActivities": [
      {"date": "08/28/2026", "time": "3:45 A.M.", "location": "", "activityScan": "Due to weather, your package is delayed by one business day."},
      {"date": "08/25/2026", "time": "11:07 P.M.", "location": "Houston, TX, United States", "activityScan": "Departed from Facility"},
      {"date": "08/25/2026", "time": "7:25 P.M.", "location": "Houston, TX, United States", "activityScan": "Arrived at Facility"},
      {"date": "08/25/2026", "time": "7:36 A.M.", "location": "United States", "activityScan": "Shipper created a label, UPS has not received the package yet. "}
    ]
  }]
}
"""

class UpsApiParserTest {

    @Test fun out_for_delivery_text_wins_over_coarse_type_code() {
        // Live ups.com reports type "I" for OFD packages; the status text must take precedence.
        val t = assertNotNull(UpsApiParser.parse(OFD_FIXTURE))
        assertEquals("OUT_FOR_DELIVERY", t.status)
        assertEquals("2026-07-15", t.etaDate)
        assertEquals("OUT_FOR_DELIVERY", t.events.last().status)
    }

    @Test fun parses_status_eta_and_events() {
        val t = assertNotNull(UpsApiParser.parse(FIXTURE))
        assertEquals("IN_TRANSIT", t.status)
        assertEquals("2026-07-14", t.etaDate)  // from "sdd":"20260714"
        assertEquals("14:30", t.etaWindowEnd)   // from "sdt":"14:30:00" (end of delivery window)
        assertEquals("11:30", t.etaWindowStart) // from "sdst":"11:30:00" (start of delivery window)
        assertEquals("Riverside, CA, United States", t.location)  // newest activity's location
        assertEquals(4, t.events.size)
        // Events must be chronological ASCENDING (domain expectation); UPS sends newest-first.
        assertTrue(t.events.first().description.startsWith("Shipper created a label"))
        assertEquals("LABEL_CREATED", t.events.first().status)
        assertEquals("IN_TRANSIT", t.events.last().status)
        // Timestamps are ISO instants (parseable by the canonical layer).
        assertTrue(t.events.all { runCatching { kotlin.time.Instant.parse(it.timestamp) }.isSuccess })
    }

    @Test fun a_delayed_package_keeps_the_stage_it_is_actually_at() {
        // packageStatusType "X" would say EXCEPTION; the text says the package is still moving.
        val t = assertNotNull(UpsApiParser.parse(DELAYED_FIXTURE))
        assertEquals("IN_TRANSIT", t.status)
    }

    @Test fun a_delayed_package_carries_the_carriers_own_reason() {
        val t = assertNotNull(UpsApiParser.parse(DELAYED_FIXTURE))
        assertEquals("Due to weather, your package is delayed by one business day.", t.delayNote)
    }

    @Test fun a_delayed_package_keeps_its_revised_eta_and_window() {
        val t = assertNotNull(UpsApiParser.parse(DELAYED_FIXTURE))
        assertEquals("2026-08-29", t.etaDate)
        assertEquals("14:00", t.etaWindowStart)
        assertEquals("18:00", t.etaWindowEnd)
    }

    @Test fun a_delay_with_no_reason_sentence_falls_back_to_the_status_headline() {
        val t = assertNotNull(UpsApiParser.parse(
            """{"trackDetails":[{"packageStatus":"On the Way: Delayed","packageStatusType":"X"}]}""",
        ))
        assertEquals("On the Way: Delayed", t.delayNote)
    }

    @Test fun an_undelayed_package_has_no_delay_note() {
        assertNull(assertNotNull(UpsApiParser.parse(FIXTURE)).delayNote)
        assertNull(assertNotNull(UpsApiParser.parse(OFD_FIXTURE)).delayNote)
    }

    @Test fun falls_back_to_legacy_scheduled_delivery_date() {
        // Older responses used "scheduledDeliveryDate" (MM/DD/YYYY) with no sdd/sdt — keep parsing it.
        val t = assertNotNull(UpsApiParser.parse(
            """{"trackDetails":[{"packageStatusType":"I","scheduledDeliveryDate":"07/15/2026"}]}""",
        ))
        assertEquals("2026-07-15", t.etaDate)
        assertNull(t.etaWindowEnd)
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

package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant
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

// Captured live from webapis.ups.com GetStatus on 2026-10-07 for an international package,
// trimmed to the fields the parser reads; the tracking number is synthetic. Each scan's "date" and
// "time" are local to the facility, while gmtDate/gmtTime pin it to UTC: Hong Kong's 7:24 P.M.
// departure happened before Anchorage's 12:57 P.M. arrival.
private const val INTERNATIONAL_FIXTURE = """
{
  "statusCode": "200",
  "trackDetails": [{
    "trackingNumber": "1ZA1B2C3D4E5F6G7H8",
    "packageStatus": "On the Way",
    "packageStatusType": "I",
    "shipFromGMTOffset": "+08:00",
    "shipToGMTOffset": "-07:00",
    "shipmentProgressActivities": [
      {"date": "10/03/2026", "time": "4:26 A.M.", "location": "Louisville, KY, United States", "activityScan": "Departed from Facility", "gmtDate": "20261003", "gmtOffset": "-04:00", "gmtTime": "08:26:00"},
      {"date": "10/01/2026", "time": "12:57 P.M.", "location": "Anchorage, AK, United States", "activityScan": "Arrived at Facility", "gmtDate": "20261001", "gmtOffset": "-08:00", "gmtTime": "20:57:00"},
      {"date": "10/01/2026", "time": "7:24 P.M.", "location": "Chek Lap Kok, Hong Kong", "activityScan": "Departed from Facility", "gmtDate": "20261001", "gmtOffset": "+08:00", "gmtTime": "11:24:00"},
      {"date": "09/29/2026", "time": "2:29 P.M.", "location": "Chek Lap Kok, Hong Kong", "activityScan": "Arrived at Facility", "gmtDate": "20260929", "gmtOffset": "+08:00", "gmtTime": "06:29:00"},
      {"date": "09/29/2026", "time": "12:30 P.M.", "location": "Shenzhen, China", "activityScan": "Departed from Facility", "gmtDate": "20260929", "gmtOffset": "+08:00", "gmtTime": "04:30:00"},
      {"date": "", "time": "", "location": "China", "activityScan": "Shipper created a label, UPS has not received the package yet. ", "gmtDate": "", "gmtOffset": "", "gmtTime": ""}
    ]
  }]
}
"""

private fun oneActivity(fields: String) =
    """{"trackDetails":[{"packageStatusType":"I","shipmentProgressActivities":[{"date":"10/01/2026","time":"7:24 P.M.","location":"Chek Lap Kok, Hong Kong","activityScan":"Departed from Facility"$fields}]}]}"""

class UpsApiParserTest {

    @Test fun scan_times_are_read_as_the_utc_instant_ups_reports() {
        val t = assertNotNull(UpsApiParser.parse(INTERNATIONAL_FIXTURE))
        val hongKongDeparture = t.events.single { it.location == "Chek Lap Kok, Hong Kong" && it.description == "Departed from Facility" }
        assertEquals(Instant.parse("2026-10-01T11:24:00Z"), hongKongDeparture.timestamp)
    }

    @Test fun scans_in_different_time_zones_keep_their_real_order() {
        val t = assertNotNull(UpsApiParser.parse(INTERNATIONAL_FIXTURE))
        assertEquals(
            listOf(
                "Shenzhen, China",
                "Chek Lap Kok, Hong Kong",
                "Chek Lap Kok, Hong Kong",
                "Anchorage, AK, United States",
                "Louisville, KY, United States",
            ),
            t.events.map { it.location },
        )
    }

    @Test fun a_scan_with_only_an_offset_is_shifted_by_that_offset() {
        val t = assertNotNull(UpsApiParser.parse(oneActivity(""","gmtOffset":"+08:00"""")))
        assertEquals(Instant.parse("2026-10-01T11:24:00Z"), t.events.single().timestamp)
    }

    @Test fun a_scan_with_blank_utc_fields_falls_back_to_the_offset() {
        val t = assertNotNull(UpsApiParser.parse(oneActivity(""","gmtDate":"","gmtTime":"","gmtOffset":"+08:00"""")))
        assertEquals(Instant.parse("2026-10-01T11:24:00Z"), t.events.single().timestamp)
    }

    @Test fun a_scan_with_no_zone_information_is_read_in_the_device_zone() {
        val t = assertNotNull(UpsApiParser.parse(oneActivity("")))
        val expected = LocalDateTime(2026, 10, 1, 19, 24).toInstant(TimeZone.currentSystemDefault())
        assertEquals(expected, t.events.single().timestamp)
    }

    @Test fun a_malformed_offset_falls_back_to_the_device_zone() {
        val t = assertNotNull(UpsApiParser.parse(oneActivity(""","gmtOffset":"bogus"""")))
        val expected = LocalDateTime(2026, 10, 1, 19, 24).toInstant(TimeZone.currentSystemDefault())
        assertEquals(expected, t.events.single().timestamp)
    }

    @Test fun out_for_delivery_text_wins_over_coarse_type_code() {
        // Live ups.com reports type "I" for OFD packages; the status text must take precedence.
        val t = assertNotNull(UpsApiParser.parse(OFD_FIXTURE))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, t.status)
        assertEquals(LocalDate(2026, 7, 15), t.etaDate)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, t.events.last().status)
    }

    @Test fun parses_status_eta_and_events() {
        val t = assertNotNull(UpsApiParser.parse(FIXTURE))
        assertEquals(TrackingStatus.IN_TRANSIT, t.status)
        assertEquals(LocalDate(2026, 7, 14), t.etaDate)  // from "sdd":"20260714"
        assertEquals(LocalTime(14, 30), t.etaWindowEnd)   // from "sdt":"14:30:00" (end of delivery window)
        assertEquals(LocalTime(11, 30), t.etaWindowStart) // from "sdst":"11:30:00" (start of delivery window)
        assertEquals("Riverside, CA, United States", t.latestLocation)  // newest activity's location
        assertEquals(4, t.events.size)
        // Events must be chronological ASCENDING (domain expectation); UPS sends newest-first.
        assertTrue(t.events.first().description.startsWith("Shipper created a label"))
        assertEquals(TrackingStatus.LABEL_CREATED, t.events.first().status)
        assertEquals(TrackingStatus.IN_TRANSIT, t.events.last().status)
    }

    @Test fun a_delayed_package_keeps_the_stage_it_is_actually_at() {
        // packageStatusType "X" would say EXCEPTION; the text says the package is still moving.
        val t = assertNotNull(UpsApiParser.parse(DELAYED_FIXTURE))
        assertEquals(TrackingStatus.IN_TRANSIT, t.status)
    }

    @Test fun a_delayed_package_carries_the_carriers_own_reason() {
        val t = assertNotNull(UpsApiParser.parse(DELAYED_FIXTURE))
        assertEquals("Due to weather, your package is delayed by one business day.", t.delayNote)
    }

    @Test fun a_delayed_package_keeps_its_revised_eta_and_window() {
        val t = assertNotNull(UpsApiParser.parse(DELAYED_FIXTURE))
        assertEquals(LocalDate(2026, 8, 29), t.etaDate)
        assertEquals(LocalTime(14, 0), t.etaWindowStart)
        assertEquals(LocalTime(18, 0), t.etaWindowEnd)
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
        assertEquals(LocalDate(2026, 7, 15), t.etaDate)
        assertNull(t.etaWindowEnd)
    }

    @Test fun status_type_codes_map_to_canonical() {
        fun withType(type: String, text: String = "x") = """{"trackDetails":[{"packageStatus":"$text","packageStatusType":"$type"}]}"""
        assertEquals(TrackingStatus.LABEL_CREATED, UpsApiParser.parse(withType("M"))!!.status)
        assertEquals(TrackingStatus.IN_TRANSIT, UpsApiParser.parse(withType("I"))!!.status)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, UpsApiParser.parse(withType("O"))!!.status)
        assertEquals(TrackingStatus.DELIVERED, UpsApiParser.parse(withType("D"))!!.status)
        assertEquals(TrackingStatus.EXCEPTION, UpsApiParser.parse(withType("X"))!!.status)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, UpsApiParser.parse(withType("", "Out for Delivery Today"))!!.status)
        assertEquals(TrackingStatus.UNKNOWN, UpsApiParser.parse(withType("", "Some New Wording"))!!.status)
    }

    @Test fun rejects_non_tracking_json() {
        assertNull(UpsApiParser.parse("""{"unrelated": true}"""))
        assertNull(UpsApiParser.parse("""{"trackDetails": []}"""))
        assertNull(UpsApiParser.parse("not json"))
    }

    @Test fun tolerates_missing_fields() {
        val t = assertNotNull(UpsApiParser.parse("""{"trackDetails":[{"packageStatusType":"D"}]}"""))
        assertEquals(TrackingStatus.DELIVERED, t.status)
        assertNull(t.etaDate)
        assertTrue(t.events.isEmpty())
    }
}

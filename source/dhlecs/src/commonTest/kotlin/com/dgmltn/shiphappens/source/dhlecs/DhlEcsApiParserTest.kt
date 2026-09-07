package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

// All tracking values in these fixtures are fabricated; only the JSON shape mirrors the live
// api.dhlecs.com/webtrack/v4/tracking response (recon 2026-09-03).
class DhlEcsApiParserTest {

    private fun envelope(vararg packages: String) =
        """{"total":${packages.size},"limit":10,"offset":0,"packages":[${packages.joinToString(",")}]}"""

    private val enRoutePackage = """
        {"status":"En Route",
         "trackedValue":"420300019261234500000000000042",
         "trackingId":"420300019261234500000000000042",
         "deliveryConfirmationNumber":"9261234500000000000042",
         "productClass":"Domestic","productId":81,"productName":"DHL Parcel Expedited",
         "dspName":"USPS",
         "estimatedDeliveryDate":"2026-09-08",
         "sender":{"city":"PLAINFIELD","state":"IN","country":"US","postalCode":"46168"},
         "recipient":{"country":"US"},
         "events":[
           {"primaryEventId":200,"date":"2026-09-04","time":"18:05:00","timeZone":"CT",
            "primaryEventDescription":"ARRIVAL DHL ECOMMERCE FACILITY","location":"Whitestown, IN, US","isStopClock":false},
           {"primaryEventId":238,"date":"2026-09-03","time":"09:49:13","timeZone":"ET",
            "primaryEventDescription":"LABEL CREATED","location":"Hebron, KY, US","isStopClock":false}
         ]}
    """.trimIndent()

    @Test fun parses_status_eta_and_events_from_the_live_shape() {
        val tracking = DhlEcsApiParser.parse(envelope(enRoutePackage))!!
        assertEquals(TrackingStatus.IN_TRANSIT, tracking.status)
        assertEquals(LocalDate(2026, 9, 8), tracking.etaDate)
        assertEquals("Whitestown, IN, US", tracking.latestLocation)
        assertNull(tracking.delayNote)
        assertEquals(2, tracking.events.size)
    }

    @Test fun events_sort_ascending_and_carry_zone_resolved_timestamps() {
        // API lists newest first; canonical order is ascending. ET in September is EDT (UTC-4),
        // CT is CDT (UTC-5).
        val events = DhlEcsApiParser.parse(envelope(enRoutePackage))!!.events
        assertEquals(Instant.parse("2026-09-03T13:49:13Z"), events[0].timestamp)
        assertEquals("Label created", events[0].description)
        assertEquals("Hebron, KY, US", events[0].location)
        assertEquals(TrackingStatus.LABEL_CREATED, events[0].status)
        assertEquals(Instant.parse("2026-09-04T23:05:00Z"), events[1].timestamp)
        assertEquals("Arrival DHL eCommerce facility", events[1].description)
        assertEquals(TrackingStatus.IN_TRANSIT, events[1].status)
    }

    @Test fun pacific_and_winter_zone_abbreviations_resolve() {
        val body = envelope(
            """{"status":"En Route","events":[
                {"primaryEventId":540,"date":"2026-01-15","time":"08:00:00","timeZone":"PT",
                 "primaryEventDescription":"PROCESSED THROUGH SORT FACILITY","location":"Compton, CA, US"}]}"""
        )
        assertEquals(Instant.parse("2026-01-15T16:00:00Z"), DhlEcsApiParser.parse(body)!!.events.single().timestamp)
    }

    @Test fun unknown_zone_abbreviation_keeps_the_event() {
        val body = envelope(
            """{"status":"En Route","events":[
                {"primaryEventId":540,"date":"2026-01-15","time":"08:00:00","timeZone":"XX",
                 "primaryEventDescription":"PROCESSED THROUGH SORT FACILITY"}]}"""
        )
        assertEquals(1, DhlEcsApiParser.parse(body)!!.events.size)
    }

    @Test fun electronic_notification_is_label_created() {
        val body = envelope("""{"status":"Electronic Notification","events":[]}""")
        assertEquals(TrackingStatus.LABEL_CREATED, DhlEcsApiParser.parse(body)!!.status)
    }

    @Test fun delivered_status_classifies() {
        val body = envelope("""{"status":"Delivered","events":[]}""")
        assertEquals(TrackingStatus.DELIVERED, DhlEcsApiParser.parse(body)!!.status)
    }

    @Test fun unreadable_status_falls_back_to_the_newest_classifiable_event() {
        val body = envelope(
            """{"status":"Some New Wording","events":[
                {"primaryEventId":597,"date":"2026-09-05","time":"07:30:00","timeZone":"ET",
                 "primaryEventDescription":"OUT FOR DELIVERY","location":"Carlsbad, CA, US"}]}"""
        )
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, DhlEcsApiParser.parse(body)!!.status)
    }

    @Test fun delay_event_reports_a_note_but_not_a_stage() {
        // A delay is a modifier, not a stage (2026-08-28): the package-level status keeps the
        // stage, and the delay wording rides alongside as the note.
        val body = envelope(
            """{"status":"En Route","events":[
                {"primaryEventId":593,"date":"2026-09-05","time":"11:00:00","timeZone":"CT",
                 "primaryEventDescription":"POSSIBLE DELIVERY DELAY - ADVERSE WEATHER","location":"Whitestown, IN, US"},
                {"primaryEventId":540,"date":"2026-09-04","time":"18:05:00","timeZone":"CT",
                 "primaryEventDescription":"PROCESSED THROUGH SORT FACILITY","location":"Whitestown, IN, US"}]}"""
        )
        val tracking = DhlEcsApiParser.parse(body)!!
        assertEquals(TrackingStatus.IN_TRANSIT, tracking.status)
        assertEquals("Possible delivery delay - adverse weather", tracking.delayNote)
        assertNull(tracking.events.last().status)  // stage-less wording asserts no stage
    }

    @Test fun old_delay_event_does_not_flag_a_moved_on_package() {
        // Non-null delayNote IS the delay flag, and delay events stay in the history for the
        // life of the shipment — only the newest event (or the live status) may assert one.
        val body = envelope(
            """{"status":"Delivered","events":[
                {"primaryEventId":600,"date":"2026-09-06","time":"14:10:00","timeZone":"ET",
                 "primaryEventDescription":"DELIVERED","location":"Carlsbad, CA, US"},
                {"primaryEventId":593,"date":"2026-09-05","time":"11:00:00","timeZone":"CT",
                 "primaryEventDescription":"POSSIBLE DELIVERY DELAY - ADVERSE WEATHER"}]}"""
        )
        val tracking = DhlEcsApiParser.parse(body)!!
        assertEquals(TrackingStatus.DELIVERED, tracking.status)
        assertNull(tracking.delayNote)
    }

    @Test fun atlantic_zone_resolves_for_puerto_rico_scans() {
        // AST has no DST: 10:00 AST = 14:00Z year-round.
        val body = envelope(
            """{"status":"En Route","events":[
                {"primaryEventId":540,"date":"2026-07-10","time":"10:00:00","timeZone":"AST",
                 "primaryEventDescription":"PROCESSED THROUGH SORT FACILITY","location":"San Juan, PR, US"}]}"""
        )
        assertEquals(Instant.parse("2026-07-10T14:00:00Z"), DhlEcsApiParser.parse(body)!!.events.single().timestamp)
    }

    @Test fun undelivered_wording_is_an_exception_not_a_delivery() {
        val body = envelope(
            """{"status":"En Route","events":[
                {"primaryEventId":636,"date":"2026-09-05","time":"11:00:00","timeZone":"ET",
                 "primaryEventDescription":"UNDELIVERED - PROCESSES FOR LOCAL DISPOSAL"}]}"""
        )
        assertEquals(TrackingStatus.EXCEPTION, DhlEcsApiParser.parse(body)!!.events.single().status)
    }

    @Test fun eta_with_a_time_component_still_yields_the_date() {
        val body = envelope("""{"status":"En Route","estimatedDeliveryDate":"2026-09-08T00:00:00Z","events":[]}""")
        assertEquals(LocalDate(2026, 9, 8), DhlEcsApiParser.parse(body)!!.etaDate)
    }

    @Test fun no_packages_returns_null_for_the_dom_fallback_to_own_not_found() {
        assertNull(DhlEcsApiParser.parse("""{"total":0,"limit":10,"offset":0,"packages":[]}"""))
    }

    @Test fun garbage_returns_null() {
        assertNull(DhlEcsApiParser.parse("not json"))
        assertNull(DhlEcsApiParser.parse("""{"unrelated":true}"""))
    }
}

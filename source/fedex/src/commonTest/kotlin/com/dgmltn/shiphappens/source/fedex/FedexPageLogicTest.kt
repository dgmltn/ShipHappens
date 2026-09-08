package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.DomRawEvent
import com.dgmltn.shiphappens.source.webview.PageOutcome
import com.dgmltn.shiphappens.source.webview.findEtaWindowText
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.snapshotOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class FedexPageLogicTest {

    private fun page(raw: DomRaw) = resolveTrackerPage(raw, FEDEX_PAGE, TimeZone.UTC)
    private fun eta(text: String?, today: LocalDate? = null) = FEDEX_PAGE.etaDate(DomRaw(kind = "tracker", etaText = text), today)

    // -- status vocabulary (fedex.com wording, banner headline and scan-event descriptions) --

    @Test fun classifies_delivered() {
        assertEquals(TrackingStatus.DELIVERED, FEDEX_VOCABULARY.classify("Delivered"))
        assertEquals(TrackingStatus.DELIVERED, FEDEX_VOCABULARY.classify("Delivered: Left at front door"))
    }

    @Test fun classifies_out_for_delivery_including_vehicle_wording() {
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, FEDEX_VOCABULARY.classify("Out for delivery"))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, FEDEX_VOCABULARY.classify("On FedEx vehicle for delivery"))
    }

    @Test fun classifies_transit_wordings() {
        assertEquals(TrackingStatus.IN_TRANSIT, FEDEX_VOCABULARY.classify("In transit"))
        assertEquals(TrackingStatus.IN_TRANSIT, FEDEX_VOCABULARY.classify("On the way"))
        assertEquals(TrackingStatus.IN_TRANSIT, FEDEX_VOCABULARY.classify("Departed FedEx location"))
        assertEquals(TrackingStatus.IN_TRANSIT, FEDEX_VOCABULARY.classify("Arrived at FedEx location"))
        assertEquals(TrackingStatus.IN_TRANSIT, FEDEX_VOCABULARY.classify("At local FedEx facility"))
        assertEquals(TrackingStatus.IN_TRANSIT, FEDEX_VOCABULARY.classify("At destination sort facility"))
        assertEquals(TrackingStatus.IN_TRANSIT, FEDEX_VOCABULARY.classify("International shipment release - Import"))
    }

    @Test fun classifies_picked_up_as_shipped() {
        assertEquals(TrackingStatus.SHIPPED, FEDEX_VOCABULARY.classify("Picked up"))
        assertEquals(TrackingStatus.SHIPPED, FEDEX_VOCABULARY.classify("We have your package"))
    }

    @Test fun classifies_label_created_wordings() {
        assertEquals(TrackingStatus.LABEL_CREATED, FEDEX_VOCABULARY.classify("Label created"))
        assertEquals(TrackingStatus.LABEL_CREATED, FEDEX_VOCABULARY.classify("Shipment information sent to FedEx"))
    }

    @Test fun classifies_exception_family() {
        assertEquals(TrackingStatus.EXCEPTION, FEDEX_VOCABULARY.classify("Delivery exception"))
        assertEquals(TrackingStatus.EXCEPTION, FEDEX_VOCABULARY.classify("Shipment exception"))
        // A delay names no stage — the stage comes from the travel history instead (2026-08-28).
        assertNull(FEDEX_VOCABULARY.classify("Delivery updated - delay"))
        assertEquals(TrackingStatus.EXCEPTION, FEDEX_VOCABULARY.classify("Held at FedEx location for pickup"))
        assertEquals(TrackingStatus.EXCEPTION, FEDEX_VOCABULARY.classify("Returning to shipper"))
    }

    @Test fun unknown_wording_is_null_not_a_guess() {
        assertNull(FEDEX_VOCABULARY.classify("Delivery date pending"))
        assertNull(FEDEX_VOCABULARY.classify(""))
        assertNull(FEDEX_VOCABULARY.classify(null))
    }

    // -- ETA date parsing (banner text; both fedex date renderings plus relative wording) --

    @Test fun parses_numeric_month_day_year() {
        assertEquals(LocalDate(2026, 8, 19), eta("Estimated delivery Tuesday 8/19/2026 by end of day"))
    }

    @Test fun parses_run_together_weekday_and_date() {
        // The live hero flattens to "Thursday8/20/2026" — no space between weekday and date
        // (QA capture 2026-08-19), so the date match can't demand a word boundary.
        assertEquals(
            LocalDate(2026, 8, 20),
            eta("ESTIMATED DELIVERY DATE Thursday8/20/2026 Between 10:10 AM - 2:10 PM"),
        )
    }

    @Test fun parses_month_name_form() {
        assertEquals(LocalDate(2026, 8, 19), eta("Estimated delivery: Tuesday, August 19, 2026"))
    }

    @Test fun resolves_today_and_tomorrow_against_page_date() {
        val today = LocalDate(2026, 8, 19)
        assertEquals(LocalDate(2026, 8, 19), eta("Estimated delivery today by 8:00 PM", today))
        assertEquals(LocalDate(2026, 8, 20), eta("Estimated delivery tomorrow by end of day", today))
        assertNull(eta("Estimated delivery today by 8:00 PM"))
    }

    @Test fun pending_banner_has_no_date() {
        assertNull(eta("Estimated delivery Pending"))
        assertNull(eta(null))
    }

    // -- delivery-window phrase extraction (fed to the shared EtaWindowParser downstream) --

    @Test fun extracts_cutoff_and_range_phrases() {
        assertEquals("by 8:00 PM", findEtaWindowText("Estimated delivery Tuesday 8/19/2026 by 8:00 PM"))
        assertEquals("10:35 AM - 2:35 PM", findEtaWindowText("Estimated delivery window 10:35 AM - 2:35 PM"))
        assertEquals(
            "between 10:35 AM and 2:35 PM",
            findEtaWindowText("Arriving between 10:35 AM and 2:35 PM"),
        )
    }

    @Test fun end_of_day_is_not_a_window() {
        assertNull(findEtaWindowText("Estimated delivery Tuesday 8/19/2026 by end of day"))
        assertNull(findEtaWindowText(null))
    }

    // -- raw tracker-page routing --

    @Test fun tracker_page_classifies_banner_and_parses_eta() {
        val result = page(
            DomRaw(
                kind = "tracker",
                statusText = "In transit",
                etaText = "Estimated delivery Tuesday 8/19/2026 by 8:00 PM",
                todayIso = "2026-08-18",
            ),
        )
        assertIs<PageOutcome.Tracking>(result)
        val s = result.snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
        assertEquals(LocalDate(2026, 8, 19), s.etaDate)
        assertEquals(LocalTime(20, 0), s.etaWindowEnd)
        assertNull(s.etaWindowStart)
    }

    @Test fun unreadable_banner_falls_back_to_newest_classifiable_event() {
        val result = page(
            DomRaw(
                kind = "tracker",
                statusText = "Delivery date pending",
                events = listOf(
                    DomRawEvent("2026-08-17T09:00:00Z", "Picked up", "SANTA CLARA, CA"),
                    DomRawEvent("2026-08-18T14:33:00Z", "Departed FedEx location", "MEMPHIS, TN"),
                ),
            ),
        )
        val s = result.snapshotOrNull()
        assertEquals(TrackingStatus.IN_TRANSIT, s?.status)
        assertEquals("MEMPHIS, TN", s?.latestLocation)
        assertEquals(2, s?.events?.size)
    }

    @Test fun weekday_only_promise_resolves_against_page_date() {
        // Second live capture 2026-08-19, minutes after the run-together one: the hero rendered
        // "Thursday Between 10:10 AM - 2:10 PM" — weekday only, no numeric date at all.
        val result = page(
            DomRaw(
                kind = "tracker",
                statusText = "On the way",
                etaText = "Thursday Between 10:10 AM - 2:10 PM",
                locationText = "Currently in Sacramento, CA",
                todayIso = "2026-08-19",
            ),
        )
        val s = result.snapshotOrNull()
        assertEquals(LocalDate(2026, 8, 20), s?.etaDate)
        assertEquals(LocalTime(10, 10), s?.etaWindowStart)
        assertEquals(LocalTime(14, 10), s?.etaWindowEnd)
    }

    @Test fun live_capture_shape_parses_fully() {
        // Field values verbatim from the 2026-08-19 QA capture of a moving FedEx Ground package.
        val result = page(
            DomRaw(
                kind = "tracker",
                statusText = "On the way",
                etaText = "ESTIMATED DELIVERY DATE Thursday8/20/2026 Between 10:10 AM - 2:10 PM",
                locationText = "Currently in Sacramento, CA",
                todayIso = "2026-08-19",
            ),
        )
        val s = result.snapshotOrNull()
        assertEquals(TrackingStatus.IN_TRANSIT, s?.status)
        assertEquals(LocalDate(2026, 8, 20), s?.etaDate)
        assertEquals(LocalTime(10, 10), s?.etaWindowStart)
        assertEquals(LocalTime(14, 10), s?.etaWindowEnd)
        assertEquals("Sacramento, CA", s?.latestLocation)
    }

    @Test fun delivered_page_capture_classifies_delivered() {
        // Verbatim from the 2026-08-20 delivered-state capture: the progress bar is gone, so the
        // extraction falls back to the delivery-date eyebrow, which now reads "DELIVERED"; the
        // date element shows the actual delivery time. Bug pinned: the pre-fix selector chain
        // scraped statusText null here, so the card kept the stale "Out for delivery".
        val result = page(
            DomRaw(
                kind = "tracker",
                statusText = "DELIVERED",
                etaText = "Thursday8/20/2026 at 1:48 pm",
                todayIso = "2026-08-20",
            ),
        )
        val s = result.snapshotOrNull()
        assertEquals(TrackingStatus.DELIVERED, s?.status)
        assertEquals(LocalDate(2026, 8, 20), s?.etaDate)
        assertNull(s?.etaWindowStart)
        assertNull(s?.etaWindowEnd)  // "at 1:48 pm" is a delivery time, not a window
    }

    @Test fun estimated_delivery_date_eyebrow_is_not_a_status() {
        // The same eyebrow reads "ESTIMATED DELIVERY DATE" on moving pages; if it ever reaches
        // the classifier (progress bar missing on a transit page), it must classify to nothing
        // rather than misreport — the events/ETA fallbacks take over.
        assertNull(FEDEX_VOCABULARY.classify("ESTIMATED DELIVERY DATE"))
    }

    @Test fun eta_without_status_headline_still_reports() {
        // A page variant that renders the promise but no classifiable headline must not be
        // mistaken for an empty shell — the ETA alone is worth surfacing (status UNKNOWN).
        val result = page(DomRaw(kind = "tracker", etaText = "Thursday8/20/2026"))
        assertIs<PageOutcome.Tracking>(result)
        val s = result.snapshotOrNull()!!
        assertEquals(TrackingStatus.UNKNOWN, s.status)
        assertEquals(LocalDate(2026, 8, 20), s.etaDate)
    }

    // -- page-state wording decided in Kotlin (moved out of the JS blob 2026-08-20) --

    @Test fun not_found_wordings_route_not_found_from_page_text() {
        // All three live wordings: /no-results-found, the system-error page, and the shipper hint.
        listOf(
            "FedEx ® Tracking The tracking number you entered can't be found right now. Please check the number with the shipper or try again later.",
            "FedEx ® Tracking We can’t find that tracking number. Please check with the shipper to make sure it’s the correct one.",
            "no record of this tracking number",
        ).forEach { wording ->
            val result = page(DomRaw(kind = "tracker", pageText = wording))
            assertIs<PageOutcome.NotFound>(result, "wording: $wording")
        }
    }

    @Test fun ordinary_page_text_does_not_trip_not_found() {
        val result = page(
            DomRaw(kind = "tracker", statusText = "On the way", pageText = "Fedex Home # 875900001234 On the way"),
        )
        assertIs<PageOutcome.Tracking>(result)
    }

    // -- travel-history rows: date-group header + bare time arrive verbatim in whenText --

    @Test fun events_build_timestamps_from_when_text_and_sort_ascending() {
        // Rows in document order = newest first, exactly as the details view renders them.
        val result = page(
            DomRaw(
                kind = "tracker",
                statusText = "DELIVERED",
                events = listOf(
                    DomRawEvent(whenText = "Thursday, 08/20/2026 1:48 PM", description = "Delivered", location = "Carlsbad, CA"),
                    DomRawEvent(whenText = "Wednesday, 08/19/2026 3:06 AM", description = "Departed FedEx location", location = "SACRAMENTO, CA"),
                ),
            ),
        )
        val events = result.snapshotOrNull()?.events.orEmpty()
        assertEquals(listOf("Departed FedEx location", "Delivered"), events.map { it.description })
        assertEquals(
            LocalDateTime(LocalDate(2026, 8, 19), LocalTime(3, 6)).toInstant(TimeZone.UTC),
            events.first().timestamp,
        )
        assertEquals(listOf(TrackingStatus.IN_TRANSIT, TrackingStatus.DELIVERED), events.map { it.status })
        assertEquals("Carlsbad, CA", result.snapshotOrNull()?.latestLocation)  // newest event's location
    }

    @Test fun live_travel_history_rows_parse_and_classify() {
        // Verbatim from the 2026-08-21 Pixel capture of the delivered package's travel history:
        // two-digit years, per-day date cells, fixed time/description/location triples.
        val result = page(
            DomRaw(
                kind = "tracker",
                events = listOf(
                    DomRawEvent(whenText = "Tuesday, 8/18/26 4:16 PM", description = "Picked up", location = "SOUTH SAN FRANCISCO, CA"),
                    DomRawEvent(
                        whenText = "Tuesday, 8/18/26 3:32 PM",
                        description = "In FedEx possession Package received after final location pickup has occurred.",
                        location = "FOSTER CITY, CA",
                    ),
                    DomRawEvent(whenText = "Tuesday, 8/18/26 9:48 AM", description = "Shipment information sent to FedEx"),
                ),
            ),
        )
        val events = result.snapshotOrNull()?.events.orEmpty()
        assertEquals(3, events.size)
        assertEquals("Shipment information sent to FedEx", events.first().description)   // ascending
        assertEquals(listOf(TrackingStatus.LABEL_CREATED, TrackingStatus.SHIPPED, TrackingStatus.SHIPPED), events.map { it.status })
        assertEquals(
            LocalDateTime(LocalDate(2026, 8, 18), LocalTime(9, 48)).toInstant(TimeZone.UTC),
            events.first().timestamp,
        )
        assertEquals("SOUTH SAN FRANCISCO, CA", result.snapshotOrNull()?.latestLocation)  // newest with a location
    }

    @Test fun rows_without_a_parseable_when_text_are_dropped_not_fatal() {
        val result = page(
            DomRaw(
                kind = "tracker",
                statusText = "On the way",
                events = listOf(
                    DomRawEvent(whenText = "Pending", description = "Mystery row"),
                    DomRawEvent(whenText = "Wednesday, 08/19/2026 3:06 AM", description = "Departed FedEx location"),
                ),
            ),
        )
        assertEquals(listOf("Departed FedEx location"), result.snapshotOrNull()?.events.orEmpty().map { it.description })
    }

    @Test fun blank_page_is_empty_and_foreign_kind_is_null() {
        assertIs<PageOutcome.Empty>(page(DomRaw(kind = "tracker")))
        assertNull(page(DomRaw(kind = "cards", statusText = "In transit")))
    }
}

package com.dgmltn.shiphappens.source.usps

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.DomRawEvent
import com.dgmltn.shiphappens.source.webview.PageOutcome
import com.dgmltn.shiphappens.source.webview.findEtaWindowText
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.snapshotOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Strings here are verbatim from the 2026-07-24 live scrape of 9400150106851001311909
 * (ScrapeTracer log) — the scrape that classified an in-transit package UNKNOWN because
 * "On the Way" wasn't in the JS blob's vocabulary, and lost the July 28 ETA because the
 * selector read only the bare day number "28" out of USPS's split-span date markup.
 */
class UspsVocabularyTest {

    private fun page(raw: DomRaw) = resolveTrackerPage(raw, USPS_PAGE, TimeZone.UTC)

    // The .expected_delivery banner, textContent-flattened: the date is split across
    // .day/.date/.month_year spans and two tooltips interleave their copy into the text.
    private val etaBanner = "Expected Delivery by: Tuesday 28 July 2026 Expected Delivery Date " +
        "Expected delivery on the date provided is the latest information on when the Postal Service " +
        "expects to deliver your package. by 9:00pm Expected Delivery Time The timeframe provided is " +
        "the estimated timeframe when the carrier will attempt to deliver your package."

    private val liveRaw = DomRaw(
        kind = "tracker",
        statusText = "On the Way",
        etaText = etaBanner,
        events = listOf(
            DomRawEvent("2026-07-22T13:17:00.000Z", "Shipping Label Created", "ROCHESTER, NY 14609"),
            DomRawEvent("2026-07-23T23:11:00.000Z", "Accepted at USPS Facility", "ROCHESTER, NY 14609"),
            DomRawEvent("2026-07-24T00:26:00.000Z", "Arrived at USPS Facility", "NORTHWEST ROCHESTER NY DISTRIBUTION CENTER"),
            DomRawEvent("2026-07-24T09:44:00.000Z", "Departed USPS Facility", "NORTHWEST ROCHESTER NY DISTRIBUTION CENTER"),
        ),
    )

    @Test fun on_the_way_classifies_in_transit() {
        assertEquals(TrackingStatus.IN_TRANSIT, USPS_VOCABULARY.classify("On the Way"))
        assertEquals(TrackingStatus.IN_TRANSIT, USPS_VOCABULARY.classify("Your package is on its way to a USPS facility"))
    }

    @Test fun existing_vocabulary_is_unchanged() {
        assertEquals(TrackingStatus.LABEL_CREATED, USPS_VOCABULARY.classify("Shipping Label Created, USPS Awaiting Item"))
        assertEquals(TrackingStatus.SHIPPED, USPS_VOCABULARY.classify("Accepted at USPS Origin Facility"))
        assertEquals(TrackingStatus.IN_TRANSIT, USPS_VOCABULARY.classify("Moving Through Network"))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, USPS_VOCABULARY.classify("Out for Delivery"))
        assertEquals(TrackingStatus.DELIVERED, USPS_VOCABULARY.classify("Delivered, In/At Mailbox"))
        assertEquals(TrackingStatus.EXCEPTION, USPS_VOCABULARY.classify("Alert"))
        assertNull(USPS_VOCABULARY.classify("Some New Wording"))
        assertNull(USPS_VOCABULARY.classify(null))
    }

    @Test fun eta_date_survives_split_spans_and_tooltip_copy() {
        assertEquals(LocalDate(2026, 7, 28), USPS_PAGE.etaDate(etaBanner, null))
    }

    @Test fun eta_date_also_parses_month_first_wording() {
        // The "Expected Delivery on" variants render "Monday, July 28, 2026".
        assertEquals(LocalDate(2026, 7, 28), USPS_PAGE.etaDate("Expected Delivery on Monday, July 28, 2026", null))
    }

    @Test fun eta_date_is_null_when_banner_is_absent_or_junk() {
        assertNull(USPS_PAGE.etaDate(null, null))
        assertNull(USPS_PAGE.etaDate("Get More Out of USPS Tracking:", null))
    }

    @Test fun eta_window_cutoff_is_extracted_verbatim() {
        assertEquals("by 9:00pm", findEtaWindowText(etaBanner))
        assertNull(findEtaWindowText("Expected Delivery by: Tuesday 28 July 2026"))
        assertNull(findEtaWindowText(null))
    }

    // Verbatim from the 2026-07-27 live scrape of the same tracking number once it went
    // "Out for Delivery": the banner phrases the window as "between X and Y" rather than
    // "X - Y" or "by X", which the range regex didn't recognize, so the scrape produced no
    // window and the repository merge kept showing the stale "by 9:00pm" from the 07-24 scrape.
    @Test fun eta_window_between_and_range_is_extracted() {
        val outForDeliveryBanner = "Expected Delivery on: Monday 27 July 2026 Expected Delivery Date " +
            "Expected delivery on the date provided is the latest information on when the Postal Service " +
            "expects to deliver your package. between 12:00pm and 2:00pm Expected Delivery Time " +
            "The timeframe provided is the estimated timeframe when the carrier will attempt to deliver your package."
        // The shared extractor quotes the "between" prefix too (FedEx precedent); parseEtaWindow
        // reads both forms identically and etaWindowText is never persisted.
        assertEquals("between 12:00pm and 2:00pm", findEtaWindowText(outForDeliveryBanner))
    }

    @Test fun live_scrape_regression_full_raw_parse() {
        // The whole 2026-07-24 bug in one assertion set: IN_TRANSIT (not UNKNOWN — which the
        // repository merge would have discarded, leaving the stale LABEL_CREATED on the card),
        // ETA July 28, window "by 9:00pm", location from the newest event.
        val outcome = page(liveRaw)
        assertIs<PageOutcome.Tracking>(outcome)
        val s = outcome.snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
        assertEquals(LocalDate(2026, 7, 28), s.etaDate)
        assertEquals(LocalTime(21, 0), s.etaWindowEnd)
        assertEquals("NORTHWEST ROCHESTER NY DISTRIBUTION CENTER", s.latestLocation)
        assertEquals(
            listOf(TrackingStatus.LABEL_CREATED, TrackingStatus.SHIPPED, TrackingStatus.IN_TRANSIT, TrackingStatus.IN_TRANSIT),
            s.events.map { it.status },
        )
        assertEquals("Departed USPS Facility", s.events.last().description)
    }

    @Test fun unreadable_status_falls_back_to_newest_classifiable_event() {
        assertEquals(TrackingStatus.IN_TRANSIT, page(liveRaw.copy(statusText = "Latest Update")).snapshotOrNull()?.status)
    }

    @Test fun unreadable_status_with_no_events_is_unknown_not_a_crash() {
        assertEquals(TrackingStatus.UNKNOWN, page(DomRaw(kind = "tracker", statusText = "Latest Update")).snapshotOrNull()?.status)
    }

    @Test fun not_found_wording_routes_not_found_from_page_text() {
        // The wordings the JS blob used to decide on (moved to Kotlin 2026-08-20).
        assertIs<PageOutcome.NotFound>(page(DomRaw(kind = "tracker", pageText = "Status Not Available")))
        assertIs<PageOutcome.NotFound>(
            page(DomRaw(kind = "tracker", pageText = "We could not locate the tracking information for your request")),
        )
        assertIs<PageOutcome.Tracking>(page(liveRaw.copy(pageText = "USPS Tracking results")))
    }

    @Test fun nothing_readable_is_empty() {
        assertIs<PageOutcome.Empty>(page(DomRaw(kind = "tracker")))
    }

    @Test fun unknown_raw_kind_is_refused() {
        assertNull(page(DomRaw(kind = "cards")))
    }
}

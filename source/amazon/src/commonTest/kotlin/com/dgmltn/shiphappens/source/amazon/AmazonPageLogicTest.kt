package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomCard
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.DomRawEvent
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Strings here are verbatim device captures (ScrapeTracer, 2026-07-19) — the point of moving this
 * logic out of the JS blob is that real page text can now be asserted against without a device.
 */
class AmazonPageLogicTest {

    // Order 112-2518213-0037838: a delivered shipment plus a completed-replacement card. The app
    // showed "delivery exception" because a bare 'return' match promoted the RMA card to EXCEPTION,
    // and "first card that isn't DELIVERED" then selected it over the real delivery.
    private val deliveredCard = DomCard(
        head = "Delivered June 25 Your package was left near the front door or porch.",
        href = "https://www.amazon.com/progress-tracker/package/ref=x",
    )
    private val replacementCard = DomCard(
        head = "Replacement complete We’ve received your return. Your replacement is complete.",
        href = null,
    )

    @Test fun replacement_card_is_not_a_shipping_status() {
        assertNull(classifyAmazonStatus(replacementCard.head))
    }

    @Test fun delivered_card_classifies_delivered() {
        assertEquals(TrackingStatus.DELIVERED, classifyAmazonStatus(deliveredCard.head))
    }

    @Test fun returns_copy_on_a_delivered_order_stays_delivered() {
        // Every delivered order renders return-window copy; it must not outrank the delivery.
        assertEquals(
            TrackingStatus.DELIVERED,
            classifyAmazonStatus("Delivered June 25 Return or replace items: Eligible through July 25"),
        )
    }

    @Test fun return_phrasing_still_detects_a_real_delivery_exception() {
        assertEquals(TrackingStatus.EXCEPTION, classifyAmazonStatus("Package is being returned to sender"))
        assertEquals(TrackingStatus.EXCEPTION, classifyAmazonStatus("Undeliverable — returned to sender"))
    }

    @Test fun picks_the_shipment_not_the_replacement_card() {
        assertEquals(deliveredCard, pickShipmentCard(listOf(deliveredCard, replacementCard)))
    }

    @Test fun delivered_order_with_an_rma_card_hops_to_the_tracker() {
        // The regression, end to end: goto the delivered shipment's tracker, carrying DELIVERED.
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(deliveredCard, replacementCard)))
        assertEquals("goto", result.page)
        assertEquals(deliveredCard.href, result.url)
        assertEquals(TrackingStatus.DELIVERED.name, result.tracking?.status)
    }

    @Test fun still_prefers_an_in_flight_shipment_over_a_delivered_one() {
        // "Arriving today" is a delivery-date promise, not a transit state: the card is still picked
        // over the delivered one and its ETA is carried, but status stays UNKNOWN (the tracker hop
        // supplies the real state) — it must NOT be reported as IN_TRANSIT.
        val arriving = DomCard(head = "Arriving today", href = "https://www.amazon.com/progress-tracker/p2")
        val result = resolveAmazonCards(
            DomRaw(kind = "cards", cards = listOf(deliveredCard, arriving), todayIso = "2026-07-19"),
        )
        assertEquals(arriving.href, result.url)
        assertEquals(TrackingStatus.UNKNOWN.name, result.tracking?.status)
        assertEquals("2026-07-19", result.tracking?.etaDate)
    }

    @Test fun arriving_is_an_eta_not_a_transit_status() {
        assertNull(classifyAmazonStatus("Arriving tomorrow"))
        assertNull(classifyAmazonStatus("Arriving Tue, Jul 22"))
    }

    @Test fun an_ordered_status_is_not_yet_shipped() {
        // The tracker page reports "Ordered" for a placed-but-unshipped order; that is label-created,
        // never in-transit.
        assertEquals(TrackingStatus.LABEL_CREATED, classifyAmazonStatus("Ordered"))
    }

    @Test fun genuine_transit_phrasing_still_classifies_in_transit() {
        assertEquals(TrackingStatus.IN_TRANSIT, classifyAmazonStatus("Package is on the way"))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyAmazonStatus("In transit to next facility"))
    }

    @Test fun arriving_only_card_keeps_its_eta_with_unknown_status() {
        // No tracker link and only a delivery-date headline: report the ETA with an honest UNKNOWN
        // status rather than inventing IN_TRANSIT or dropping the countdown.
        val arriving = DomCard(head = "Arriving tomorrow", href = null)
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(arriving), todayIso = "2026-07-20"))
        assertEquals("ok", result.page)
        assertEquals(TrackingStatus.UNKNOWN.name, result.tracking?.status)
        assertEquals("2026-07-21", result.tracking?.etaDate)
    }

    @Test fun card_without_a_tracker_link_reports_its_coarse_status() {
        val old = DomCard(head = "Delivered June 25", href = null)
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(old)))
        assertEquals("ok", result.page)
        assertEquals(TrackingStatus.DELIVERED.name, result.tracking?.status)
    }

    @Test fun unrecognizable_cards_fall_back_rather_than_vanish() {
        // No card qualifies as a shipment: still pick one so the caller reports empty, not silence.
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(replacementCard)))
        assertEquals("empty", result.page)
    }

    @Test fun no_cards_is_empty() {
        assertEquals("empty", resolveAmazonCards(DomRaw(kind = "cards")).page)
    }

    @Test fun tracker_classifies_status_line_and_events() {
        val result = resolveAmazonTracker(
            DomRaw(
                kind = "tracker",
                statusText = "Delivered June 25",
                etaDate = "2026-06-25",
                events = listOf(
                    DomRawEvent("2026-06-24T09:00:00Z", "Shipped", "Phoenix, AZ"),
                    DomRawEvent("2026-06-25T14:00:00Z", "Delivered, left near front door", "Tempe, AZ"),
                ),
            ),
        )
        assertEquals(TrackingStatus.DELIVERED.name, result.tracking?.status)
        assertEquals("Tempe, AZ", result.tracking?.location)
        assertEquals(TrackingStatus.SHIPPED.name, result.tracking?.events?.first()?.status)
        assertEquals(TrackingStatus.DELIVERED.name, result.tracking?.events?.last()?.status)
    }

    @Test fun tracker_falls_back_to_newest_event_when_status_line_is_unreadable() {
        val result = resolveAmazonTracker(
            DomRaw(
                kind = "tracker",
                statusText = "Your order",
                events = listOf(DomRawEvent("2026-06-24T09:00:00Z", "Out for delivery", null)),
            ),
        )
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY.name, result.tracking?.status)
    }

    @Test fun tracker_with_nothing_readable_is_empty() {
        assertEquals("empty", resolveAmazonTracker(DomRaw(kind = "tracker")).page)
    }

    @Test fun unknown_raw_kind_is_refused() {
        assertNull(parseAmazonRaw(DomRaw(kind = "somethingElse")))
    }

    // --- Revised-promise phrasing (device capture, order 112-9490776-9361838, 2026-08-18) ---
    //
    // A delayed shipment drops the "Arriving ..." wording entirely and reads "Now expected
    // tomorrow by 8 AM". The JS only ever looked for "arriving", so the page's own stated day was
    // discarded and the parcel showed no arrival date at all — while still carrying the "by 8 AM"
    // window, which the detail screen hides when there is no date to hang it on.

    private val today = LocalDate(2026, 8, 18)
    private val tomorrow = LocalDate(2026, 8, 19)

    @Test fun now_expected_tomorrow_resolves_to_tomorrows_date() {
        assertEquals(tomorrow, amazonEtaFromStatus("Now expected tomorrow by 8 AM", today))
    }

    @Test fun now_expected_marks_the_shipment_delayed() {
        assertEquals(TrackingStatus.EXCEPTION, classifyAmazonStatus("Now expected tomorrow by 8 AM"))
    }

    @Test fun arriving_phrasing_still_resolves() {
        assertEquals(today, amazonEtaFromStatus("Arriving today", today))
        assertEquals(tomorrow, amazonEtaFromStatus("Arriving tomorrow", today))
        assertEquals(tomorrow, amazonEtaFromStatus("Arriving overnight 7 AM – 11 AM", today))
    }

    @Test fun explicit_day_takes_its_year_from_the_page_date() {
        assertEquals(LocalDate(2026, 8, 22), amazonEtaFromStatus("Arriving Sat, Aug 22", today))
        assertEquals(LocalDate(2026, 9, 3), amazonEtaFromStatus("Now expected September 3", today))
    }

    @Test fun a_december_promise_read_in_january_belongs_to_last_year() {
        // Amazon omits the year; taking January's year would put the date 11 months in the future.
        assertEquals(LocalDate(2026, 12, 28), amazonEtaFromStatus("Arriving December 28", LocalDate(2027, 1, 5)))
    }

    @Test fun text_with_no_delivery_phrase_has_no_eta() {
        // The gate matters: a delivered headline names a date that is history, not a promise.
        assertNull(amazonEtaFromStatus("Delivered June 25 Your package was left near the front door", today))
        assertNull(amazonEtaFromStatus("Package delayed in transit", today))
        assertNull(amazonEtaFromStatus("by 8 AM", today))
    }

    @Test fun a_promise_element_is_parsed_without_needing_a_phrase() {
        // The tracker's own promise element is nothing but the day, so it needs no lead-in word.
        assertEquals(tomorrow, parseAmazonDay("Tomorrow", today))
        assertEquals(LocalDate(2026, 8, 22), parseAmazonDay("Saturday, August 22", today))
    }

    @Test fun a_page_that_never_reported_its_date_yields_no_eta() {
        // todayIso absent (older payload, or a page read before the field existed): relative words
        // are unresolvable, and inventing a date would be worse than having none.
        assertNull(amazonEtaFromStatus("Now expected tomorrow by 8 AM", today = null))
        assertNull(parseAmazonDay("Saturday, August 22", today = null))
    }

    @Test fun delayed_tracker_page_reports_tomorrow_the_window_and_the_delay() {
        // Verbatim from the 2026-08-18 capture.
        val result = resolveAmazonTracker(
            DomRaw(
                kind = "tracker",
                statusText = "Now expected tomorrow by 8 AM",
                etaWindowText = "by 8 AM",
                todayIso = "2026-08-18",
                events = listOf(
                    DomRawEvent("2026-08-18T07:00:00.000Z", "Delivery appointment scheduled", "US"),
                    DomRawEvent("2026-08-18T07:00:00.000Z", "Package delayed in transit", ""),
                    DomRawEvent("2026-08-18T07:00:00.000Z", "Package delayed in transit", ""),
                ),
            ),
        )
        assertEquals("2026-08-19", result.tracking?.etaDate)
        assertEquals("by 8 AM", result.tracking?.etaWindowText)
        assertEquals(TrackingStatus.EXCEPTION.name, result.tracking?.status)
    }

    // --- Delay as a modifier (2026-08-28) ---
    //
    // Amazon has no reason-sentence field (no UPS `simplifiedText` equivalent), so the wording
    // that trips the delay match IS the note.

    @Test fun delay_wording_no_longer_hides_the_stage_the_shipment_is_at() {
        // The event row Amazon logs for a late shipment names its own stage; collapsing it to
        // EXCEPTION (stepIndex -1) stopped the timeline advancing past it.
        assertEquals(TrackingStatus.IN_TRANSIT, classifyAmazonStatus("Package delayed in transit"))
    }

    @Test fun delay_wording_that_names_no_stage_still_classifies_exception() {
        // Unchanged behavior for the headline wordings: nothing better to be.
        assertEquals(TrackingStatus.EXCEPTION, classifyAmazonStatus("Now expected tomorrow by 8 AM"))
        assertEquals(TrackingStatus.EXCEPTION, classifyAmazonStatus("Running late"))
        assertEquals(TrackingStatus.EXCEPTION, classifyAmazonStatus("Delayed"))
    }

    @Test fun a_real_exception_still_beats_delay_wording() {
        assertEquals(TrackingStatus.EXCEPTION, classifyAmazonStatus("Delivery attempted, running late"))
        assertEquals(TrackingStatus.EXCEPTION, classifyAmazonStatus("Undeliverable — delayed"))
    }

    @Test fun delivered_and_out_for_delivery_still_outrank_delay_wording() {
        assertEquals(TrackingStatus.DELIVERED, classifyAmazonStatus("Delivered, was running late"))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, classifyAmazonStatus("Out for delivery, delayed"))
    }

    @Test fun delay_is_detected_independently_of_the_stage() {
        assertTrue(isAmazonDelayed("Now expected tomorrow by 8 AM"))
        assertTrue(isAmazonDelayed("Package delayed in transit"))
        assertTrue(isAmazonDelayed("Running late"))
        assertFalse(isAmazonDelayed("Arriving tomorrow"))
        assertFalse(isAmazonDelayed("Out for delivery"))
        assertFalse(isAmazonDelayed(""))
        assertFalse(isAmazonDelayed(null))
    }

    @Test fun a_delayed_tracker_page_carries_the_wording_as_its_note() {
        val result = resolveAmazonTracker(
            DomRaw(kind = "tracker", statusText = "Now expected tomorrow by 8 AM", todayIso = "2026-08-18"),
        )
        assertEquals("Now expected tomorrow by 8 AM", result.tracking?.delayNote)
    }

    @Test fun a_tracker_whose_only_delay_signal_is_an_event_still_reports_it() {
        // The 2026-08-18 capture's warning: a tracker that hadn't logged the revised promise in
        // its status line still had "Package delayed in transit" in the rows.
        val result = resolveAmazonTracker(
            DomRaw(
                kind = "tracker",
                statusText = "Arriving tomorrow",
                todayIso = "2026-08-18",
                events = listOf(
                    DomRawEvent("2026-08-18T05:00:00.000Z", "Package left the facility", "US"),
                    DomRawEvent("2026-08-18T07:00:00.000Z", "Package delayed in transit", ""),
                ),
            ),
        )
        assertEquals("Package delayed in transit", result.tracking?.delayNote)
    }

    @Test fun an_undelayed_tracker_page_has_no_note() {
        val result = resolveAmazonTracker(
            DomRaw(kind = "tracker", statusText = "Arriving tomorrow", todayIso = "2026-08-18"),
        )
        assertNull(result.tracking?.delayNote)
    }

    @Test fun a_delayed_order_card_carries_its_note_into_the_hop() {
        val delayed = DomCard(
            head = "Now expected tomorrow by 8 AM",
            href = "https://www.amazon.com/gp/your-account/ship-track?itemId=x&orderId=y",
        )
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(delayed), todayIso = "2026-08-18"))
        assertEquals("Now expected tomorrow by 8 AM", result.tracking?.delayNote)
    }

    @Test fun an_undelayed_order_card_has_no_note() {
        val card = DomCard(head = "Arriving tomorrow", href = "https://www.amazon.com/gp/your-account/ship-track?x")
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(card), todayIso = "2026-08-18"))
        assertNull(result.tracking?.delayNote)
    }

    @Test fun tracker_prefers_the_promise_element_over_the_status_line() {
        val result = resolveAmazonTracker(
            DomRaw(
                kind = "tracker",
                statusText = "Now expected tomorrow by 8 AM",
                etaText = "Saturday, August 22",
                todayIso = "2026-08-18",
            ),
        )
        assertEquals("2026-08-22", result.tracking?.etaDate)
    }

    @Test fun delayed_order_card_carries_the_revised_eta_into_the_hop() {
        // The order page hops to the tracker, but the coarse fallback must already hold the date:
        // an impoverished tracker page would otherwise blank a countdown the order page knew.
        val delayed = DomCard(
            head = "Now expected tomorrow by 8 AM",
            href = "https://www.amazon.com/gp/your-account/ship-track?itemId=x&orderId=y",
        )
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(delayed), todayIso = "2026-08-18"))
        assertEquals("goto", result.page)
        assertEquals("2026-08-19", result.tracking?.etaDate)
        assertEquals(TrackingStatus.EXCEPTION.name, result.tracking?.status)
    }

    @Test fun order_page_not_found_wording_routes_not_found_from_page_text() {
        // The wordings the JS blob used to decide on (moved to Kotlin 2026-08-20); scoped to the
        // cards page, matching the blob's original branch placement.
        assertEquals(
            "notFound",
            parseAmazonRaw(DomRaw(kind = "cards", pageText = "We're having a problem finding this order"))?.page,
        )
        assertEquals(
            "notFound",
            parseAmazonRaw(DomRaw(kind = "cards", pageText = "We can't find that order"))?.page,
        )
    }
}

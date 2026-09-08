package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomCard
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.DomRawEvent
import com.dgmltn.shiphappens.source.webview.PageOutcome
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.snapshotOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        assertNull(AMAZON_VOCABULARY.classify(replacementCard.head))
    }

    @Test fun delivered_card_classifies_delivered() {
        assertEquals(TrackingStatus.DELIVERED, AMAZON_VOCABULARY.classify(deliveredCard.head))
    }

    @Test fun returns_copy_on_a_delivered_order_stays_delivered() {
        // Every delivered order renders return-window copy; it must not outrank the delivery.
        assertEquals(
            TrackingStatus.DELIVERED,
            AMAZON_VOCABULARY.classify("Delivered June 25 Return or replace items: Eligible through July 25"),
        )
    }

    @Test fun return_phrasing_still_detects_a_real_delivery_exception() {
        assertEquals(TrackingStatus.EXCEPTION, AMAZON_VOCABULARY.classify("Package is being returned to sender"))
        assertEquals(TrackingStatus.EXCEPTION, AMAZON_VOCABULARY.classify("Undeliverable — returned to sender"))
    }

    @Test fun picks_the_shipment_not_the_replacement_card() {
        assertEquals(deliveredCard, pickShipmentCard(listOf(deliveredCard, replacementCard)))
    }

    @Test fun delivered_order_with_an_rma_card_hops_to_the_tracker() {
        // The regression, end to end: goto the delivered shipment's tracker, carrying DELIVERED.
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(deliveredCard, replacementCard)))
        val goto = assertIs<PageOutcome.Goto>(result)
        assertEquals(deliveredCard.href, goto.url)
        assertEquals(TrackingStatus.DELIVERED, goto.coarse?.status)
    }

    @Test fun still_prefers_an_in_flight_shipment_over_a_delivered_one() {
        // "Arriving today" is a delivery-date promise, not a transit state: the card is still picked
        // over the delivered one and its ETA is carried, but status stays UNKNOWN (the tracker hop
        // supplies the real state) — it must NOT be reported as IN_TRANSIT.
        val arriving = DomCard(head = "Arriving today", href = "https://www.amazon.com/progress-tracker/p2")
        val result = resolveAmazonCards(
            DomRaw(kind = "cards", cards = listOf(deliveredCard, arriving), todayIso = "2026-07-19"),
        )
        val goto = assertIs<PageOutcome.Goto>(result)
        assertEquals(arriving.href, goto.url)
        assertEquals(TrackingStatus.UNKNOWN, goto.coarse?.status)
        assertEquals(LocalDate(2026, 7, 19), goto.coarse?.etaDate)
    }

    @Test fun arriving_is_an_eta_not_a_transit_status() {
        assertNull(AMAZON_VOCABULARY.classify("Arriving tomorrow"))
        assertNull(AMAZON_VOCABULARY.classify("Arriving Tue, Jul 22"))
    }

    @Test fun an_ordered_status_is_not_yet_shipped() {
        // The tracker page reports "Ordered" for a placed-but-unshipped order; that is label-created,
        // never in-transit.
        assertEquals(TrackingStatus.LABEL_CREATED, AMAZON_VOCABULARY.classify("Ordered"))
    }

    @Test fun a_pre_shipment_tracker_headline_is_not_mistaken_for_a_progress_rail() {
        assertEquals(TrackingStatus.LABEL_CREATED,
            resolveTrackerPage(DomRaw(kind = "tracker", statusText = "Not yet shipped"), AMAZON_PAGE, TimeZone.UTC).snapshotOrNull()?.status)
    }

    @Test fun genuine_transit_phrasing_still_classifies_in_transit() {
        assertEquals(TrackingStatus.IN_TRANSIT, AMAZON_VOCABULARY.classify("Package is on the way"))
        assertEquals(TrackingStatus.IN_TRANSIT, AMAZON_VOCABULARY.classify("In transit to next facility"))
    }

    @Test fun arriving_only_card_keeps_its_eta_with_unknown_status() {
        // No tracker link and only a delivery-date headline: report the ETA with an honest UNKNOWN
        // status rather than inventing IN_TRANSIT or dropping the countdown.
        val arriving = DomCard(head = "Arriving tomorrow", href = null)
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(arriving), todayIso = "2026-07-20"))
        val tracking = assertIs<PageOutcome.Tracking>(result)
        assertEquals(TrackingStatus.UNKNOWN, tracking.snapshot.status)
        assertEquals(LocalDate(2026, 7, 21), tracking.snapshot.etaDate)
    }

    @Test fun card_without_a_tracker_link_reports_its_coarse_status() {
        val old = DomCard(head = "Delivered June 25", href = null)
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(old)))
        val tracking = assertIs<PageOutcome.Tracking>(result)
        assertEquals(TrackingStatus.DELIVERED, tracking.snapshot.status)
    }

    @Test fun unrecognizable_cards_fall_back_rather_than_vanish() {
        // No card qualifies as a shipment: still pick one so the caller reports empty, not silence.
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(replacementCard)))
        assertIs<PageOutcome.Empty>(result)
    }

    @Test fun no_cards_is_empty() {
        assertIs<PageOutcome.Empty>(resolveAmazonCards(DomRaw(kind = "cards")))
    }

    @Test fun tracker_classifies_status_line_and_events() {
        val result = resolveTrackerPage(
            DomRaw(
                kind = "tracker",
                statusText = "Delivered June 25",
                etaDate = "2026-06-25",
                events = listOf(
                    DomRawEvent("2026-06-24T09:00:00Z", "Shipped", "Phoenix, AZ"),
                    DomRawEvent("2026-06-25T14:00:00Z", "Delivered, left near front door", "Tempe, AZ"),
                ),
            ),
            AMAZON_PAGE,
            TimeZone.UTC,
        )
        val snapshot = result.snapshotOrNull()
        assertEquals(TrackingStatus.DELIVERED, snapshot?.status)
        assertEquals("Tempe, AZ", snapshot?.latestLocation)
        assertEquals(TrackingStatus.SHIPPED, snapshot?.events?.first()?.status)
        assertEquals(TrackingStatus.DELIVERED, snapshot?.events?.last()?.status)
    }

    @Test fun tracker_falls_back_to_newest_event_when_status_line_is_unreadable() {
        val result = resolveTrackerPage(
            DomRaw(
                kind = "tracker",
                statusText = "Your order",
                events = listOf(DomRawEvent("2026-06-24T09:00:00Z", "Out for delivery", null)),
            ),
            AMAZON_PAGE,
            TimeZone.UTC,
        )
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, result.snapshotOrNull()?.status)
    }

    @Test fun tracker_with_nothing_readable_is_empty() {
        assertIs<PageOutcome.Empty>(resolveTrackerPage(DomRaw(kind = "tracker"), AMAZON_PAGE, TimeZone.UTC))
    }

    @Test fun a_tracker_page_with_only_a_promise_still_reports_its_eta() {
        val out = resolveTrackerPage(DomRaw(kind = "tracker", etaText = "Arriving tomorrow", todayIso = "2026-08-18"), AMAZON_PAGE, TimeZone.UTC)
        assertEquals(TrackingStatus.UNKNOWN, out.snapshotOrNull()?.status)
        assertEquals(LocalDate(2026, 8, 19), out.snapshotOrNull()?.etaDate)
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
        // The revised promise reports the delay, but asserts no stage: Amazon shows a promise
        // from the moment an order is placed, so a slipped one is equally valid on an order that
        // has not shipped yet (2026-08-28).
        assertTrue(AMAZON_VOCABULARY.isDelayed("Now expected tomorrow by 8 AM"))
        assertNull(AMAZON_VOCABULARY.classify("Now expected tomorrow by 8 AM"))
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
        val result = resolveTrackerPage(
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
            AMAZON_PAGE,
            TimeZone.UTC,
        )
        val snapshot = result.snapshotOrNull()
        assertEquals(LocalDate(2026, 8, 19), snapshot?.etaDate)
        assertEquals(LocalTime(8, 0), snapshot?.etaWindowEnd)
        // The status line names no stage, so the stage falls to the newest event that does —
        // "Package delayed in transit" — rather than the old blanket EXCEPTION (2026-08-28).
        assertEquals(TrackingStatus.IN_TRANSIT, snapshot?.status)
        assertEquals("Now expected tomorrow by 8 AM", snapshot?.delayNote)
    }

    // --- Delay as a modifier (2026-08-28) ---
    //
    // Amazon has no reason-sentence field (no UPS `simplifiedText` equivalent), so the wording
    // that trips the delay match IS the note.

    @Test fun delay_wording_no_longer_hides_the_stage_the_shipment_is_at() {
        // The event row Amazon logs for a late shipment names its own stage; collapsing it to
        // EXCEPTION (stepIndex -1) stopped the timeline advancing past it.
        assertEquals(TrackingStatus.IN_TRANSIT, AMAZON_VOCABULARY.classify("Package delayed in transit"))
    }

    @Test fun delay_wording_that_names_no_stage_asserts_no_stage() {
        // Not EXCEPTION and not IN_TRANSIT — a guess either way. Null leaves the stage to the
        // event rows or the stored status, which is what makes "not yet shipped, but late" a
        // representable state.
        assertNull(AMAZON_VOCABULARY.classify("Now expected tomorrow by 8 AM"))
        assertNull(AMAZON_VOCABULARY.classify("Running late"))
        assertNull(AMAZON_VOCABULARY.classify("Delayed"))
    }

    @Test fun a_real_exception_still_beats_delay_wording() {
        assertEquals(TrackingStatus.EXCEPTION, AMAZON_VOCABULARY.classify("Delivery attempted, running late"))
        assertEquals(TrackingStatus.EXCEPTION, AMAZON_VOCABULARY.classify("Undeliverable — delayed"))
    }

    @Test fun delivered_and_out_for_delivery_still_outrank_delay_wording() {
        assertEquals(TrackingStatus.DELIVERED, AMAZON_VOCABULARY.classify("Delivered, was running late"))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, AMAZON_VOCABULARY.classify("Out for delivery, delayed"))
    }

    @Test fun delay_is_detected_independently_of_the_stage() {
        assertTrue(AMAZON_VOCABULARY.isDelayed("Now expected tomorrow by 8 AM"))
        assertTrue(AMAZON_VOCABULARY.isDelayed("Package delayed in transit"))
        assertTrue(AMAZON_VOCABULARY.isDelayed("Running late"))
        assertFalse(AMAZON_VOCABULARY.isDelayed("Arriving tomorrow"))
        assertFalse(AMAZON_VOCABULARY.isDelayed("Out for delivery"))
        assertFalse(AMAZON_VOCABULARY.isDelayed(""))
        assertFalse(AMAZON_VOCABULARY.isDelayed(null))
    }

    @Test fun a_delayed_tracker_page_carries_the_wording_as_its_note() {
        val result = resolveTrackerPage(
            DomRaw(kind = "tracker", statusText = "Now expected tomorrow by 8 AM", todayIso = "2026-08-18"),
            AMAZON_PAGE,
            TimeZone.UTC,
        )
        assertEquals("Now expected tomorrow by 8 AM", result.snapshotOrNull()?.delayNote)
    }

    @Test fun a_tracker_whose_only_delay_signal_is_an_event_still_reports_it() {
        // The 2026-08-18 capture's warning: a tracker that hadn't logged the revised promise in
        // its status line still had "Package delayed in transit" in the rows.
        val result = resolveTrackerPage(
            DomRaw(
                kind = "tracker",
                statusText = "Arriving tomorrow",
                todayIso = "2026-08-18",
                events = listOf(
                    DomRawEvent("2026-08-18T05:00:00.000Z", "Package left the facility", "US"),
                    DomRawEvent("2026-08-18T07:00:00.000Z", "Package delayed in transit", ""),
                ),
            ),
            AMAZON_PAGE,
            TimeZone.UTC,
        )
        assertEquals("Package delayed in transit", result.snapshotOrNull()?.delayNote)
    }

    @Test fun an_undelayed_tracker_page_has_no_note() {
        val result = resolveTrackerPage(
            DomRaw(kind = "tracker", statusText = "Arriving tomorrow", todayIso = "2026-08-18"),
            AMAZON_PAGE,
            TimeZone.UTC,
        )
        assertNull(result.snapshotOrNull()?.delayNote)
    }

    @Test fun a_delayed_order_card_carries_its_note_into_the_hop() {
        val delayed = DomCard(
            head = "Now expected tomorrow by 8 AM",
            href = "https://www.amazon.com/gp/your-account/ship-track?itemId=x&orderId=y",
        )
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(delayed), todayIso = "2026-08-18"))
        val goto = assertIs<PageOutcome.Goto>(result)
        assertEquals("Now expected tomorrow by 8 AM", goto.coarse?.delayNote)
    }

    @Test fun an_undelayed_order_card_has_no_note() {
        val card = DomCard(head = "Arriving tomorrow", href = "https://www.amazon.com/gp/your-account/ship-track?x")
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(card), todayIso = "2026-08-18"))
        val goto = assertIs<PageOutcome.Goto>(result)
        assertNull(goto.coarse?.delayNote)
    }

    @Test fun tracker_prefers_the_promise_element_over_the_status_line() {
        val result = resolveTrackerPage(
            DomRaw(
                kind = "tracker",
                statusText = "Now expected tomorrow by 8 AM",
                etaText = "Saturday, August 22",
                todayIso = "2026-08-18",
            ),
            AMAZON_PAGE,
            TimeZone.UTC,
        )
        assertEquals(LocalDate(2026, 8, 22), result.snapshotOrNull()?.etaDate)
    }

    @Test fun delayed_order_card_carries_the_revised_eta_into_the_hop() {
        // The order page hops to the tracker, but the coarse fallback must already hold the date:
        // an impoverished tracker page would otherwise blank a countdown the order page knew.
        val delayed = DomCard(
            head = "Now expected tomorrow by 8 AM",
            href = "https://www.amazon.com/gp/your-account/ship-track?itemId=x&orderId=y",
        )
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(delayed), todayIso = "2026-08-18"))
        val goto = assertIs<PageOutcome.Goto>(result)
        assertEquals(LocalDate(2026, 8, 19), goto.coarse?.etaDate)
        // The card names no stage; the coarse fallback carries the ETA and the delay, and leaves
        // the stage UNKNOWN for the tracker hop (or the stored status) to supply.
        assertEquals(TrackingStatus.UNKNOWN, goto.coarse?.status)
        assertEquals("Now expected tomorrow by 8 AM", goto.coarse?.delayNote)
    }

    @Test fun order_page_not_found_wording_routes_not_found_from_page_text() {
        // The wordings the JS blob used to decide on (moved to Kotlin 2026-08-20); scoped to the
        // cards page, matching the blob's original branch placement.
        assertIs<PageOutcome.NotFound>(
            parseAmazonRaw(DomRaw(kind = "cards", pageText = "We're having a problem finding this order")),
        )
        assertIs<PageOutcome.NotFound>(
            parseAmazonRaw(DomRaw(kind = "cards", pageText = "We can't find that order")),
        )
    }
}

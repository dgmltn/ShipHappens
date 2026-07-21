package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomCard
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.DomRawEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
        val arriving = DomCard(head = "Arriving today", href = "https://www.amazon.com/progress-tracker/p2", etaDate = "2026-07-19")
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(deliveredCard, arriving)))
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
        val arriving = DomCard(head = "Arriving tomorrow", href = null, etaDate = "2026-07-21")
        val result = resolveAmazonCards(DomRaw(kind = "cards", cards = listOf(arriving)))
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
}

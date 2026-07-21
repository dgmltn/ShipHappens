package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomCard
import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking

/**
 * Everything the Amazon scrape *decides*, kept out of AMAZON_EXTRACTION_JS so it can be tested
 * against real captured page strings (see AmazonPageLogicTest). The JS only reads text now.
 */

// Ordered longest-intent-first: a delivered order still shows returns copy and a still-open return
// window, so "delivered" has to win before any return phrasing is considered.
private val EXCEPTION_PHRASES = listOf(
    "undeliverable",
    "running late",
    "delayed",
    "delivery attempted",
    "returned to sender",
    "return to sender",
    "being returned",
    "package was lost",
    "lost in transit",
)
private val LABEL_CREATED_PHRASES = listOf("not yet shipped", "not shipped", "ordered", "order placed", "preparing for shipment")
private val SHIPPED_PHRASES = listOf("shipped", "dispatched", "picked up")
// "Arriving <day>" is deliberately NOT here: Amazon shows it as the delivery-date promise the moment
// an order is placed, before anything ships, so it says nothing about the shipment's transit state —
// it only carries the ETA (parsed separately in AmazonWebSpec's etaFromArriving). Conflating the two
// made a not-yet-shipped "Arriving tomorrow" order report IN_TRANSIT. Real movement is signaled by
// the tracker page's events/status line, which classify below.
private val IN_TRANSIT_PHRASES = listOf("in transit", "on the way", "on its way", "at carrier")

/**
 * Maps an Amazon status headline to a status, or null when the text isn't a shipping status at all.
 *
 * Null is a real answer, not a failure: an order page also carries cards like "Replacement complete
 * — We've received your return", which describe an RMA rather than a shipment. The 2026-07-19 QA
 * bug was a bare `'return'` substring match promoting exactly that card to EXCEPTION, so return
 * phrasing is matched only as a delivery outcome ("returned to sender"), never as a bare word.
 */
internal fun classifyAmazonStatus(raw: String?): TrackingStatus? {
    val t = raw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (t.isEmpty()) return null
    fun any(phrases: List<String>) = phrases.any { it in t }
    return when {
        "out for delivery" in t -> TrackingStatus.OUT_FOR_DELIVERY
        "delivered" in t -> TrackingStatus.DELIVERED
        any(EXCEPTION_PHRASES) -> TrackingStatus.EXCEPTION
        any(LABEL_CREATED_PHRASES) -> TrackingStatus.LABEL_CREATED
        any(SHIPPED_PHRASES) -> TrackingStatus.SHIPPED
        any(IN_TRANSIT_PHRASES) -> TrackingStatus.IN_TRANSIT
        else -> null
    }
}

/**
 * Picks the card the parcel should track: the first shipment still in flight, else the last one
 * (design spec §Decisions).
 *
 * Only *shipments* are candidates. An order page mixes real shipment cards with RMA/replacement
 * cards, and the older "first card that isn't DELIVERED" rule handed the parcel to whichever
 * non-shipment card happened to come first — reporting an RMA card's state for a delivered order.
 * A card qualifies by having a tracker link or a recognizable shipping status; if none does, every
 * card stays eligible so the caller still produces its page:'empty' diagnostics rather than nothing.
 */
internal fun pickShipmentCard(cards: List<DomCard>): DomCard? {
    if (cards.isEmpty()) return null
    val shipments = cards.filter { it.href != null || classifyAmazonStatus(it.head) != null }
    val pool = shipments.ifEmpty { cards }
    return pool.firstOrNull { classifyAmazonStatus(it.head) != TrackingStatus.DELIVERED } ?: pool.last()
}

/** Order-details page: choose a shipment and either hop to its tracker or report its coarse state. */
internal fun resolveAmazonCards(raw: DomRaw): DomExtraction {
    val pick = pickShipmentCard(raw.cards) ?: return DomExtraction(page = "empty")
    // The headline's "Arriving <day>" carries the ETA but not a transit state (see IN_TRANSIT_PHRASES),
    // so a card can have a delivery date with no classifiable status. Keep the ETA regardless — a
    // shipment with no tracker link still yields a countdown — and leave status UNKNOWN until a real
    // signal (the tracker hop, or delivered/shipped/exception phrasing) supplies one.
    val status = classifyAmazonStatus(pick.head)
    val coarse = if (status != null || pick.etaDate != null) {
        ScrapedTracking(status = (status ?: TrackingStatus.UNKNOWN).name, etaDate = pick.etaDate)
    } else null
    return when {
        pick.href != null -> DomExtraction(page = "goto", url = pick.href, tracking = coarse)
        coarse != null -> DomExtraction(page = "ok", tracking = coarse)
        else -> DomExtraction(page = "empty")
    }
}

/** Tracker page: classify the status line and each event row, newest location wins. */
internal fun resolveAmazonTracker(raw: DomRaw): DomExtraction {
    val events = raw.events.map {
        ScrapedEvent(
            timestamp = it.timestamp,
            description = it.description,
            location = it.location,
            status = classifyAmazonStatus(it.description)?.name,
        )
    }
    if (raw.statusText.isNullOrBlank() && events.isEmpty()) return DomExtraction(page = "empty")
    // Events arrive oldest-first; the newest one that names a place is the current location.
    val status = classifyAmazonStatus(raw.statusText)
        ?: classifyAmazonStatus(events.lastOrNull()?.description)
        ?: TrackingStatus.UNKNOWN
    return DomExtraction(
        page = "ok",
        tracking = ScrapedTracking(
            status = status.name,
            etaDate = raw.etaDate,
            etaWindowText = raw.etaWindowText,
            location = events.lastOrNull { it.location != null }?.location,
            events = events,
        ),
    )
}

/** Dispatches a raw extraction by the page that produced it. */
internal fun parseAmazonRaw(raw: DomRaw): DomExtraction? = when (raw.kind) {
    "cards" -> resolveAmazonCards(raw)
    "tracker" -> resolveAmazonTracker(raw)
    else -> null
}

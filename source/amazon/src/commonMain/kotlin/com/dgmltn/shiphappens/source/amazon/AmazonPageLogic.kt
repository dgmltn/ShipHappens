package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomCard
import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import com.dgmltn.shiphappens.source.webview.parseDayWithoutYear
import com.dgmltn.shiphappens.source.webview.parseRelativeDay
import kotlinx.datetime.LocalDate

/**
 * Everything the Amazon scrape *decides*, kept out of AMAZON_EXTRACTION_JS so it can be tested
 * against real captured page strings (see AmazonPageLogicTest). The JS only reads text now.
 */

// Ordered longest-intent-first: a delivered order still shows returns copy and a still-open return
// window, so "delivered" has to win before any return phrasing is considered.
private val EXCEPTION_PHRASES = listOf(
    "undeliverable",
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
 * Delay wordings, including Amazon's revised-promise phrasing ("Now expected tomorrow by 8 AM"),
 * which it uses only when the original promise slipped. Captured 2026-08-18 on a shipment whose
 * only other delay signal was an event row — hence [delayNoteFor]'s event fallback.
 *
 * These are NOT status phrases at all: a delay is a modifier on the stage, never a stage itself
 * (2026-08-28, matching UPS). [classifyAmazonStatus] has no delay branch, so "Package delayed in
 * transit" reports the IN_TRANSIT its own wording names, while a stage-less "Now expected
 * tomorrow by 8 AM" answers null rather than guessing. That null matters here specifically:
 * Amazon shows a delivery promise from the moment an order is placed, so a slipped promise is a
 * perfectly valid state for an order that has not shipped yet, and calling it IN_TRANSIT would
 * repeat the bug the IN_TRANSIT_PHRASES note above describes.
 */
private val DELAY_PHRASES = listOf("delayed", "running late", "now expected")

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
        // No delay branch, deliberately — see DELAY_PHRASES.
        else -> null
    }
}


/**
 * Whether a wording reports a delay, asked independently of [classifyAmazonStatus] because the
 * two are orthogonal. Amazon has no reason-sentence field (no UPS `simplifiedText` equivalent),
 * so the matched wording itself is what surfaces as the note — see [delayNoteFor].
 */
internal fun isAmazonDelayed(raw: String?): Boolean {
    val t = raw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (t.isEmpty()) return false
    return DELAY_PHRASES.any { it in t }
}

/**
 * The delay note for a page: its headline when that is what reports the delay, else the newest
 * event row that does. The fallback is the 2026-08-18 capture's lesson — a tracker whose status
 * line had not yet been rewritten still had "Package delayed in transit" in its rows.
 */
private fun delayNoteFor(headline: String?, eventDescriptions: List<String> = emptyList()): String? =
    headline?.takeIf { isAmazonDelayed(it) }
        ?: eventDescriptions.lastOrNull { isAmazonDelayed(it) }

// --- Delivery-day parsing -----------------------------------------------------------------
//
// Amazon writes the day relatively ("tomorrow") or without a year ("Saturday, August 22"), so
// resolving it needs the date the page was read on — DomRaw.todayIso, supplied by the JS. Doing
// it here rather than in the blob is the point: the JS version recognized only the word
// "arriving", so the delay wording "Now expected tomorrow by 8 AM" silently produced no ETA at
// all (2026-08-18 capture), and an earlier version of the same function turned "tomorrow" into
// 2026-01-01. Neither was catchable without a device.

/**
 * Phrases that introduce a delivery promise, most specific first: "Now expected" supersedes an
 * "Arriving" promise in the same string, because it IS the revision of one.
 */
private val ETA_PHRASES = listOf("estimated delivery", "now expected", "expected", "arriving")

/**
 * Resolves a day phrase against [today] — relative wording first ("tomorrow", "overnight"),
 * then a year-less month+day with the year inferred (shared PageDates mechanics). Takes the
 * string verbatim, so it is only safe on text already known to BE a promise (the tracker's
 * promise element) — anything else must come through [amazonEtaFromStatus], which requires a
 * promise phrase first. Null [today] means the page never reported its date, and a guessed
 * year is worse than no ETA.
 */
internal fun parseAmazonDay(text: String?, today: LocalDate?): LocalDate? =
    parseRelativeDay(text, today) ?: parseDayWithoutYear(text, today)

/**
 * Pulls the delivery day out of a status headline, which only counts when a promise phrase
 * introduces it. The gate is what keeps "Delivered June 25" — a date that is history — from being
 * read as an upcoming arrival.
 */
internal fun amazonEtaFromStatus(text: String?, today: LocalDate?): LocalDate? {
    val t = text ?: return null
    val after = ETA_PHRASES.firstNotNullOfOrNull { phrase ->
        t.indexOf(phrase, ignoreCase = true).takeIf { it >= 0 }?.let { t.substring(it + phrase.length) }
    } ?: return null
    return parseAmazonDay(after, today)
}

private fun DomRaw.today(): LocalDate? = todayIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

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

// Verbatim from the JS blob's original decision (moved to Kotlin 2026-08-20); order-page copy,
// which is why the check lives in the cards branch just as it did in the blob.
private val AMAZON_NOT_FOUND = Regex(
    """problem finding this order|couldn.t find that order|can.t find that order|not a valid order""",
    RegexOption.IGNORE_CASE,
)

/** Order-details page: choose a shipment and either hop to its tracker or report its coarse state. */
internal fun resolveAmazonCards(raw: DomRaw): DomExtraction {
    if (raw.pageText?.let { AMAZON_NOT_FOUND.containsMatchIn(it) } == true) return DomExtraction(page = "notFound")
    val pick = pickShipmentCard(raw.cards) ?: return DomExtraction(page = "empty")
    val eta = amazonEtaFromStatus(pick.head, raw.today())
    // The headline's "Arriving <day>" carries the ETA but not a transit state (see IN_TRANSIT_PHRASES),
    // so a card can have a delivery date with no classifiable status. Keep the ETA regardless — a
    // shipment with no tracker link still yields a countdown — and leave status UNKNOWN until a real
    // signal (the tracker hop, or delivered/shipped/exception phrasing) supplies one.
    val status = classifyAmazonStatus(pick.head)
    val coarse = if (status != null || eta != null) {
        ScrapedTracking(
            status = (status ?: TrackingStatus.UNKNOWN).name,
            etaDate = eta?.toString(),
            delayNote = delayNoteFor(pick.head),
        )
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
            etaDate = (parseAmazonDay(raw.etaText, raw.today())
                ?: amazonEtaFromStatus(raw.statusText, raw.today()))?.toString()
                ?: raw.etaDate,
            etaWindowText = raw.etaWindowText,
            location = events.lastOrNull { it.location != null }?.location,
            delayNote = delayNoteFor(raw.statusText, events.map { it.description }),
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

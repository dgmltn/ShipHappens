package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.BaseKeywords
import com.dgmltn.shiphappens.source.webview.DomCard
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.PageOutcome
import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.StatusVocabulary
import com.dgmltn.shiphappens.source.webview.TrackerPageRules
import com.dgmltn.shiphappens.source.webview.parseDayWithoutYear
import com.dgmltn.shiphappens.source.webview.parseRelativeDay
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.today
import kotlinx.datetime.LocalDate

/**
 * Everything the Amazon scrape *decides*, kept out of AMAZON_EXTRACTION_JS so it can be tested
 * against real captured page strings (see AmazonPageLogicTest). The JS only reads text now.
 */

/**
 * Amazon's vocabulary starts from NO shared phrases (BaseKeywords.None): an order page mixes
 * shipment cards with RMA/returns copy, so the carrier base's bare "return" and "attempt"
 * would misclassify — "Replacement complete — We've received your return" was promoted to
 * EXCEPTION on 2026-07-19. Return phrasing is therefore matched only as a delivery outcome.
 *
 * "Arriving <day>" is deliberately NOT an in-transit phrase: Amazon shows it as the delivery
 * promise the moment an order is placed, so it says nothing about the shipment's state — it
 * only carries the ETA (see [amazonEtaFromStatus]). Real movement is signaled by the tracker
 * page's events and status line.
 *
 * Delay phrases include the revised-promise wording ("Now expected tomorrow by 8 AM"), used only
 * when the original promise slipped (captured 2026-08-18). They are modifiers, never stages:
 * "Package delayed in transit" reports the IN_TRANSIT its own wording names, while a stage-less
 * "Now expected tomorrow" answers null rather than guessing — a slipped promise is a valid state
 * for an order that has not shipped yet.
 */
internal val AMAZON_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        outForDelivery = listOf("out for delivery"),
        delivered = listOf("delivered"),
        // Longest-intent-first: a delivered order still shows returns copy and an open return window.
        exception = listOf(
            "undeliverable", "delivery attempted", "returned to sender", "return to sender",
            "being returned", "package was lost", "lost in transit",
        ),
        labelCreated = listOf("ordered", "order placed", "preparing for shipment"),
        shipped = listOf("shipped", "dispatched", "picked up"),
        inTransit = listOf("in transit", "on the way", "on its way", "at carrier"),
        delayed = listOf("delayed", "running late", "now expected"),
    ),
    base = BaseKeywords.None,
)

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
    val shipments = cards.filter { it.href != null || AMAZON_VOCABULARY.classify(it.head) != null }
    val pool = shipments.ifEmpty { cards }
    return pool.firstOrNull { AMAZON_VOCABULARY.classify(it.head) != TrackingStatus.DELIVERED } ?: pool.last()
}

// Verbatim from the JS blob's original decision (moved to Kotlin 2026-08-20); order-page copy,
// which is why the check lives in the cards branch just as it did in the blob.
private val AMAZON_NOT_FOUND = Regex(
    """problem finding this order|couldn.t find that order|can.t find that order|not a valid order""",
    RegexOption.IGNORE_CASE,
)

/** Order-details page: choose a shipment and either hop to its tracker or report its coarse state. */
internal fun resolveAmazonCards(raw: DomRaw): PageOutcome {
    if (raw.pageText?.let { AMAZON_NOT_FOUND.containsMatchIn(it) } == true) return PageOutcome.NotFound
    val pick = pickShipmentCard(raw.cards) ?: return PageOutcome.Empty
    val eta = amazonEtaFromStatus(pick.head, raw.today())
    // The headline's "Arriving <day>" carries the ETA but not a transit state, so a card can
    // have a delivery date with no classifiable status. Keep the ETA regardless — a shipment
    // with no tracker link still yields a countdown — and leave status UNKNOWN until a real
    // signal (the tracker hop, or delivered/shipped/exception phrasing) supplies one.
    val status = AMAZON_VOCABULARY.classify(pick.head)
    val coarse = if (status != null || eta != null) {
        TrackingSnapshot(
            status = status ?: TrackingStatus.UNKNOWN,
            etaDate = eta,
            delayNote = pick.head.takeIf { AMAZON_VOCABULARY.isDelayed(it) },
        )
    } else null
    val href = pick.href
    return when {
        href != null -> PageOutcome.Goto(href, coarse)
        coarse != null -> PageOutcome.Tracking(coarse)
        else -> PageOutcome.Empty
    }
}

/**
 * Tracker page: the shared ladder, with the one thing it cannot know — Amazon writes the promise
 * in the status headline ("Now expected tomorrow by 8 AM"), gated by a promise phrase so a
 * "Delivered June 25" date is never read as an arrival. The promise element, when present,
 * still wins.
 */
internal val AMAZON_PAGE = TrackerPageRules(
    vocabulary = AMAZON_VOCABULARY,
    etaDate = { raw, today -> parseAmazonDay(raw.etaText, today) ?: amazonEtaFromStatus(raw.statusText, today) },
)

/** Dispatches a raw extraction by the page that produced it. */
internal fun parseAmazonRaw(raw: DomRaw): PageOutcome? = when (raw.kind) {
    "cards" -> resolveAmazonCards(raw)
    "tracker" -> resolveTrackerPage(raw, AMAZON_PAGE)
    else -> null
}

package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomCard
import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus

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
    // Amazon's revised-promise wording ("Now expected tomorrow by 8 AM"), which it uses only when
    // the original promise slipped. Captured 2026-08-18 on a shipment whose only other delay
    // signal was an event row — a tracker page that hadn't logged one yet would have looked fine.
    "now expected",
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


// --- Delivery-day parsing -----------------------------------------------------------------
//
// Amazon writes the day relatively ("tomorrow") or without a year ("Saturday, August 22"), so
// resolving it needs the date the page was read on — DomRaw.todayIso, supplied by the JS. Doing
// it here rather than in the blob is the point: the JS version recognized only the word
// "arriving", so the delay wording "Now expected tomorrow by 8 AM" silently produced no ETA at
// all (2026-08-18 capture), and an earlier version of the same function turned "tomorrow" into
// 2026-01-01. Neither was catchable without a device.

private const val MONTHS =
    "January|February|March|April|May|June|July|August|September|October|November|December|" +
        "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec"
private val MONTH_DAY = Regex("""\b($MONTHS)\.?\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE)
private val DAY_MONTH = Regex("""\b(\d{1,2})\s+($MONTHS)\b""", RegexOption.IGNORE_CASE)

/**
 * Phrases that introduce a delivery promise, most specific first: "Now expected" supersedes an
 * "Arriving" promise in the same string, because it IS the revision of one.
 */
private val ETA_PHRASES = listOf("estimated delivery", "now expected", "expected", "arriving")

private fun monthOf(name: String): Month? =
    Month.entries.firstOrNull { it.name.startsWith(name.trimEnd('.').uppercase()) }

/**
 * Resolves a day phrase against [today]. Takes the string verbatim, so it is only safe on text
 * already known to BE a promise (the tracker's promise element) — anything else must come through
 * [amazonEtaFromStatus], which requires a promise phrase first. Null [today] means the page never
 * reported its date, and a guessed year is worse than no ETA.
 */
internal fun parseAmazonDay(text: String?, today: LocalDate?): LocalDate? {
    if (text.isNullOrBlank() || today == null) return null
    val t = text.lowercase()
    // "Arriving overnight 7 AM – 11 AM": delivery during the coming night, i.e. tomorrow morning.
    when {
        "today" in t -> return today
        "tomorrow" in t || "overnight" in t -> return today.plus(1, DateTimeUnit.DAY)
        "yesterday" in t -> return today.minus(1, DateTimeUnit.DAY)
    }
    val (monthName, day) = MONTH_DAY.find(text)?.destructured?.let { (m, d) -> m to d }
        ?: DAY_MONTH.find(text)?.destructured?.let { (d, m) -> m to d }
        ?: return null
    val month = monthOf(monthName) ?: return null
    val dayOfMonth = day.toIntOrNull() ?: return null
    val candidate = runCatching { LocalDate(today.year, month, dayOfMonth) }.getOrNull() ?: return null
    // The year is ours, not the page's: a December promise read in January would otherwise land 11
    // months out. Anything implausibly far ahead belongs to last year.
    return if (candidate > today.plus(45, DateTimeUnit.DAY)) {
        runCatching { LocalDate(today.year - 1, month, dayOfMonth) }.getOrNull()
    } else {
        candidate
    }
}

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

/** Order-details page: choose a shipment and either hop to its tracker or report its coarse state. */
internal fun resolveAmazonCards(raw: DomRaw): DomExtraction {
    val pick = pickShipmentCard(raw.cards) ?: return DomExtraction(page = "empty")
    val eta = amazonEtaFromStatus(pick.head, raw.today())
    // The headline's "Arriving <day>" carries the ETA but not a transit state (see IN_TRANSIT_PHRASES),
    // so a card can have a delivery date with no classifiable status. Keep the ETA regardless — a
    // shipment with no tracker link still yields a countdown — and leave status UNKNOWN until a real
    // signal (the tracker hop, or delivered/shipped/exception phrasing) supplies one.
    val status = classifyAmazonStatus(pick.head)
    val coarse = if (status != null || eta != null) {
        ScrapedTracking(status = (status ?: TrackingStatus.UNKNOWN).name, etaDate = eta?.toString())
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

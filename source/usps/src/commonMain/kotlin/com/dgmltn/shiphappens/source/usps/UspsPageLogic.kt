package com.dgmltn.shiphappens.source.usps

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month

/**
 * Everything the USPS scrape *decides*, kept out of USPS_EXTRACTION_JS so it can be tested against
 * real captured page strings (see UspsPageLogicTest). The JS only reads text now — the same split
 * AmazonPageLogic made after the 2026-07-19 vocabulary bug, prompted here by its USPS twin: the
 * progress bar's current step says "On the Way", a wording the JS blob's classifier didn't know,
 * so a moving package reported UNKNOWN and the card kept its stale "Label created".
 */

/**
 * Maps a USPS status wording (banner headline or event description) to a status, or null when the
 * text isn't recognizable. Shared by [UspsApiParser], so both extraction layers speak one
 * vocabulary and a new wording is added (and tested) exactly once.
 */
internal fun classifyUspsStatus(raw: String?): TrackingStatus? {
    val t = raw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (t.isEmpty()) return null
    return when {
        "out for delivery" in t -> TrackingStatus.OUT_FOR_DELIVERY
        "delivered" in t -> TrackingStatus.DELIVERED
        "alert" in t || "attempted" in t || "notice left" in t || "return" in t || "held" in t ->
            TrackingStatus.EXCEPTION
        "label created" in t || "pre-shipment" in t || "awaiting item" in t -> TrackingStatus.LABEL_CREATED
        "accepted" in t || "picked up" in t || "possession" in t -> TrackingStatus.SHIPPED
        "in transit" in t || "on the way" in t || "on its way" in t || "departed" in t ||
            "arrived" in t || "moving through" in t || "processed" in t -> TrackingStatus.IN_TRANSIT
        else -> null
    }
}

// The ETA banner's textContent interleaves tooltip copy with the date, and the date itself renders
// in two orders: "Tuesday 28 July 2026" (progress banner, split across spans) and
// "Monday, July 28, 2026" (the "Expected Delivery on" variants). Neither tooltip contains a
// month-name-adjacent number, so anchored month regexes are safe against the junk.
private const val MONTH_NAMES =
    "January|February|March|April|May|June|July|August|September|October|November|December"
private val DAY_MONTH_YEAR = Regex("""\b(\d{1,2})\s+($MONTH_NAMES)\s+(\d{4})""", RegexOption.IGNORE_CASE)
private val MONTH_DAY_YEAR = Regex("""\b($MONTH_NAMES)\s+(\d{1,2}),?\s+(\d{4})""", RegexOption.IGNORE_CASE)

/** Pulls the expected-delivery date out of the banner's flattened text, in either wording order. */
internal fun parseUspsEtaDate(text: String?): LocalDate? {
    if (text.isNullOrBlank()) return null
    val (monthName, day, year) = DAY_MONTH_YEAR.find(text)?.destructured?.let { (d, m, y) -> Triple(m, d, y) }
        ?: MONTH_DAY_YEAR.find(text)?.destructured?.let { (m, d, y) -> Triple(m, d, y) }
        ?: return null
    val month = Month.entries.firstOrNull { it.name.equals(monthName, ignoreCase = true) } ?: return null
    return runCatching { LocalDate(year.toInt(), month, day.toInt()) }.getOrNull()
}

// The two window shapes parseEtaWindow understands, extracted verbatim so the banner's tooltip
// copy never reaches the parser: a range ("9:45am - 1:45pm", "between 9:45am and 1:45pm") or a
// lone cutoff ("by 9:00pm"). "Out for Delivery" banners phrase the range as "between X and Y"
// rather than "X - Y" or "X to Y", so "and" joins the separator alternation alongside "to".
private val WINDOW_RANGE = Regex(
    """\d{1,2}(?::\d{2})?\s*(?:am|pm)?\s*(?:-|–|—|to|and)\s*\d{1,2}(?::\d{2})?\s*(?:am|pm)""",
    RegexOption.IGNORE_CASE,
)
private val WINDOW_CUTOFF = Regex("""\bby\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)""", RegexOption.IGNORE_CASE)

/** The delivery-window phrase as quoted on the page, or null when the banner carries none. */
internal fun uspsEtaWindowText(text: String?): String? {
    if (text.isNullOrBlank()) return null
    return WINDOW_RANGE.find(text)?.value ?: WINDOW_CUTOFF.find(text)?.value
}

/** Tracker page: classify the banner headline and each event row; newest event supplies fallbacks. */
internal fun parseUspsRaw(raw: DomRaw): DomExtraction? {
    if (raw.kind != "tracker") return null
    val events = raw.events.map {
        ScrapedEvent(
            timestamp = it.timestamp,
            description = it.description,
            location = it.location,
            status = classifyUspsStatus(it.description)?.name,
        )
    }
    if (raw.statusText.isNullOrBlank() && events.isEmpty()) return DomExtraction(page = "empty")
    // Events arrive oldest-first; the newest classifiable one backs an unreadable headline.
    val status = classifyUspsStatus(raw.statusText)
        ?: events.lastOrNull { it.status != null }?.status?.let { TrackingStatus.valueOf(it) }
        ?: TrackingStatus.UNKNOWN
    return DomExtraction(
        page = "ok",
        tracking = ScrapedTracking(
            status = status.name,
            etaDate = parseUspsEtaDate(raw.etaText)?.toString(),
            etaWindowText = uspsEtaWindowText(raw.etaText),
            location = events.lastOrNull { it.location != null }?.location,
            events = events,
        ),
    )
}

package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.plus

/**
 * Everything the FedEx scrape *decides*, kept out of FEDEX_EXTRACTION_JS so it is testable
 * against captured page strings — the split UspsPageLogic/AmazonPageLogic settled on after the
 * 2026-07 vocabulary bugs. The JS only reads text.
 */

/**
 * Maps a fedex.com status wording (banner headline or scan-event description) to a status, or
 * null when the text isn't recognizable. Shared by [FedexApiParser], so both extraction layers
 * speak one vocabulary and a new wording is added (and tested) exactly once.
 */
internal fun classifyFedexStatus(raw: String?): TrackingStatus? {
    val t = raw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
    if (t.isEmpty()) return null
    return when {
        "out for delivery" in t || "on fedex vehicle" in t -> TrackingStatus.OUT_FOR_DELIVERY
        "delivered" in t -> TrackingStatus.DELIVERED
        // "Held at FedEx location" may be a customer-requested hold, but it still needs the
        // user's attention (someone must go pick it up), so it rides the EXCEPTION lane.
        "exception" in t || "delay" in t || "held" in t || "unable to deliver" in t ||
            "return" in t || "incorrect address" in t -> TrackingStatus.EXCEPTION
        "label created" in t || "shipment information sent" in t -> TrackingStatus.LABEL_CREATED
        "picked up" in t || "we have your package" in t -> TrackingStatus.SHIPPED
        "in transit" in t || "on the way" in t || "on its way" in t || "departed" in t ||
            "arrived" in t || "at local fedex facility" in t || "at destination sort" in t ||
            "left fedex origin" in t || "international shipment release" in t -> TrackingStatus.IN_TRANSIT
        else -> null
    }
}

// fedex.com renders the promise date two ways ("Tuesday 8/19/2026" in the fedextrack banner,
// "Tuesday, August 19, 2026" in detail copy) and sometimes relatively ("today"/"tomorrow" near
// delivery) — the relative forms resolve against DomRaw.todayIso, same contract as Amazon.
private const val MONTH_NAMES =
    "January|February|March|April|May|June|July|August|September|October|November|December"
// Lookbehind, not \b: the hero flattens to "Thursday8/20/2026" (QA 2026-08-19), and there is no
// word boundary between "y" and "8".
private val NUMERIC_MDY = Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})/(\d{4})\b""")
private val MONTH_DAY_YEAR = Regex("""\b($MONTH_NAMES)\s+(\d{1,2}),?\s+(\d{4})""", RegexOption.IGNORE_CASE)

/** Pulls the expected-delivery date out of the banner's flattened text; null [today] disables
 *  relative wording (the page never told us what day it was read on). */
internal fun parseFedexEtaDate(text: String?, today: LocalDate? = null): LocalDate? {
    if (text.isNullOrBlank()) return null
    NUMERIC_MDY.find(text)?.let { m ->
        val (mm, dd, yyyy) = m.destructured
        return runCatching { LocalDate(yyyy.toInt(), mm.toInt(), dd.toInt()) }.getOrNull()
    }
    MONTH_DAY_YEAR.find(text)?.let { m ->
        val (monthName, dd, yyyy) = m.destructured
        val month = Month.entries.firstOrNull { it.name.equals(monthName, ignoreCase = true) } ?: return null
        return runCatching { LocalDate(yyyy.toInt(), month, dd.toInt()) }.getOrNull()
    }
    val t = text.lowercase()
    if (today != null) {
        if ("tomorrow" in t) return today.plus(1, DateTimeUnit.DAY)
        if ("today" in t) return today
    }
    return null
}

// The window shapes parseEtaWindow understands, quoted verbatim ("between" prefix included) so
// tooltip copy never reaches the parser: a range ("10:35 AM - 2:35 PM", "between 10:35 AM and
// 2:35 PM") or a lone cutoff ("by 8:00 PM"). "by end of day" carries no time and is no window.
private val WINDOW_RANGE = Regex(
    """(?:between\s+)?\d{1,2}(?::\d{2})?\s*(?:am|pm)?\s*(?:-|–|—|to|and)\s*\d{1,2}(?::\d{2})?\s*(?:am|pm)""",
    RegexOption.IGNORE_CASE,
)
private val WINDOW_CUTOFF = Regex("""\bby\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)""", RegexOption.IGNORE_CASE)

/** The delivery-window phrase as quoted on the page, or null when the banner carries none. */
internal fun fedexEtaWindowText(text: String?): String? {
    if (text.isNullOrBlank()) return null
    return WINDOW_RANGE.find(text)?.value ?: WINDOW_CUTOFF.find(text)?.value
}

private fun DomRaw.today(): LocalDate? = todayIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/** Tracker page: classify the banner headline and each event row; newest event supplies fallbacks. */
internal fun parseFedexRaw(raw: DomRaw): DomExtraction? {
    if (raw.kind != "tracker") return null
    val events = raw.events.map {
        ScrapedEvent(
            timestamp = it.timestamp,
            description = it.description,
            location = it.location,
            status = classifyFedexStatus(it.description)?.name,
        )
    }
    val etaDate = parseFedexEtaDate(raw.etaText, raw.today())
    val etaWindowText = fedexEtaWindowText(raw.etaText)
    // A page with only a delivery promise is still a result (status UNKNOWN), not an empty shell.
    if (raw.statusText.isNullOrBlank() && events.isEmpty() && etaDate == null && etaWindowText == null) {
        return DomExtraction(page = "empty")
    }
    // Events arrive oldest-first; the newest classifiable one backs an unreadable headline.
    val status = classifyFedexStatus(raw.statusText)
        ?: events.lastOrNull { it.status != null }?.status?.let { TrackingStatus.valueOf(it) }
        ?: TrackingStatus.UNKNOWN
    return DomExtraction(
        page = "ok",
        tracking = ScrapedTracking(
            status = status.name,
            etaDate = etaDate?.toString(),
            etaWindowText = etaWindowText,
            location = fedexLocation(raw.locationText) ?: events.lastOrNull { it.location != null }?.location,
            events = events,
        ),
    )
}

/** "Currently in Sacramento, CA" → "Sacramento, CA"; any other phrasing passes through verbatim. */
internal fun fedexLocation(text: String?): String? {
    val t = text?.replace(Regex("\\s+"), " ")?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return t.replace(Regex("""^currently\s+in\s+""", RegexOption.IGNORE_CASE), "")
}

package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.classifyStatusWording
import com.dgmltn.shiphappens.source.webview.findEtaWindowText
import com.dgmltn.shiphappens.source.webview.parseMonthNameDate
import com.dgmltn.shiphappens.source.webview.parseNumericMdyDate
import com.dgmltn.shiphappens.source.webview.parseRelativeDay
import com.dgmltn.shiphappens.source.webview.parseWeekdayName
import kotlinx.datetime.LocalDate

/**
 * Everything the FedEx scrape *decides*, kept out of FEDEX_EXTRACTION_JS so it is testable
 * against captured page strings — the split UspsPageLogic/AmazonPageLogic settled on after the
 * 2026-07 vocabulary bugs. The JS only reads text.
 */

// FedEx wordings on top of the shared vocabulary (StatusVocabulary.kt). "Held at FedEx
// location" may be a customer-requested hold, but it still needs the user's attention (someone
// must go pick it up) — the shared "held" keyword already rides the EXCEPTION lane.
private val FEDEX_KEYWORDS = StatusKeywords(
    outForDelivery = listOf("on fedex vehicle"),
    exception = listOf("incorrect address"),
    labelCreated = listOf("shipment information sent"),
    shipped = listOf("we have your package"),
    inTransit = listOf(
        "at local fedex facility", "at destination sort", "left fedex origin",
        "international shipment release",
    ),
)

/**
 * Maps a fedex.com status wording (banner headline or scan-event description) to a status, or
 * null when the text isn't recognizable.
 */
internal fun classifyFedexStatus(raw: String?): TrackingStatus? = classifyStatusWording(raw, FEDEX_KEYWORDS)

/**
 * Pulls the expected-delivery date out of the banner's flattened text. fedex.com renders the
 * promise numerically ("Thursday8/20/2026" — run together), in month-name copy, relatively, or
 * as a bare weekday near delivery ("Thursday Between 10:10 AM - 2:10 PM" — both forms captured
 * live 2026-08-19, minutes apart); null [today] disables the relative/weekday forms (the page
 * never told us what day it was read on). Weekday resolution is safe here because the banner
 * element IS the promise — see [parseWeekdayName]'s past-weekday caveat.
 */
internal fun parseFedexEtaDate(text: String?, today: LocalDate? = null): LocalDate? =
    parseNumericMdyDate(text) ?: parseMonthNameDate(text) ?: parseRelativeDay(text, today)
        ?: parseWeekdayName(text, today)

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
    val etaWindowText = findEtaWindowText(raw.etaText)
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

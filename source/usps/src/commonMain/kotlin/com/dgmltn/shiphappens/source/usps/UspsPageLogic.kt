package com.dgmltn.shiphappens.source.usps

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.classifyStatusWording
import com.dgmltn.shiphappens.source.webview.findEtaWindowText
import com.dgmltn.shiphappens.source.webview.parseMonthNameDate
import kotlinx.datetime.LocalDate

/**
 * Everything the USPS scrape *decides*, kept out of USPS_EXTRACTION_JS so it can be tested against
 * real captured page strings (see UspsPageLogicTest). The JS only reads text now — the same split
 * AmazonPageLogic made after the 2026-07-19 vocabulary bug, prompted here by its USPS twin: the
 * progress bar's current step says "On the Way", a wording the JS blob's classifier didn't know,
 * so a moving package reported UNKNOWN and the card kept its stale "Label created".
 */

// USPS wordings on top of the shared vocabulary (StatusVocabulary.kt): tracker-banner phrasing
// like "moving through our network" and acceptance-scan wording. Bare "processed" ("Processed
// Through Facility") is safe as a late-stage keyword because the shared chain evaluates the
// merged vocabulary in one pass — an earlier "out for delivery" in the same text still wins.
private val USPS_KEYWORDS = StatusKeywords(
    exception = listOf("alert"),
    labelCreated = listOf("pre-shipment", "awaiting item"),
    shipped = listOf("accepted", "possession"),
    inTransit = listOf("moving through", "processed"),
)

/**
 * Maps a USPS status wording (banner headline or event description) to a status, or null when the
 * text isn't recognizable. Shared by [UspsApiParser], so both extraction layers speak one
 * vocabulary and a new wording is added (and tested) exactly once.
 */
internal fun classifyUspsStatus(raw: String?): TrackingStatus? = classifyStatusWording(raw, USPS_KEYWORDS)

// The ETA banner's textContent interleaves tooltip copy with the date, and the date itself renders
// in two orders: "Tuesday 28 July 2026" (progress banner, split across spans) and
// "Monday, July 28, 2026" (the "Expected Delivery on" variants). Neither tooltip contains a
// month-name-adjacent number, so the shared month-name parsing is safe against the junk.
internal fun parseUspsEtaDate(text: String?): LocalDate? = parseMonthNameDate(text)

// Verbatim from the JS blob's original decision (moved to Kotlin 2026-08-20).
private val USPS_NOT_FOUND =
    Regex("""status not available|could not locate the tracking information""", RegexOption.IGNORE_CASE)

/** Tracker page: classify the banner headline and each event row; newest event supplies fallbacks. */
internal fun parseUspsRaw(raw: DomRaw): DomExtraction? {
    if (raw.kind != "tracker") return null
    if (raw.pageText?.let { USPS_NOT_FOUND.containsMatchIn(it) } == true) return DomExtraction(page = "notFound")
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
            etaWindowText = findEtaWindowText(raw.etaText),
            location = events.lastOrNull { it.location != null }?.location,
            events = events,
        ),
    )
}

package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.classifyStatusWording

/**
 * Everything the UPS scrape *decides* — the last source to leave the classify-in-JS pattern
 * (2026-08-19 consolidation): UPS_EXTRACTION_JS used to bucket statuses inline, where no unit
 * test could reach the vocabulary. The JS only reads text now; this file classifies, shared
 * with [UpsApiParser] so both extraction layers speak one vocabulary.
 */

// UPS wordings on top of the shared vocabulary (StatusVocabulary.kt). "action" is deliberately
// bare — the old JS matched it bare ("Action Needed"), the API says "action required"; bare
// covers both. "label"/"not received" are the API's label-stage phrasing, "order processed" the
// DOM banner's ("Order Processed: Ready for UPS" — kept UPS-local because on USPS pages bare
// "processed" is a transit scan).
private val UPS_KEYWORDS = StatusKeywords(
    exception = listOf("action"),
    labelCreated = listOf("label", "not received", "order processed"),
    shipped = listOf("origin scan", "pickup"),
)

internal fun classifyUpsStatus(raw: String?): TrackingStatus? = classifyStatusWording(raw, UPS_KEYWORDS)

/**
 * Tracker page: classify the status headline. The DOM layer is UPS's coarse fallback (API
 * capture is primary), so a present-but-novel headline still reports (status UNKNOWN) exactly
 * as the pre-conversion JS did.
 */
internal fun parseUpsRaw(raw: DomRaw): DomExtraction? {
    if (raw.kind != "tracker") return null
    if (raw.statusText.isNullOrBlank()) return DomExtraction(page = "empty")
    val status = classifyUpsStatus(raw.statusText) ?: TrackingStatus.UNKNOWN
    return DomExtraction(page = "ok", tracking = ScrapedTracking(status = status.name))
}

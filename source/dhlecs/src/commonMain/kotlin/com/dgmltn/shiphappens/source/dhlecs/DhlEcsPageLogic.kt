package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.classifyStatusWording
import com.dgmltn.shiphappens.source.webview.isDelayedWording
import com.dgmltn.shiphappens.source.webview.isMultiStageWording

/**
 * Everything the DHL eCommerce scrape *decides*. The wordings come from webtrack's en-US locale
 * file (recon 2026-09-03), which enumerates the full event vocabulary (`id_99`…`id_803`) — the
 * API's SCREAMING `primaryEventDescription` values are the same strings uppercased, so one
 * vocabulary serves both [DhlEcsApiParser] and the DOM fallback.
 */
private val DHLECS_KEYWORDS = StatusKeywords(
    labelCreated = listOf("electronic notification"),
    shipped = listOf("pick up", "accepted", "received by carrier"),
    inTransit = listOf(
        "arrival", "en route", "processed", "departure", "forwarded", "sorted",
        "tendered", "manifested", "transport", "customs clearance", "cleared customs",
    ),
    exception = listOf(
        "refused", "undeliverable", "damage", "missent", "mis-shipped", "dead letter",
        "no such number", "insufficient", "unclaimed", "vacant", "addressee unknown",
        "not possible", "recalled",
    ),
)

internal fun classifyDhlEcsStatus(raw: String?): TrackingStatus? {
    // "Undelivered - Processes for Local Disposal" (event 636) contains the substring
    // "delivered", and the shared chain checks DELIVERED before EXCEPTION — catch the
    // negation here, before delegating.
    if (raw?.contains("undelivered", ignoreCase = true) == true) return TrackingStatus.EXCEPTION
    return classifyStatusWording(raw, DHLECS_KEYWORDS)
}

// "Unfortunately, no results found. Please confirm the accuracy of your tracking number…" —
// webtrack's en-US no_records copy (locale recon 2026-09-03; wording re-checked at device QA).
private val DHLECS_NOT_FOUND =
    Regex("""no results? found|confirm the accuracy of your tracking number""", RegexOption.IGNORE_CASE)

/** Coarse DOM fallback for when the API capture misses: classify the visible status wording. */
internal fun parseDhlEcsRaw(raw: DomRaw): DomExtraction? {
    if (raw.kind != "tracker") return null
    if (raw.pageText?.let { DHLECS_NOT_FOUND.containsMatchIn(it) } == true) return DomExtraction(page = "notFound")
    val statusText = raw.statusText?.takeIf { it.isNotBlank() } ?: return null
    // A container grab that swallowed the progress rail names every stage at once — refuse it
    // (Unparsed, nothing persisted) rather than classify; the API capture on the same page has
    // the truth. Live QA 2026-09-03: the details route's rail overwrote a label-only package as
    // DELIVERED through the scrape-on-view path.
    if (isMultiStageWording(statusText, DHLECS_KEYWORDS)) return null
    val status = classifyDhlEcsStatus(statusText) ?: TrackingStatus.UNKNOWN
    // The page copy is the only delay wording the DOM fallback has, so it is the note.
    val delayNote = statusText.takeIf { isDelayedWording(it) }
    return DomExtraction(page = "ok", tracking = ScrapedTracking(status = status.name, delayNote = delayNote))
}

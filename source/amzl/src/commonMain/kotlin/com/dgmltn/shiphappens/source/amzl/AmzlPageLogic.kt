package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedTracking

/**
 * Classifies the tracking page's visible status headline ("Arriving Wednesday", "Out for
 * delivery") — the coarse DOM fallback for when the API capture misses. English page copy,
 * so this vocabulary is separate from [AmzlApiParser]'s API codes.
 */
// Verbatim from the JS blob's original decision (moved to Kotlin 2026-08-20).
private val AMZL_NOT_FOUND =
    Regex("""couldn.t find|can.t find|unable to find|invalid tracking|no longer available""", RegexOption.IGNORE_CASE)

internal fun parseAmzlRaw(raw: DomRaw): DomExtraction? {
    if (raw.kind != "tracker") return null
    if (raw.pageText?.let { AMZL_NOT_FOUND.containsMatchIn(it) } == true) return DomExtraction(page = "notFound")
    val t = raw.statusText?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val status = when {
        "out for delivery" in t -> "OUT_FOR_DELIVERY"
        "attempt" in t || "undeliverable" in t || "problem" in t || "delayed" in t -> "EXCEPTION"
        "delivered" in t -> "DELIVERED"
        "arriving" in t || "in transit" in t || "on the way" in t || "on its way" in t || "shipped" in t -> "IN_TRANSIT"
        "label" in t || "package details" in t || "preparing" in t -> "LABEL_CREATED"
        else -> "UNKNOWN"
    }
    return DomExtraction(page = "ok", tracking = ScrapedTracking(status = status))
}

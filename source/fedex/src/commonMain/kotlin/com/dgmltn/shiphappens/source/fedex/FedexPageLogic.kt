package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.StatusVocabulary
import com.dgmltn.shiphappens.source.webview.TrackerPageRules

/**
 * What the FedEx scrape *decides* beyond the shared tracker-page resolver: its vocabulary, its
 * not-found copy, and one hook for the location banner's phrasing. Everything else — the
 * promise date chain, event rows built from verbatim date-group headers plus times, the
 * fallback ladder — is the shared resolver's.
 */

// FedEx wordings on top of the shared vocabulary. "Held at FedEx location" may be a
// customer-requested hold, but it still needs the user's attention (someone must go pick it up)
// — the shared "held" keyword already rides the EXCEPTION lane.
internal val FEDEX_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        outForDelivery = listOf("on fedex vehicle"),
        exception = listOf("incorrect address"),
        labelCreated = listOf("shipment information sent"),
        // "In FedEx possession" is the travel history's pickup-adjacent scan (live capture 2026-08-21).
        shipped = listOf("we have your package", "possession"),
        inTransit = listOf(
            "at local fedex facility", "at destination sort", "left fedex origin",
            "international shipment release",
        ),
    ),
)

/** "Currently in Sacramento, CA" → "Sacramento, CA"; any other phrasing passes through verbatim. */
internal fun fedexLocation(text: String?): String? {
    val t = text?.replace(Regex("\\s+"), " ")?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return t.replace(Regex("""^currently\s+in\s+""", RegexOption.IGNORE_CASE), "")
}

// The three live not-found wordings (QA 2026-08-19/20): /fedextrack/no-results-found ("The
// tracking number you entered can't be found right now"), the system-error page ("We can't find
// that tracking number. Please check with the shipper"), and the generic no-record phrasing.
// The "can't find that/this tracking number" and "no record" forms are shared seeds now; the
// FedEx-specific phrasings stay here.
internal val FEDEX_PAGE = TrackerPageRules(
    vocabulary = FEDEX_VOCABULARY,
    notFound = listOf("""tracking number.{0,80}can.t be found|please check (the number )?with the shipper"""),
    location = { fedexLocation(it.locationText) },
)

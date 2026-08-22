package com.dgmltn.shiphappens.source.webview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One message posted from the in-page JS bridge to Kotlin. */
@Serializable
data class BridgePayload(val kind: String, val url: String? = null, val body: String)

/** Parsed body of a `kind == "dom"` payload (the extraction runner's output). */
@Serializable
data class DomExtraction(
    val page: String,
    val url: String? = null,
    val tracking: ScrapedTracking? = null,
    val raw: DomRaw? = null,
)

/**
 * Verbatim page text for extractors that classify in Kotlin rather than in the JS blob.
 *
 * commonTest has no JS engine, so anything decided inside [WebProviderSpec.extractionJs] is
 * verifiable only by rescraping on a device — which is how a status-vocabulary bug shipped to QA
 * on 2026-07-19. A `page:'raw'` extraction therefore carries the strings it read and nothing
 * more; [WebProviderSpec.parseRaw] turns them into an outcome in testable Kotlin.
 *
 * [kind] names which page produced this ("cards" for an order/shipment list, "tracker" for a
 * detail page); the fields each kind populates are provider-documented.
 */
@Serializable
data class DomRaw(
    val kind: String,
    val cards: List<DomCard> = emptyList(),
    val statusText: String? = null,
    val etaDate: String? = null,
    /** Verbatim text of the page's ETA banner, for providers whose date needs Kotlin-side parsing
     *  (USPS splits it across spans with tooltip copy interleaved); [etaDate] is for extractors
     *  that can produce ISO themselves. */
    val etaText: String? = null,
    val etaWindowText: String? = null,
    /** Verbatim current-location banner ("Currently in Sacramento, CA") for tracker pages that
     *  show a location without any event rows; the provider strips the phrasing in Kotlin. */
    val locationText: String? = null,
    /** Head of the page's flattened body text, so page-state wording (not-found and friends) is
     *  classified in the provider's tested Kotlin instead of a regex inside the JS blob — the
     *  fedex /no-results-found wording miss (QA 2026-08-19) was exactly a blob-only decision. */
    val pageText: String? = null,
    /** The device's local date when the page was read, ISO. Pages that phrase a delivery day
     *  relatively ("tomorrow") or without a year ("Saturday, August 22") can only be resolved
     *  against it, and resolving in Kotlin keeps that arithmetic under test. */
    val todayIso: String? = null,
    val events: List<DomRawEvent> = emptyList(),
)

/** One shipment card from a list page: its status headline and detail-page link, unclassified.
 *  The headline also carries the delivery day; parsing it is the provider's job (see [DomRaw.todayIso]). */
@Serializable
data class DomCard(val head: String = "", val href: String? = null)

/** One event row. [timestamp] is ISO-8601 when the page's own date context lets the JS normalize
 *  it (USPS); pages that render date-group headers plus bare times (FedEx's travel history) send
 *  the verbatim strings in [whenText] instead, and the provider's Kotlin builds the timestamp. */
@Serializable
data class DomRawEvent(
    val timestamp: String = "",
    val description: String,
    val location: String? = null,
    val whenText: String? = null,
)

sealed interface RouteResult {
    data class Tracking(val tracking: ScrapedTracking) : RouteResult
    /** A validated one-hop navigation request from the extractor, optionally carrying a coarse
     *  tracking fallback extracted from the page that requested the hop (design spec §1). */
    data class Goto(val url: String, val tracking: ScrapedTracking?) : RouteResult
    data object NotFound : RouteResult
    data object LoginWall : RouteResult
    data object Challenge : RouteResult
    data object Unparsed : RouteResult
}

/**
 * True when [url] is an https URL whose host is [domain] or a subdomain of it — the only targets
 * a 'goto' hop may navigate to. Plain string parsing (commonMain has no platform URL class);
 * an authority containing userinfo ('@') is rejected outright rather than parsed around.
 */
internal fun isAllowedHopUrl(url: String, domain: String): Boolean {
    if (!url.startsWith("https://")) return false
    val authority = url.removePrefix("https://").takeWhile { it != '/' && it != '?' && it != '#' }
    if ('@' in authority) return false
    val host = authority.substringBefore(':').lowercase()
    val d = domain.lowercase()
    return host == d || host.endsWith(".$d")
}

/** Routes raw bridge payload JSON to a provider-agnostic [RouteResult]. Pure, commonMain, tested. */
class PayloadRouter(private val spec: WebProviderSpec) {
    private val json = Json { ignoreUnknownKeys = true }

    fun route(payloadJson: String): RouteResult {
        val payload = runCatching { json.decodeFromString<BridgePayload>(payloadJson) }.getOrNull()
            ?: return RouteResult.Unparsed
        return when (payload.kind) {
            "api" -> spec.parseApi(payload.url, payload.body)
                ?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
            "dom" -> {
                val dom = runCatching { json.decodeFromString<DomExtraction>(payload.body) }.getOrNull()
                    ?: return RouteResult.Unparsed
                routeDom(dom)
            }
            else -> RouteResult.Unparsed
        }
    }

    private fun routeDom(dom: DomExtraction): RouteResult = when (dom.page) {
        "ok" -> dom.tracking?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
        "goto" ->
            if (dom.url != null && isAllowedHopUrl(dom.url, spec.cookieDomain)) {
                RouteResult.Goto(dom.url, dom.tracking)
            } else {
                // Bad hop target: salvage the coarse tracking if the extractor sent one.
                dom.tracking?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
            }
        // Provider Kotlin decides the outcome, then that outcome routes by the same rules above —
        // notably the hop-URL check, which a provider must not be able to opt out of. Re-entering
        // 'raw' is refused so a hook can't loop.
        "raw" -> dom.raw
            ?.let { spec.parseRaw(it) }
            ?.takeIf { it.page != "raw" }
            ?.let { routeDom(it) }
            ?: RouteResult.Unparsed
        "notFound" -> RouteResult.NotFound
        "loginWall" -> RouteResult.LoginWall
        "challenge" -> RouteResult.Challenge
        else -> RouteResult.Unparsed
    }
}

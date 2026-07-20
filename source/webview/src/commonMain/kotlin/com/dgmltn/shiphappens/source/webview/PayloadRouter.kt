package com.dgmltn.shiphappens.source.webview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One message posted from the in-page JS bridge to Kotlin. */
@Serializable
data class BridgePayload(val kind: String, val url: String? = null, val body: String)

/** Parsed body of a `kind == "dom"` payload (the extraction runner's output). */
@Serializable
data class DomExtraction(val page: String, val url: String? = null, val tracking: ScrapedTracking? = null)

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
                when (dom.page) {
                    "ok" -> dom.tracking?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
                    "goto" ->
                        if (dom.url != null && isAllowedHopUrl(dom.url, spec.cookieDomain)) {
                            RouteResult.Goto(dom.url, dom.tracking)
                        } else {
                            // Bad hop target: salvage the coarse tracking if the extractor sent one.
                            dom.tracking?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
                        }
                    "notFound" -> RouteResult.NotFound
                    "loginWall" -> RouteResult.LoginWall
                    "challenge" -> RouteResult.Challenge
                    else -> RouteResult.Unparsed
                }
            }
            else -> RouteResult.Unparsed
        }
    }
}

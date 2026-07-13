package com.shiphappens.source.webview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One message posted from the in-page JS bridge to Kotlin. */
@Serializable
data class BridgePayload(val kind: String, val url: String? = null, val body: String)

/** Parsed body of a `kind == "dom"` payload (the extraction runner's output). */
@Serializable
data class DomExtraction(val page: String, val tracking: ScrapedTracking? = null)

sealed interface RouteResult {
    data class Tracking(val tracking: ScrapedTracking) : RouteResult
    data object NotFound : RouteResult
    data object LoginWall : RouteResult
    data object Challenge : RouteResult
    data object Unparsed : RouteResult
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

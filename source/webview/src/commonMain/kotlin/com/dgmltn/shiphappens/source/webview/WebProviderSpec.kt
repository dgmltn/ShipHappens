package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.Carrier

/**
 * Everything provider-specific about scraping one carrier's website. Adding a new provider
 * (FedEx, USPS, DHL...) means writing one of these plus a thin WebViewBasedSource subclass —
 * no new scraping machinery.
 *
 * JS fields are small, versioned-in-code scripts:
 *  - [isLoggedInJs]: expression evaluating to a boolean in page context.
 *  - [extractionJs]: a JS *function expression* `function(){...}` returning
 *    `{page: 'ok'|'goto'|'raw'|'notFound'|'loginWall'|'challenge'|'empty', tracking: <canonical
 *    ScrapedTracking>}`. Prefer `'raw'` + [parseRaw] over classifying in JS: commonTest has no JS
 *    engine, so decisions made in the blob can only be checked by scraping on a device.
 *  - [apiUrlPatterns]: JS-compatible regex source strings matched against fetch/XHR URLs.
 */
class WebProviderSpec(
    val sourceId: String,
    val carrier: Carrier,
    val cookieDomain: String,
    val trackingUrl: (trackingNumber: String) -> String,
    val loginUrl: String,
    val isLoggedInJs: String,
    val apiUrlPatterns: List<String>,
    val challengeMarkers: List<String>,
    val extractionJs: String,
    val parseApi: (url: String?, body: String) -> ScrapedTracking?,
    /**
     * Turns a `page:'raw'` extraction's verbatim page text into an outcome, so status vocabulary
     * and card-selection rules live in unit-testable Kotlin instead of [extractionJs] (see [DomRaw]).
     * Returning null, or another `page:'raw'`, routes to Unparsed.
     */
    val parseRaw: (DomRaw) -> DomExtraction? = { null },
) {
    /** Origin rules for androidx.webkit's WebMessageListener / document-start script APIs. */
    fun allowedOriginRules(): List<String> = listOf("https://*.$cookieDomain", "https://$cookieDomain")
}

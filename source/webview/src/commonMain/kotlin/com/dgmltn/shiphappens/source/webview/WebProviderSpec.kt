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
 *    `{page: 'ok'|'notFound'|'loginWall'|'challenge'|'empty', tracking: <canonical ScrapedTracking>}`.
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
) {
    /** Origin rules for androidx.webkit's WebMessageListener / document-start script APIs. */
    fun allowedOriginRules(): List<String> = listOf("https://*.$cookieDomain", "https://$cookieDomain")
}

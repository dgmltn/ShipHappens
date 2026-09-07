package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.StatusVocabulary
import com.dgmltn.shiphappens.source.webview.TrackerPageRules
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage

// UPS wordings on top of the shared vocabulary. "action" is deliberately bare — the old JS
// matched it bare ("Action Needed"), the API says "action required"; bare covers both.
// "label"/"not received" are the API's label-stage phrasing, "order processed" the DOM banner's
// ("Order Processed: Ready for UPS" — UPS-local because on USPS pages bare "processed" is a
// transit scan). Shared by the API parser and the DOM fallback: one vocabulary, tested once.
internal val UPS_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        exception = listOf("action"),
        labelCreated = listOf("label", "not received", "order processed"),
        shipped = listOf("origin scan", "pickup"),
    ),
)

// Tracker page: the DOM layer is UPS's coarse fallback (API capture is primary). The not-found
// wording is verbatim from the JS blob's original decision (moved to Kotlin 2026-08-20).
internal val UPS_PAGE = TrackerPageRules(
    vocabulary = UPS_VOCABULARY,
    notFound = listOf("""tracking number.{0,40}(invalid|not found|couldn.t locate)"""),
)

// DOM *reader* fallback (API capture is UPS's primary layer). Selector constants are validated
// against the live page during manual QA — unchanged by the 2026-08-19 raw-reader conversion,
// which only moved the status bucketing out of this blob into Kotlin where unit tests reach it
// (UPS_VOCABULARY / UPS_PAGE above). The blob finds the status element and returns its text; it
// decides nothing.
private val UPS_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  if (/log in|sign in to view/i.test(text) && !/track/i.test(document.title)) return {page: 'loginWall'};
  var statusEl = document.querySelector('#stApp_txtPackageStatus, [id*="PackageStatus"], .ups-tracking_status');
  return {page: 'raw', raw: {kind: 'tracker',
    statusText: statusEl ? statusEl.textContent.replace(/\s+/g, ' ').trim() : null,
    pageText: text.replace(/\s+/g, ' ').slice(0, 400)}};
}
""".trimIndent()

private val UPS_IS_LOGGED_IN_JS = """
(function() {
  try {
    if (document.querySelector('#ups-header a[href*="logout"], [data-testid*="account"], .ups-header_avatar')) return true;
    return /welcome,|my profile|sign out/i.test((document.body && document.body.innerText) || '');
  } catch (e) { return false; }
})()
""".trimIndent()

val UpsWebSpec = WebProviderSpec(
    sourceId = "ups",
    carrier = WellKnownCarriers.UPS,
    cookieDomain = "ups.com",
    trackingUrl = { "https://www.ups.com/track?loc=en_US&tracknum=$it" },
    loginUrl = "https://www.ups.com/lasso/signin?loc=en_US",
    isLoggedInJs = UPS_IS_LOGGED_IN_JS,
    apiUrlPatterns = listOf(""".*ups\.com/track/api/Track/GetStatus.*"""),
    challengeMarkers = listOf("verify you are a human", "unusual activity", "Pardon Our Interruption", "Access Denied"),
    extractionJs = UPS_EXTRACTION_JS,
    parseApi = { _, body -> UpsApiParser.parse(body) },
    parseRaw = { resolveTrackerPage(it, UPS_PAGE) },
)

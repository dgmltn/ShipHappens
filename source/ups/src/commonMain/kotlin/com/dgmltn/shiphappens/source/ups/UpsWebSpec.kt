package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.webview.BridgeScripts
import com.dgmltn.shiphappens.source.webview.LoginRecipe
import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.StatusVocabulary
import com.dgmltn.shiphappens.source.webview.TrackerPageRules
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.webSourceModule
import org.koin.core.module.Module

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
function(page) {
  if (/log in|sign in to view/i.test(page.bodyText) && !/track/i.test(document.title)) return {page: 'loginWall'};
  return page.raw('tracker', {statusText: page.text('#stApp_txtPackageStatus', '[id*="PackageStatus"]', '.ups-tracking_status')});
}
""".trimIndent()

val UpsWebSpec = WebProviderSpec(
    carrier = WellKnownCarriers.UPS,
    cookieDomain = "ups.com",
    trackingUrl = { "https://www.ups.com/track?loc=en_US&tracknum=${normalizeTracking(it)}" },
    // Greeting/sign-out markers on ups.com chrome — validated in live QA.
    login = LoginRecipe(
        "https://www.ups.com/lasso/signin?loc=en_US",
        BridgeScripts.loggedInProbe(
            listOf("#ups-header a[href*=\"logout\"]", "[data-testid*=\"account\"]", ".ups-header_avatar"),
            "welcome,|my profile|sign out",
        ),
    ),
    apiUrlPatterns = listOf(""".*ups\.com/track/api/Track/GetStatus.*"""),
    extraChallengeMarkers = listOf("unusual activity", "Pardon Our Interruption"),
    extractionJs = UPS_EXTRACTION_JS,
    parseApi = { _, body -> UpsApiParser.parse(body) },
    parseRaw = { resolveTrackerPage(it, UPS_PAGE) },
)

val upsSourceModule: Module = webSourceModule(UpsWebSpec)

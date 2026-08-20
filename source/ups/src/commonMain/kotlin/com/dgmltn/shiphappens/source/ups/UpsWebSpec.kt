package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.webview.WebProviderSpec

// DOM *reader* fallback (API capture is UPS's primary layer). Selector constants are validated
// against the live page during manual QA — unchanged by the 2026-08-19 raw-reader conversion,
// which only moved the status bucketing out of this blob into UpsPageLogic where unit tests
// reach it. The blob finds the status element and returns its text; it decides nothing.
private val UPS_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  if (/tracking number.{0,40}(invalid|not found|couldn.t locate)/i.test(text)) return {page: 'notFound'};
  if (/log in|sign in to view/i.test(text) && !/track/i.test(document.title)) return {page: 'loginWall'};
  var statusEl = document.querySelector('#stApp_txtPackageStatus, [id*="PackageStatus"], .ups-tracking_status');
  if (!statusEl) return {page: 'empty'};
  return {page: 'raw', raw: {kind: 'tracker', statusText: statusEl.textContent.replace(/\s+/g, ' ').trim()}};
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
    parseRaw = ::parseUpsRaw,
)

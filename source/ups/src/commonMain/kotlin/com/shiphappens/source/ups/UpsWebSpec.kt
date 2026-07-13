package com.shiphappens.source.ups

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.webview.WebProviderSpec

// DOM fallback extractor. Selector constants are validated against the live page during
// manual QA (Task 11) — the structure (page states + canonical tracking JSON) is what the
// rest of the pipeline depends on, and that is locked by PayloadRouter/canonical-model tests.
private val UPS_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  if (/tracking number.{0,40}(invalid|not found|couldn.t locate)/i.test(text)) return {page: 'notFound'};
  if (/log in|sign in to view/i.test(text) && !/track/i.test(document.title)) return {page: 'loginWall'};
  var statusEl = document.querySelector('#stApp_txtPackageStatus, [id*="PackageStatus"], .ups-tracking_status');
  if (!statusEl) return {page: 'empty'};
  var raw = statusEl.textContent.trim().toLowerCase();
  var status =
    raw.indexOf('out for delivery') >= 0 ? 'OUT_FOR_DELIVERY' :
    raw.indexOf('delivered') >= 0 ? 'DELIVERED' :
    raw.indexOf('exception') >= 0 || raw.indexOf('action') >= 0 ? 'EXCEPTION' :
    raw.indexOf('label') >= 0 || raw.indexOf('order processed') >= 0 ? 'LABEL_CREATED' :
    raw.indexOf('on the way') >= 0 || raw.indexOf('in transit') >= 0 ? 'IN_TRANSIT' : 'UNKNOWN';
  return {page: 'ok', tracking: {status: status, events: []}};
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
)

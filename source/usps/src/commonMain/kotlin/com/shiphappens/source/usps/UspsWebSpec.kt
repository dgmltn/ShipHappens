package com.shiphappens.source.usps

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.webview.WebProviderSpec

// DOM extractor — the RELIABLE layer for USPS (inverse of UPS, where API capture is primary):
// the tools.usps.com page renders the full event history in the DOM. Selector constants are
// validated against the live page during device QA (Akamai blocks off-device inspection);
// the returned JSON structure is what PayloadRouter/canonical-model tests lock down.
private val USPS_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  if (/status not available|could not locate the tracking information/i.test(text)) return {page: 'notFound'};
  // Priority chain, NOT a comma list: querySelector('a, b') returns the first match in
  // DOCUMENT order, and on the live page ancestor wrappers (current-tracking-status-wrapper)
  // precede the precise node — their concatenated text misclassified a delivered package as
  // OUT_FOR_DELIVERY (live QA 2026-07-15). .tb-status is unique on the live page (inside the
  // current .tb-step); .statusSummaryText is the "Latest Update" banner sentence.
  var statusEl = document.querySelector('.tb-status')
    || document.querySelector('.delivery_status h2')
    || document.querySelector('.statusSummaryText');
  if (!statusEl) return {page: 'empty'};
  function classify(raw) {
    var t = (raw || '').toLowerCase();
    if (t.indexOf('out for delivery') >= 0) return 'OUT_FOR_DELIVERY';
    if (t.indexOf('delivered') >= 0) return 'DELIVERED';
    if (t.indexOf('alert') >= 0 || t.indexOf('attempted') >= 0 || t.indexOf('notice left') >= 0 || t.indexOf('return') >= 0 || t.indexOf('held') >= 0) return 'EXCEPTION';
    if (t.indexOf('label created') >= 0 || t.indexOf('pre-shipment') >= 0 || t.indexOf('awaiting item') >= 0) return 'LABEL_CREATED';
    if (t.indexOf('accepted') >= 0 || t.indexOf('picked up') >= 0 || t.indexOf('possession') >= 0) return 'SHIPPED';
    if (t.indexOf('in transit') >= 0 || t.indexOf('departed') >= 0 || t.indexOf('arrived') >= 0 || t.indexOf('moving through') >= 0 || t.indexOf('processed') >= 0) return 'IN_TRANSIT';
    return 'UNKNOWN';
  }
  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  var events = [];
  var steps = document.querySelectorAll('#trackingHistory .tb-step, .tracking-progress-bar-status-container .tb-step');
  for (var i = 0; i < steps.length; i++) {
    var dateText = clean(steps[i].querySelector('.tb-date'));
    var desc = clean(steps[i].querySelector('.tb-status-detail, .tb-status'));
    if (!dateText || !desc) continue;
    var t = Date.parse(dateText);
    if (isNaN(t)) continue;
    var st = classify(desc);
    events.push({
      timestamp: new Date(t).toISOString(),
      description: desc,
      location: clean(steps[i].querySelector('.tb-location')),
      status: st === 'UNKNOWN' ? null : st
    });
  }
  events.reverse();  // page lists newest first; canonical order is ascending
  var newestLoc = null, newestT = -1;
  for (var j = 0; j < events.length; j++) {
    var et = Date.parse(events[j].timestamp);
    if (events[j].location && et > newestT) { newestT = et; newestLoc = events[j].location; }
  }
  var etaDate = null;
  var etaText = clean(document.querySelector('.expected_delivery .date, [class*="expected-delivery"], .eta_info'));
  if (etaText) {
    var d = Date.parse(etaText);
    if (!isNaN(d)) {
      var dd = new Date(d);
      etaDate = dd.getFullYear() + '-' + ('0' + (dd.getMonth() + 1)).slice(-2) + '-' + ('0' + dd.getDate()).slice(-2);
    }
  }
  return {page: 'ok', tracking: {
    status: classify(statusEl.textContent),
    etaDate: etaDate,
    location: newestLoc,
    events: events
  }};
}
""".trimIndent()

// Greeting/sign-out markers on usps.com chrome — validated in live QA like the selectors above.
private val USPS_IS_LOGGED_IN_JS = """
(function() {
  try {
    if (document.querySelector('a[href*="logout"], a[href*="LogOutAction"], [class*="sign-out"]')) return true;
    return /sign out|welcome,/i.test((document.body && document.body.innerText) || '');
  } catch (e) { return false; }
})()
""".trimIndent()

val UspsWebSpec = WebProviderSpec(
    sourceId = "usps",
    carrier = WellKnownCarriers.USPS,
    cookieDomain = "usps.com",
    trackingUrl = { "https://tools.usps.com/tracking/$it" },
    loginUrl = "https://reg.usps.com/entreg/LoginAction_input",
    isLoggedInJs = USPS_IS_LOGGED_IN_JS,
    // Deliberately broad (Akamai blocked off-device endpoint capture); non-tracking captures
    // are rejected by UspsApiParser returning null. Tightened during live QA.
    apiUrlPatterns = listOf(""".*tools\.usps\.com/.*[Tt]rack.*"""),
    challengeMarkers = listOf("Access Denied", "Reference #", "verify you are a human", "unusual activity"),
    extractionJs = USPS_EXTRACTION_JS,
    parseApi = { _, body -> UspsApiParser.parse(body) },
)

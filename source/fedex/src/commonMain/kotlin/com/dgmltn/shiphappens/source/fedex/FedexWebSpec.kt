package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import kotlin.time.Duration.Companion.seconds

// DOM *reader* — the ONLY layer for FedEx, and deliberately so: injecting the fetch/XHR capture
// hooks makes fedex.com's bot defense fail every tracking lookup onto its "system-error" page
// (live QA 2026-08-19), so this spec declares no apiUrlPatterns and WebSessions skips the hooks
// entirely. The reader finds elements and returns their text; FedexPageLogic classifies in
// Kotlin. Selectors were captured live on 2026-08-19/20 from a FedEx Ground package across its
// in-transit and delivered states:
//   .phase3-progress-bar__active-label        "On the way"            (absent once delivered)
//   [data-test-id="delivery-date-header"]     "ESTIMATED DELIVERY DATE" / "DELIVERED"
//   [data-test-id="delivery-date-text"]       "Thursday8/20/2026 Between 10:10 AM - 2:10 PM" /
//                                             "Thursday8/20/2026 at 1:48 pm" (delivered)
//   .phase3-view__current-location            "Currently in Sacramento, CA" (absent once delivered)
// Every bail-out carries why/probe/textHead diagnostics for the ScrapeTracer.
private val FEDEX_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  var href = location.href;
  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  // Two live not-found wordings (QA 2026-08-19): /fedextrack/no-results-found says "The tracking
  // number you entered can't be found right now"; the system-error page says "We can't find that
  // tracking number. Please check with the shipper".
  if (/tracking number.{0,80}can.t be found|can.t find (that|this) tracking number|no record of this tracking|please check (the number )?with the shipper/i.test(text)) return {page: 'notFound'};
  // Priority chain: the progress-bar label is the headline while moving, but a delivered page
  // drops the progress bar entirely and flips the delivery-date eyebrow to "DELIVERED" (live
  // capture 2026-08-20) — that eyebrow is the fallback. On moving pages it reads "ESTIMATED
  // DELIVERY DATE", which classifies to nothing, so the fallback can't misreport transit.
  var statusText = clean(document.querySelector('.phase3-progress-bar__active-label'))
    || clean(document.querySelector('[class*="progress-bar__active-label"]'))
    || clean(document.querySelector('[data-test-id="delivery-date-header"]'));
  var etaText = clean(document.querySelector('[data-test-id="delivery-date-text"]'))
    || clean(document.querySelector('.phase3-view__delivery-date-embed, [class*="delivery-date-embed"]'));
  var locationText = clean(document.querySelector('.phase3-view__current-location, [class*="current-location"]'));
  if (statusText || etaText) {
    var d = new Date();
    var todayIso = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
    return {page: 'raw', raw: {kind: 'tracker', statusText: statusText, etaText: etaText,
                               locationText: locationText, todayIso: todayIso}};
  }
  function probe() {
    var sel = ['.phase3-view', '[class*="progress-bar" i]', '[data-test-id]', '[class*="delivery" i]', 'h1'];
    var out = {};
    for (var p = 0; p < sel.length; p++) {
      try { out[sel[p]] = document.querySelectorAll(sel[p]).length; } catch (e) { out[sel[p]] = -1; }
    }
    return out;
  }
  return {page: 'empty', why: 'noStatusHeadline', url: href,
          textHead: text.replace(/\s+/g, ' ').slice(0, 300), probe: probe()};
}
""".trimIndent()

// Greeting/sign-out markers on fedex.com chrome — the logged-out page shows "Sign Up or Log In"
// (QA 2026-08-19); sign-out wording is best-effort until a logged-in QA pass.
private val FEDEX_IS_LOGGED_IN_JS = """
(function() {
  try {
    if (document.querySelector('a[href*="logout"], a[href*="signout"], [data-test-id*="logout" i]')) return true;
    return /sign out|log out\b/i.test((document.body && document.body.innerText) || '');
  } catch (e) { return false; }
})()
""".trimIndent()

val FedexWebSpec = WebProviderSpec(
    sourceId = "fedex",
    carrier = WellKnownCarriers.FEDEX,
    cookieDomain = "fedex.com",
    // The SPA client-routes this through /wtrk/track/ and back to /fedextrack/?trknbr=...&trkqual=...
    trackingUrl = { "https://www.fedex.com/fedextrack/?trknbr=$it" },
    loginUrl = "https://www.fedex.com/secure-login/en-us/",
    isLoggedInJs = FEDEX_IS_LOGGED_IN_JS,
    // Empty is load-bearing: no patterns => WebSessions injects no fetch/XHR hooks (see header).
    apiUrlPatterns = emptyList(),
    challengeMarkers = listOf("Access Denied", "Reference #", "verify you are a human", "unusual activity"),
    extractionJs = FEDEX_EXTRACTION_JS,
    parseApi = { _, _ -> null },
    parseRaw = ::parseFedexRaw,
    // Measured live: 3s after onPageFinished the SPA is still an app shell; ~10s in it has
    // rendered the tracking view. 12s is the validated capture point.
    settle = 12.seconds,
)

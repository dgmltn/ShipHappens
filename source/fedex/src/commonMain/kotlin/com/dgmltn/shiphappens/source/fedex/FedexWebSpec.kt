package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.webview.BridgeScripts
import com.dgmltn.shiphappens.source.webview.LoginRecipe
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.webSourceModule
import org.koin.core.module.Module
import kotlin.time.Duration.Companion.seconds

// DOM *reader* — the ONLY layer for FedEx, and deliberately so: injecting the fetch/XHR capture
// hooks makes fedex.com's bot defense fail every tracking lookup onto its "system-error" page
// (live QA 2026-08-19), so this spec declares no apiUrlPatterns and WebSessions skips the hooks
// entirely. The reader finds elements and returns their text (page-state wording included, as
// pageText); FedexPageLogic holds only the vocabulary, not-found copy, and location hook — the
// shared resolver (resolveTrackerPage) decides the rest. Selectors were captured live 2026-08-19/21 from
// a FedEx Ground package across its in-transit, delivered, and travel-history states:
//   .phase3-progress-bar__active-label        "On the way"            (absent once delivered)
//   [data-test-id="delivery-date-header"]     "ESTIMATED DELIVERY DATE" / "DELIVERED"
//   [data-test-id="delivery-date-text"]       "Thursday8/20/2026 Between 10:10 AM - 2:10 PM" /
//                                             "Thursday8/20/2026 at 1:48 pm" (delivered)
//   .phase3-view__current-location            "Currently in Sacramento, CA" (absent once delivered)
//   tr.travel-history-table__row              one per day: td[0] "Tuesday, 8/18/26", td[1] holds
//     .travel-history__scan-event             per-event triple of grid children, fixed order:
//                                             time ("4:16 PM"), description ("Picked up"),
//                                             location ("SOUTH SAN FRANCISCO, CA", may be empty)
//
// The travel history lives behind "View more details" — a same-URL client-side route (no href,
// found only by its text), so this extractor is ASYNC (see BridgeScripts.extractionRunner): read
// the summary, click the control, give the SPA a beat to render, read the rows, finish once.
// A restored session can land directly on the details view (live 2026-08-21), so rows are read
// first and the click only happens when they aren't there. Every fallback still finishes with
// the summary fields, so a failed click is never worse than the pre-history behavior.
private val FEDEX_EXTRACTION_JS = """
function(finish) {
  var text = (document.body && document.body.innerText) || '';
  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  var statusText = clean(document.querySelector('.phase3-progress-bar__active-label'))
    || clean(document.querySelector('[class*="progress-bar__active-label"]'))
    || clean(document.querySelector('[data-test-id="delivery-date-header"]'));
  var etaText = clean(document.querySelector('[data-test-id="delivery-date-text"]'))
    || clean(document.querySelector('.phase3-view__delivery-date-embed, [class*="delivery-date-embed"]'));
  var locationText = clean(document.querySelector('.phase3-view__current-location, [class*="current-location"]'));
  var d = new Date();
  var todayIso = d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  function readTravelHistory() {
    var events = [];
    var rows = document.querySelectorAll('tr.travel-history-table__row');
    for (var r = 0; r < rows.length; r++) {
      var dateText = clean(rows[r].querySelector('td'));
      var evs = rows[r].querySelectorAll('.travel-history__scan-event');
      for (var i = 0; i < evs.length; i++) {
        var kids = evs[i].children;
        var desc = kids[1] ? clean(kids[1]) : null;
        if (!desc) continue;
        events.push({whenText: (dateText || '') + ' ' + (kids[0] ? clean(kids[0]) : ''),
                     description: desc,
                     location: (kids[2] && clean(kids[2])) || null});
      }
    }
    return events;
  }
  function result(events) {
    return {page: 'raw', raw: {kind: 'tracker', statusText: statusText, etaText: etaText,
                               locationText: locationText, todayIso: todayIso,
                               pageText: text.replace(/\s+/g, ' ').slice(0, 400),
                               events: events}};
  }
  var direct = readTravelHistory();
  if (direct.length) return result(direct);
  var control = null;
  var all = document.body.getElementsByTagName('*');
  for (var p = 0; p < all.length; p++) {
    var t = (all[p].textContent || '').replace(/\s+/g, ' ').trim();
    if (t === 'View more details' && all[p].children.length === 0) { control = all[p]; break; }
  }
  if (!control) {
    var res = result([]);
    if (!statusText && !etaText) {
      // Selector-drift diagnostics for the tracer; the DomExtraction decoder ignores these keys.
      res.why = 'noStatusHeadline';
      res.probe = {};
      var sel = ['.phase3-view', '[class*="progress-bar" i]', '[data-test-id]', 'tr.travel-history-table__row', 'h1'];
      for (var q = 0; q < sel.length; q++) {
        try { res.probe[sel[q]] = document.querySelectorAll(sel[q]).length; } catch (e) { res.probe[sel[q]] = -1; }
      }
    }
    return res;
  }
  control.click();
  setTimeout(function() { finish(result(readTravelHistory())); }, 2000);
  return undefined;
}
""".trimIndent()

val FedexWebSpec = WebProviderSpec(
    carrier = WellKnownCarriers.FEDEX,
    cookieDomain = "fedex.com",
    // The SPA client-routes this through /wtrk/track/ and back to /fedextrack/?trknbr=...&trkqual=...
    trackingUrl = { "https://www.fedex.com/fedextrack/?trknbr=$it" },
    // Greeting/sign-out markers on fedex.com chrome — the logged-out page shows "Sign Up or Log In"
    // (QA 2026-08-19); sign-out wording is best-effort until a logged-in QA pass.
    login = LoginRecipe(
        "https://www.fedex.com/secure-login/en-us/",
        BridgeScripts.loggedInProbe(
            listOf("a[href*=\"logout\"]", "a[href*=\"signout\"]", "[data-test-id*=\"logout\" i]"),
            "sign out|log out\\b",
        ),
    ),
    // Empty is load-bearing: no patterns => WebSessions injects no fetch/XHR hooks (see header).
    apiUrlPatterns = emptyList(),
    extraChallengeMarkers = listOf("Reference #", "unusual activity"),
    extractionJs = FEDEX_EXTRACTION_JS,
    parseRaw = { resolveTrackerPage(it, FEDEX_PAGE) },
    // Measured live: 3s after onPageFinished the SPA is still an app shell; ~10s in it has
    // rendered the tracking view. 12s is the validated capture point (plus 2s more in-extractor
    // after the details click).
    settle = 12.seconds,
)

val fedexSourceModule: Module = webSourceModule(FedexWebSpec)

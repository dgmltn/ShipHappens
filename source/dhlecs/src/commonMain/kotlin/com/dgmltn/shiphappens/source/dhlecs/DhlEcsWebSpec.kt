package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.StatusVocabulary
import com.dgmltn.shiphappens.source.webview.TrackerPageRules
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.webSourceModule
import org.koin.core.module.Module

// webtrack.dhlecs.com is a React SPA (empty #root shell); its POST to
// api.dhlecs.com/webtrack/v4/tracking is the primary data layer (captured via apiUrlPatterns,
// parsed in DhlEcsApiParser). This DOM extractor is only the fallback: read the results list's
// status cell, or report diagnostics. Class names ('list-status', 'shipment-status',
// 'no-result-found-msg') come from the production bundle (recon 2026-09-03); off-device fetches
// only see the SPA shell, so they're validated on-device during QA — every bail-out carries
// why/probe for the tracer, AMZL-style.
private val DHLECS_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  var pageText = text.replace(/\s+/g, ' ').slice(0, 400);
  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  // .list-status only (the results list's status cell) — NOT [class*="shipment-status"]: on the
  // details route that matched a CONTAINER whose text concatenates the progress rail's static
  // step labels ("Notified En Route Delivered"), and the rail's "Delivered" overwrote a
  // label-only package (live QA 2026-09-03). Kotlin refuses multi-stage text too, but the
  // precise-node-or-nothing rule is the same lesson as USPS's 2026-07-15 ancestor-wrapper bug.
  var statusEl = document.querySelector('.list-status')
    || document.querySelector('main h1, h1');
  var statusText = clean(statusEl);
  if (statusText) return {page: 'raw', raw: {kind: 'tracker', statusText: statusText, pageText: pageText}};
  function probe() {
    var sel = ['.list-status', '[class*="shipment-status"]', '.no-result-found-msg', 'h1', '#root *'];
    var out = {};
    for (var p = 0; p < sel.length; p++) {
      try { out[sel[p]] = document.querySelectorAll(sel[p]).length; } catch (e) { out[sel[p]] = -1; }
    }
    return out;
  }
  // Raw (not 'empty') so Kotlin can still classify not-found wording from pageText; the
  // decoder ignores why/probe, the tracer logs them verbatim.
  return {page: 'raw', raw: {kind: 'tracker', pageText: pageText},
          why: 'noStatusHeadline', url: location.href, probe: probe()};
}
""".trimIndent()

// The wordings come from webtrack's en-US locale file (recon 2026-09-03), which enumerates
// the full event vocabulary (`id_99`…`id_803`); the API's SCREAMING primaryEventDescription
// values are the same strings uppercased, so one vocabulary serves both layers. The
// "Undelivered" negation (event 636) is handled by the shared vocabulary's negated-delivered lane.
internal val DHLECS_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        labelCreated = listOf("electronic notification"),
        shipped = listOf("pick up", "accepted", "received by carrier"),
        inTransit = listOf(
            "arrival", "en route", "processed", "departure", "forwarded", "sorted",
            "tendered", "manifested", "transport", "customs clearance", "cleared customs",
        ),
        exception = listOf(
            "refused", "undeliverable", "damage", "missent", "mis-shipped", "dead letter",
            "no such number", "insufficient", "unclaimed", "vacant", "addressee unknown",
            "not possible", "recalled",
        ),
    ),
)

// Coarse DOM fallback for when the API capture misses. A container grab that swallowed the
// progress rail names every stage at once — the shared resolver refuses such a headline
// (Unparsed, nothing persisted) rather than classify it; live QA 2026-09-03 saw the rail
// overwrite a label-only package as DELIVERED through the scrape-on-view path. Not-found copy is
// webtrack's en-US no_records string (locale recon 2026-09-03).
internal val DHLECS_PAGE = TrackerPageRules(
    vocabulary = DHLECS_VOCABULARY,
    notFound = listOf("""no results? found|confirm the accuracy of your tracking number"""),
)

// The tracking API is anonymous (a plain cross-origin POST, no cookies or keys — recon
// 2026-09-03); login is never required, so login is null and the probe falls back to
// NEVER_LOGGED_IN_JS.
val DhlEcsWebSpec = WebProviderSpec(
    carrier = WellKnownCarriers.DHL_ECOMMERCE,
    cookieDomain = "webtrack.dhlecs.com",
    trackingUrl = { "https://webtrack.dhlecs.com/orders?trackingNumber=${normalizeTracking(it)}" },
    // The SPA calls api.dhlecs.com cross-host; the in-page fetch hook still sees it.
    apiUrlPatterns = listOf(""".*api\.dhlecs\.com/webtrack/v4/tracking.*"""),
    extraChallengeMarkers = listOf("Reference #", "Request unsuccessful"),
    extractionJs = DHLECS_EXTRACTION_JS,
    parseApi = { _, body -> DhlEcsApiParser.parse(body) },
    parseRaw = { resolveTrackerPage(it, DHLECS_PAGE) },
)

val dhlEcsSourceModule: Module = webSourceModule(DhlEcsWebSpec)

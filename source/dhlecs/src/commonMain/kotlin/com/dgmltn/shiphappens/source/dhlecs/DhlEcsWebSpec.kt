package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.webview.WebProviderSpec

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
  var statusEl = document.querySelector('.list-status')
    || document.querySelector('[class*="shipment-status"], [class*="delivery-status"]')
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

// The tracking API is anonymous (a plain cross-origin POST, no cookies or keys — recon
// 2026-09-03); login is never required, so login state is a constant false and the loginUrl
// below is vestigial framework plumbing.
private const val DHLECS_IS_LOGGED_IN_JS = "(function() { return false; })()"

val DhlEcsWebSpec = WebProviderSpec(
    sourceId = "dhlecs",
    carrier = WellKnownCarriers.DHL_ECOMMERCE,
    cookieDomain = "webtrack.dhlecs.com",
    trackingUrl = { "https://webtrack.dhlecs.com/orders?trackingNumber=${normalizeTracking(it)}" },
    loginUrl = "https://webtrack.dhlecs.com/",
    isLoggedInJs = DHLECS_IS_LOGGED_IN_JS,
    // The SPA calls api.dhlecs.com cross-host; the in-page fetch hook still sees it.
    apiUrlPatterns = listOf(""".*api\.dhlecs\.com/webtrack/v4/tracking.*"""),
    challengeMarkers = listOf("Access Denied", "Reference #", "verify you are a human", "Request unsuccessful"),
    extractionJs = DHLECS_EXTRACTION_JS,
    parseApi = { _, body -> DhlEcsApiParser.parse(body) },
    parseRaw = ::parseDhlEcsRaw,
)

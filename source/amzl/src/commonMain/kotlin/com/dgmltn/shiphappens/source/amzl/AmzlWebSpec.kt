package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.webview.WebProviderSpec

// The tracking page is a JS SPA (AmazonShippingRecipientApp); its /api/tracker/ XHR is the
// primary data layer (captured via apiUrlPatterns, parsed in AmzlApiParser). This DOM extractor
// is only the fallback: classify not-found, read the status headline, or report diagnostics.
// Selector constants and not-found wording are validated on-device during QA — off-device recon
// (2026-08-11) only saw the SPA shell, so every bail-out carries why/probe/detail for the tracer.
private val AMZL_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  var href = location.href;
  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  if (/couldn.t find|can.t find|unable to find|invalid tracking|no longer available/i.test(text)) return {page: 'notFound'};
  var statusEl = document.querySelector('#primaryStatus')
    || document.querySelector('[class*="pt-status"], [class*="trackingStatus"], [class*="status-main"]')
    || document.querySelector('main h1, h1');
  var statusText = clean(statusEl);
  if (statusText) return {page: 'raw', raw: {kind: 'tracker', statusText: statusText}};
  function probe() {
    var sel = ['#primaryStatus', '[class*="status"]', 'main h1', 'h1', '[data-testid]'];
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

// The tracker API is anonymous (accessType ANONYMOUS_PACKAGE_ACCESS); login is never required,
// so login state is a constant false and the loginUrl below is vestigial framework plumbing.
private const val AMZL_IS_LOGGED_IN_JS = "(function() { return false; })()"

val AmzlWebSpec = WebProviderSpec(
    sourceId = "amzl",
    carrier = WellKnownCarriers.AMAZON_LOGISTICS,
    // track.amazon.com, NOT amazon.com: Android's CookieManager is app-global (an existing
    // amazon.com session reaches this subdomain regardless), and scoping the spec here keeps
    // an AMZL sign-out's clearForDomain from expiring the Amazon orders login.
    cookieDomain = "track.amazon.com",
    trackingUrl = { raw ->
        val tba = normalizeTracking(raw)
        "https://track.amazon.com/tracking/$tba?trackingId=$tba"
    },
    loginUrl = "https://www.amazon.com/gp/sign-in.html",
    isLoggedInJs = AMZL_IS_LOGGED_IN_JS,
    apiUrlPatterns = listOf(""".*track\.amazon\.com/api/tracker/.*"""),
    challengeMarkers = listOf(
        "Enter the characters you see",
        "Type the characters you see",
        "not a robot",
        "automated access to Amazon data",
    ),
    extractionJs = AMZL_EXTRACTION_JS,
    parseApi = { _, body -> AmzlApiParser.parse(body) },
    parseRaw = ::parseAmzlRaw,
)

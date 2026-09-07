package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.StatusVocabulary
import com.dgmltn.shiphappens.source.webview.TrackerPageRules
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage

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
  var statusEl = document.querySelector('#primaryStatus')
    || document.querySelector('[class*="pt-status"], [class*="trackingStatus"], [class*="status-main"]')
    || document.querySelector('main h1, h1');
  var statusText = clean(statusEl);
  var pageText = text.replace(/\s+/g, ' ').slice(0, 400);
  if (statusText) return {page: 'raw', raw: {kind: 'tracker', statusText: statusText, pageText: pageText}};
  function probe() {
    var sel = ['#primaryStatus', '[class*="status"]', 'main h1', 'h1', '[data-testid]'];
    var out = {};
    for (var p = 0; p < sel.length; p++) {
      try { out[sel[p]] = document.querySelectorAll(sel[p]).length; } catch (e) { out[sel[p]] = -1; }
    }
    return out;
  }
  // Raw (not 'empty') so Kotlin can still classify not-found wording from pageText; the
  // decoder ignores why/probe, the tracer logs them verbatim.
  return {page: 'raw', raw: {kind: 'tracker', pageText: pageText},
          why: 'noStatusHeadline', url: href, probe: probe()};
}
""".trimIndent()

// One AMZL vocabulary for both layers. The tracker page's headline is English ("Arriving
// Wednesday", "We have your package details"); the API's codes are CamelCase or
// SCREAMING_SNAKE ("CreationConfirmed", "READY_FOR_RECEIVE") and reach the same chain through
// classifyToken, which reads them as words. Only CreationConfirmed/READY_FOR_RECEIVE were
// observed live (2026-08-11); the rest derives from the SPA's milestone string ids
// (swa_rex_intransit, swa_rex_ofd, …) and is confirmed during device QA.
internal val AMZL_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        outForDelivery = listOf("ofd"),
        exception = listOf("undeliverable", "problem", "lost", "damaged", "reject"),
        labelCreated = listOf("package details", "preparing", "creation confirmed", "ready for receive"),
        shipped = listOf("pickup", "package received", "shipped"),
        inTransit = listOf("arriving"),
    ),
)

// Coarse DOM fallback for when the API capture misses. Not-found wording is verbatim from the
// JS blob's original decision (moved to Kotlin 2026-08-20).
internal val AMZL_PAGE = TrackerPageRules(
    vocabulary = AMZL_VOCABULARY,
    notFound = listOf("""no longer available"""),
)

// (The blob's other not-found phrases — "couldn't find", "can't find", "unable to find",
// "invalid tracking" — are now the shared seeds.)

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
    parseRaw = { resolveTrackerPage(it, AMZL_PAGE) },
)

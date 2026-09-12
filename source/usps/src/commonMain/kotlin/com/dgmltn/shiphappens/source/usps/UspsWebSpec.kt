package com.dgmltn.shiphappens.source.usps

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

// USPS wordings on top of the shared vocabulary: tracker-banner phrasing like "moving through
// our network" and acceptance-scan wording. Bare "processed" ("Processed Through Facility") is
// safe as a late-stage keyword because the merged vocabulary runs in one pass — an earlier
// "out for delivery" in the same text still wins. Shared by the API parser and the DOM reader.
internal val USPS_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        exception = listOf("alert"),
        labelCreated = listOf("pre-shipment", "awaiting item"),
        shipped = listOf("accepted", "possession"),
        inTransit = listOf("moving through", "processed"),
    ),
)

// Tracker page: the DOM reader is USPS's RELIABLE layer (inverse of UPS). The ETA banner's
// textContent interleaves tooltip copy with the date ("Tuesday 28 July 2026" split across
// spans, or "Monday, July 28, 2026"); the shared promise chain's month-name parsing is safe
// against that junk because neither tooltip contains a month-name-adjacent number. Not-found
// wording is verbatim from the JS blob's original decision (moved to Kotlin 2026-08-20).
internal val USPS_PAGE = TrackerPageRules(
    vocabulary = USPS_VOCABULARY,
    notFound = listOf("""status not available|could not locate the tracking information"""),
)

// DOM *reader* — the RELIABLE layer for USPS (inverse of UPS, where API capture is primary):
// the tools.usps.com page renders the full event history in the DOM. It finds elements and
// returns their text; it decides nothing. USPS_VOCABULARY/USPS_PAGE above classify statuses and
// parse the ETA banner in Kotlin, where commonTest can reach that logic — this blob has no test
// harness (no JS engine in commonTest), and that's exactly how "On the Way" — the current step's
// headline — shipped unclassifiable on 2026-07-24: a moving package reported UNKNOWN and the
// card kept its stale "Label created". Selector constants are still validated only against the
// live page during device QA (Akamai blocks off-device inspection). Event dates travel verbatim
// as whenText and are resolved by the shared Kotlin.
private val USPS_EXTRACTION_JS = """
function(page) {
  var statusText = page.text('.tb-status', '.delivery_status h2', '.statusSummaryText');
  var events = [];
  var steps = document.querySelectorAll('#trackingHistory .tb-step, .tracking-progress-bar-status-container .tb-step');
  for (var i = 0; i < steps.length; i++) {
    var dateText = page.clean(steps[i].querySelector('.tb-date'));
    // Priority chain, NOT a comma list: querySelector('a, b') returns the first match in
    // DOCUMENT order, and on the live page ancestor wrappers (current-tracking-status-wrapper)
    // precede the precise node — their concatenated text misclassified a delivered package as
    // OUT_FOR_DELIVERY (live QA 2026-07-15). .tb-status is unique on the live page (inside the
    // current .tb-step); .statusSummaryText is the "Latest Update" banner sentence. Same
    // comma-list trap as statusEl, one level down: the current step carries BOTH '.tb-status'
    // ("On the Way", the progress-bar headline) and '.tb-status-detail' ("Departed USPS Facility",
    // the actual event), and the headline comes first in document order. Query separately so the
    // event row always reads the event wording.
    var desc = page.clean(steps[i].querySelector('.tb-status-detail')) || page.clean(steps[i].querySelector('.tb-status'));
    if (!dateText || !desc) continue;
    events.push({whenText: dateText, description: desc, location: page.clean(steps[i].querySelector('.tb-location'))});
  }
  return page.raw('tracker', {
    statusText: statusText,
    // The WHOLE banner is sent, tooltip copy and all, because the Kotlin regexes dig the date
    // and window out of the flattened text (the old '.expected_delivery .date' selector read
    // only the bare day number, 2026-07-24).
    etaText: page.text('.expected_delivery', '[class*="expected-delivery"]', '.eta_info'),
    events: events
  });
}
""".trimIndent()

val UspsWebSpec = WebProviderSpec(
    carrier = WellKnownCarriers.USPS,
    cookieDomain = "usps.com",
    trackingUrl = { "https://tools.usps.com/tracking/${normalizeTracking(it)}" },
    // Greeting/sign-out markers on usps.com chrome — validated in live QA like the selectors above.
    login = LoginRecipe(
        "https://reg.usps.com/entreg/LoginAction_input",
        BridgeScripts.loggedInProbe(
            listOf("a[href*=\"logout\"]", "a[href*=\"LogOutAction\"]", "[class*=\"sign-out\"]"),
            "sign out|welcome,",
        ),
    ),
    // Deliberately broad (Akamai blocked off-device endpoint capture); non-tracking captures
    // are rejected by UspsApiParser returning null. Tightened during live QA.
    apiUrlPatterns = listOf(""".*tools\.usps\.com/.*[Tt]rack.*"""),
    extraChallengeMarkers = listOf("Reference #", "unusual activity"),
    extractionJs = USPS_EXTRACTION_JS,
    parseApi = { _, body -> UspsApiParser.parse(body) },
    parseRaw = { resolveTrackerPage(it, USPS_PAGE) },
)

val uspsSourceModule: Module = webSourceModule(UspsWebSpec)

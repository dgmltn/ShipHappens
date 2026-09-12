package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.webview.LoginRecipe
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.webSourceModule
import org.koin.core.module.Module

// DOM *reader* for BOTH Amazon pages a scrape can visit (design spec §3). It finds elements and
// returns their text; it decides nothing. Both branches emit {page:'raw'} and AmazonPageLogic.kt
// classifies statuses and picks the shipment in Kotlin, where commonTest can reach that logic —
// this blob has no test harness at all (no JS engine in commonTest), and a status-vocabulary bug
// living here reached device QA on 2026-07-19.
//
// Selector constants are still validated only against the live site during device QA (Amazon
// requires login, so off-device recon can't see these pages), which is why every bail-out carries
// why/probe/detail for the tracer to log. Event date headers and the delivery window travel as
// verbatim text (whenText, statusText/etaText) for the shared Kotlin to resolve against
// page.todayIso — see AmazonPageLogic.kt and TrackerPageResolver.kt. Live QA (2026-09-10) found
// the order-details cards renamed to an "OUI"-suffixed component ('shipmentCardOUI'); the class
// selectors stay as fallbacks for older/A-B layouts. Same live QA found the OUI card carries no
// tracker anchor of its own; the single page-level link is used when it's unambiguous.
private val AMAZON_EXTRACTION_JS = """
function(page) {
  var href = location.href;
  if (/\/ap\/signin/.test(href) || document.querySelector('form[name="signIn"], #ap_email, #signInSubmit')) return {page: 'loginWall'};

  if (/progress-tracker|ship-track/.test(href)) {
    var statusText = page.text('#primaryStatus', '[class*="pt-status-main"]', '#shipment-status-container h1', '.promise-slot h1');
    var events = [];
    var container = document.querySelector('#tracking-events-container') || document.body;
    var nodes = container.querySelectorAll('[class*="tracking-event"]');
    var day = null;
    for (var i = 0; i < nodes.length; i++) {
      var cls = '' + nodes[i].className;
      if (cls.indexOf('date-header') >= 0) { day = page.clean(nodes[i]); continue; }
      var msg = page.clean(nodes[i].querySelector('[class*="event-message"], .tracking-event-message'));
      if (!msg || !day) continue;
      var timeText = page.clean(nodes[i].querySelector('[class*="event-time"], .tracking-event-time'));
      events.push({whenText: day + ' ' + (timeText || ''),
                   description: msg,
                   location: page.clean(nodes[i].querySelector('[class*="event-location"], .tracking-event-location'))});
    }
    var r = page.raw('tracker', {
      statusText: statusText,
      etaText: page.text('[class*="promise"]', '#expected-delivery-date'),
      events: events
    });
    if (!statusText && !events.length) {
      r.why = 'trackerNoStatusNoEvents';
      r.detail = 'nodes=' + nodes.length;
      r.probe = page.probe('#primaryStatus', '[class*="tracking-event"]', '[class*="promise"]');
    }
    return r;
  }

  var cards = document.querySelectorAll('[data-component="shipmentCard"], [data-component="shipmentCardOUI"]');
  if (!cards.length) cards = document.querySelectorAll('.shipment, [class*="shipment-info-container"], [data-component="shipments"] .a-box');
  var out = [];
  for (var k = 0; k < cards.length; k++) {
    var head = page.clean(cards[k].querySelector('[data-component="shipmentStatus"], .shipment-top-row, [class*="shipment-status"], h4, h5')) || '';
    if (!head) head = (('' + (cards[k].innerText || '')).split('\n')[0] || '').trim();
    var link = cards[k].querySelector('a[href*="progress-tracker"], a[href*="ship-track"]');
    out.push({head: head, href: (link && link.href) ? link.href : null});
  }
  var borrowedLink = false;
  if (out.length === 1 && !out[0].href) {
    var links = document.querySelectorAll('a[href*="progress-tracker"], a[href*="ship-track"]');
    if (links.length === 1 && links[0].href) { out[0].href = links[0].href; borrowedLink = true; }
  }
  var c = page.raw('cards', {cards: out});
  if (borrowedLink) c.why = 'pageLevelLinkFallback';
  c.links = page.probe('a[href*="progress-tracker"]', 'a[href*="ship-track"]');
  if (!out.length) {
    c.why = 'noShipmentCards';
    c.url = href;
    c.probe = page.probe('.shipment', '[class*="shipment-info-container"]', '[data-component="shipments"] .a-box', '[data-component]', '.a-box', 'a[href*="progress-tracker"]', 'a[href*="ship-track"]', '[data-component="shipmentCardOUI"]');
    var dc = document.querySelectorAll('[data-component]');
    var names = [];
    for (var n = 0; n < dc.length && names.length < 20; n++) {
      var v = dc[n].getAttribute('data-component');
      if (v && names.indexOf(v) < 0) names.push(v);
    }
    c.probe.dataComponents = names;
  }
  return c;
}
""".trimIndent()

// Account-menu greeting on amazon.com chrome — validated in live QA like the selectors above.
private val AMAZON_IS_LOGGED_IN_JS = """
(function() {
  try {
    var nav = document.querySelector('#nav-link-accountList');
    if (nav) return !/sign in/i.test(nav.textContent || '');
    return /sign out/i.test((document.body && document.body.innerText) || '');
  } catch (e) { return false; }
})()
""".trimIndent()

val AmazonWebSpec = WebProviderSpec(
    carrier = WellKnownCarriers.AMAZON,
    cookieDomain = "amazon.com",
    trackingUrl = { raw ->
        // The app normalizes tracking numbers by stripping hyphens; Amazon's orderID param
        // needs the displayed 3-7-7 shape, so re-hyphenate a bare 17-digit id.
        val digits = raw.filter { it.isDigit() }
        val orderId = if (digits.length == 17) {
            "${digits.substring(0, 3)}-${digits.substring(3, 10)}-${digits.substring(10)}"
        } else raw
        "https://www.amazon.com/gp/your-account/order-details?orderID=$orderId"
    },
    login = LoginRecipe("https://www.amazon.com/gp/sign-in.html", AMAZON_IS_LOGGED_IN_JS),
    // DOM-only in v1 (design spec §Decisions): no stable public tracking-JSON vocabulary to
    // target blind. Live-QA ScrapeTracer captures can justify API patterns later.
    apiUrlPatterns = emptyList(),
    parseRaw = ::parseAmazonRaw,
    extraChallengeMarkers = listOf(
        "Enter the characters you see",
        "Type the characters you see",
        "not a robot",
        "automated access to Amazon data",
    ),
    extractionJs = AMAZON_EXTRACTION_JS,
)

val amazonSourceModule: Module = webSourceModule(AmazonWebSpec)

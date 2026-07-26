package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.webview.WebProviderSpec

// DOM *reader* for BOTH Amazon pages a scrape can visit (design spec §3). It finds elements and
// returns their text; it decides nothing. Both branches emit {page:'raw'} and AmazonPageLogic.kt
// classifies statuses and picks the shipment in Kotlin, where commonTest can reach that logic —
// this blob has no test harness at all (no JS engine in commonTest), and a status-vocabulary bug
// living here reached device QA on 2026-07-19.
//
// Selector constants are still validated only against the live site during device QA (Amazon
// requires login, so off-device recon can't see these pages), which is why every bail-out carries
// why/probe/detail for the tracer to log.
private val AMAZON_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  var href = location.href;

  // Signed-out: order pages bounce to /ap/signin (selector fallbacks for A/B variants).
  if (/\/ap\/signin/.test(href) || document.querySelector('form[name="signIn"], #ap_email, #signInSubmit')) return {page: 'loginWall'};

  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  // Amazon needs a login to reach these pages, so selectors can only be verified on-device. A bare
  // {page:'empty'} can't say WHICH branch bailed, so every empty return carries why/probe/detail:
  // the tracer logs it (debug builds only, bounded length) and that log IS the recon that a
  // selector fix has to be based on. Extra keys are ignored by DomExtraction's decoder.
  function probe() {
    var sel = ['.shipment', '[class*="shipment-info-container"]', '[data-component="shipments"] .a-box',
               '[data-component]', '.a-box', 'a[href*="progress-tracker"]', 'a[href*="ship-track"]'];
    var out = {};
    for (var p = 0; p < sel.length; p++) {
      try { out[sel[p]] = document.querySelectorAll(sel[p]).length; } catch (e) { out[sel[p]] = -1; }
    }
    var dc = document.querySelectorAll('[data-component]');
    var names = [];
    for (var n = 0; n < dc.length && names.length < 20; n++) {
      var v = dc[n].getAttribute('data-component');
      if (v && names.indexOf(v) < 0) names.push(v);
    }
    out.dataComponents = names;
    return out;
  }
  function empty(why, detail) {
    return {page: 'empty', why: why, url: href, textHead: text.replace(/\s+/g, ' ').slice(0, 300),
            probe: probe(), detail: detail || null};
  }
  // Amazon's day labels ("Today", "Yesterday", "Tuesday, July 15") omit the year; V8 would guess
  // 2001, so append the current year, with a rollover guard for December events read in January.
  function parseDay(label) {
    var now = new Date();
    var l = (label || '').toLowerCase();
    if (l.indexOf('today') >= 0) return now;
    // "Arriving overnight 7 AM – 11 AM": delivery during the coming night, i.e. tomorrow morning.
    if (l.indexOf('tomorrow') >= 0 || l.indexOf('overnight') >= 0) return new Date(now.getTime() + 864e5);
    if (l.indexOf('yesterday') >= 0) return new Date(now.getTime() - 864e5);
    // Past the relative words we need an explicit calendar date. Appending the year lets V8 parse
    // "Tuesday, July 15", but V8 also "parses" wordy labels — "tomorrow 2026", "Sunday 2026" — into
    // Jan 1, defeating the isNaN guard below (this shipped: an "Arriving tomorrow" card produced
    // etaDate 2026-01-01). Require a real month/day token first so junk returns null, not Jan 1.
    if (!/\d/.test(l) && !/jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec/.test(l)) return null;
    var d = new Date(('' + label).replace(/^[a-z]+,\s*/i, '') + ' ' + now.getFullYear());
    if (isNaN(d.getTime())) return null;
    if (d.getTime() - now.getTime() > 45 * 864e5) d.setFullYear(d.getFullYear() - 1);
    return d;
  }
  function isoDate(d) {
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
  }
  // "Arriving today" / "Arriving Tue, Jul 22" carries the ETA inside the status text on both the
  // order-details header and the tracker page; strip everything up to "arriving" and parse the day.
  function etaFromArriving(t) {
    return (t && /arriving/i.test(t)) ? parseDay(('' + t).replace(/.*arriving/i, '')) : null;
  }
  // Grabs the delivery-window phrase verbatim ("3:00 PM - 5:00 PM", "by 10 PM") for Kotlin's
  // parseEtaWindow to normalize. No conversion here: the repo has no JS engine in commonTest, so
  // anything clever in this blob is only verifiable by device QA.
  function windowText(t) {
    if (!t) return null;
    var m = ('' + t).match(/\d{1,2}(?::\d{2})?\s*(?:am|pm)?\s*(?:-|–|—|to)\s*\d{1,2}(?::\d{2})?\s*(?:am|pm)/i)
         || ('' + t).match(/\bby\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)/i);
    return m ? m[0] : null;
  }

  if (/progress-tracker|ship-track/.test(href)) {
    // --- Shipment tracker page: the rich layer ---
    var statusEl = document.querySelector('#primaryStatus')
      || document.querySelector('[class*="pt-status-main"]')
      || document.querySelector('#shipment-status-container h1, .promise-slot h1');
    var statusText = clean(statusEl);
    var events = [];
    var container = document.querySelector('#tracking-events-container') || document.body;
    var nodes = container.querySelectorAll('[class*="tracking-event"]');
    var day = null;
    for (var i = 0; i < nodes.length; i++) {
      var cls = '' + nodes[i].className;
      if (cls.indexOf('date-header') >= 0) { day = parseDay(clean(nodes[i])); continue; }
      var msg = clean(nodes[i].querySelector('[class*="event-message"], .tracking-event-message'));
      if (!msg || !day) continue;
      var timeText = clean(nodes[i].querySelector('[class*="event-time"], .tracking-event-time'));
      var ts = new Date(day.toDateString() + ' ' + (timeText || '00:00'));
      if (isNaN(ts.getTime())) ts = new Date(day.toDateString());
      events.push({
        timestamp: ts.toISOString(),
        description: msg,
        location: clean(nodes[i].querySelector('[class*="event-location"], .tracking-event-location'))
      });
    }
    events.reverse();  // page lists newest first; canonical order is ascending
    if (!statusText && !events.length) return empty('trackerNoStatusNoEvents', 'nodes=' + nodes.length);
    var promiseText = clean(document.querySelector('[class*="promise"], #expected-delivery-date'));
    var etaDay = parseDay(promiseText) || etaFromArriving(statusText);
    // Status line first (where Amazon quotes the window), promise element as the fallback. Never
    // document.body — an event row's timestamp is not the promise.
    var etaWindow = windowText(statusText) || windowText(promiseText);
    return {page: 'raw', raw: {
      kind: 'tracker',
      statusText: statusText,
      etaDate: etaDay ? isoDate(etaDay) : null,
      etaWindowText: etaWindow,
      events: events
    }};
  }

  // --- Order-details page: pick the target shipment, hop to its tracker ---
  if (/problem finding this order|couldn't find that order|can't find that order|not a valid order/i.test(text)) return {page: 'notFound'};
  // Live QA (2026-07-19) showed this page is built from data-component attributes, not the classes
  // below: '[data-component="shipments"] .a-box' matched inner boxes whose status text and tracker
  // link both live elsewhere, so every card classified UNKNOWN and the scrape bailed as empty. Real
  // card is 'shipmentCard'; the class selectors stay as fallbacks for older/A-B layouts.
  var cards = document.querySelectorAll('[data-component="shipmentCard"]');
  if (!cards.length) cards = document.querySelectorAll('.shipment, [class*="shipment-info-container"], [data-component="shipments"] .a-box');
  if (!cards.length) return empty('noShipmentCards');
  // Read every card verbatim and let Kotlin choose. An order mixes shipment cards with RMA cards
  // ("Replacement complete — We've received your return"), and telling them apart is exactly the
  // judgement that belongs in tested code, so no filtering happens here.
  var out = [];
  for (var k = 0; k < cards.length; k++) {
    var head = clean(cards[k].querySelector('[data-component="shipmentStatus"], .shipment-top-row, [class*="shipment-status"], h4, h5')) || '';
    // Last resort: the card's own leading text is the status headline ("Delivered today"), bounded
    // to one line so trailing action buttons don't join the status.
    if (!head) head = (('' + (cards[k].innerText || '')).split('\n')[0] || '').trim();
    // The headline also carries the ETA ("Arriving today"); parse it here where the page's own
    // date context is available.
    var cardEta = etaFromArriving(head);
    var link = cards[k].querySelector('a[href*="progress-tracker"], a[href*="ship-track"]');
    out.push({head: head, href: (link && link.href) ? link.href : null, etaDate: cardEta ? isoDate(cardEta) : null});
  }
  return {page: 'raw', raw: {kind: 'cards', cards: out}};
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
    sourceId = "amazon",
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
    loginUrl = "https://www.amazon.com/gp/sign-in.html",
    isLoggedInJs = AMAZON_IS_LOGGED_IN_JS,
    // DOM-only in v1 (design spec §Decisions): no stable public tracking-JSON vocabulary to
    // target blind. Live-QA ScrapeTracer captures can justify API patterns later.
    apiUrlPatterns = emptyList(),
    parseRaw = ::parseAmazonRaw,
    challengeMarkers = listOf(
        "Enter the characters you see",
        "Type the characters you see",
        "not a robot",
        "automated access to Amazon data",
    ),
    extractionJs = AMAZON_EXTRACTION_JS,
    parseApi = { _, _ -> null },
)

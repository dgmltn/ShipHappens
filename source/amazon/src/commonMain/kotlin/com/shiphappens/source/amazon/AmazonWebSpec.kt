package com.shiphappens.source.amazon

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.webview.WebProviderSpec

// DOM extractor for BOTH Amazon pages a scrape can visit (design spec §3). The scrape lands on
// the order-details page (needs a signed-in session), picks the first undelivered shipment, and
// emits {page:'goto'} toward its progress-tracker page, carrying the shipment's coarse status as
// the fallback tracking. On the tracker page it extracts the full event history plus the raw
// delivery-window phrase (normalized by parseEtaWindow in the webview module, not here). Selector
// constants are validated against the live site during device QA (Amazon requires login, so
// off-device recon can't see these pages); the returned JSON shape is what unit tests and
// PayloadRouter lock down.
private val AMAZON_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  var href = location.href;

  // Signed-out: order pages bounce to /ap/signin (selector fallbacks for A/B variants).
  if (/\/ap\/signin/.test(href) || document.querySelector('form[name="signIn"], #ap_email, #signInSubmit')) return {page: 'loginWall'};

  function classify(raw) {
    var t = (raw || '').toLowerCase();
    if (t.indexOf('out for delivery') >= 0) return 'OUT_FOR_DELIVERY';
    if (t.indexOf('delivered') >= 0) return 'DELIVERED';
    if (t.indexOf('undeliverable') >= 0 || t.indexOf('running late') >= 0 || t.indexOf('delayed') >= 0 ||
        t.indexOf('problem') >= 0 || t.indexOf('return') >= 0 || t.indexOf('lost') >= 0) return 'EXCEPTION';
    if (t.indexOf('not yet shipped') >= 0 || t.indexOf('not shipped') >= 0 || t.indexOf('order placed') >= 0 ||
        t.indexOf('ordered') >= 0 || t.indexOf('preparing for shipment') >= 0) return 'LABEL_CREATED';
    if (t.indexOf('shipped') >= 0 || t.indexOf('dispatched') >= 0 || t.indexOf('picked up') >= 0) return 'SHIPPED';
    if (t.indexOf('arriving') >= 0 || t.indexOf('arrives') >= 0 || t.indexOf('in transit') >= 0 ||
        t.indexOf('on the way') >= 0 || t.indexOf('on its way') >= 0 || t.indexOf('at carrier') >= 0) return 'IN_TRANSIT';
    return 'UNKNOWN';
  }
  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  // Amazon's day labels ("Today", "Yesterday", "Tuesday, July 15") omit the year; V8 would guess
  // 2001, so append the current year, with a rollover guard for December events read in January.
  function parseDay(label) {
    var now = new Date();
    var l = (label || '').toLowerCase();
    if (l.indexOf('today') >= 0) return now;
    if (l.indexOf('yesterday') >= 0) return new Date(now.getTime() - 864e5);
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
      var st = classify(msg);
      events.push({
        timestamp: ts.toISOString(),
        description: msg,
        location: clean(nodes[i].querySelector('[class*="event-location"], .tracking-event-location')),
        status: st === 'UNKNOWN' ? null : st
      });
    }
    events.reverse();  // page lists newest first; canonical order is ascending
    if (!statusText && !events.length) return {page: 'empty'};
    var promiseText = clean(document.querySelector('[class*="promise"], #expected-delivery-date'));
    var etaDay = parseDay(promiseText) || etaFromArriving(statusText);
    // Status line first (where Amazon quotes the window), promise element as the fallback. Never
    // document.body — an event row's timestamp is not the promise.
    var etaWindow = windowText(statusText) || windowText(promiseText);
    var newestLoc = null;
    for (var j = events.length - 1; j >= 0; j--) { if (events[j].location) { newestLoc = events[j].location; break; } }
    return {page: 'ok', tracking: {
      status: classify(statusText || (events.length ? events[events.length - 1].description : '')),
      etaDate: etaDay ? isoDate(etaDay) : null,
      etaWindowText: etaWindow,
      location: newestLoc,
      events: events
    }};
  }

  // --- Order-details page: pick the target shipment, hop to its tracker ---
  if (/problem finding this order|couldn't find that order|can't find that order|not a valid order/i.test(text)) return {page: 'notFound'};
  var cards = document.querySelectorAll('.shipment, [class*="shipment-info-container"], [data-component="shipments"] .a-box');
  var picks = [];
  for (var k = 0; k < cards.length; k++) {
    var head = clean(cards[k].querySelector('.shipment-top-row, [class*="shipment-status"], h4, h5')) || '';
    picks.push({card: cards[k], status: classify(head), head: head});
  }
  // First undelivered shipment; when everything is delivered, the last card (design spec §Decisions).
  var pick = null;
  for (var m = 0; m < picks.length; m++) { if (picks[m].status !== 'DELIVERED') { pick = picks[m]; break; } }
  if (!pick && picks.length) pick = picks[picks.length - 1];
  if (!pick) return {page: 'empty'};
  // The order-details header already carries the ETA ("Arriving today") — capture it here so a
  // shipment with no tracker link to hop to still yields a countdown, not a bare status.
  var coarseEta = etaFromArriving(pick.head);
  var coarse = pick.status === 'UNKNOWN' ? null : {status: pick.status, etaDate: coarseEta ? isoDate(coarseEta) : null, location: null, events: []};
  var link = pick.card.querySelector('a[href*="progress-tracker"], a[href*="ship-track"]');
  if (link && link.href) return {page: 'goto', url: link.href, tracking: coarse};
  if (coarse) return {page: 'ok', tracking: coarse};  // no tracker link (e.g. old delivered order)
  return {page: 'empty'};
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
    challengeMarkers = listOf(
        "Enter the characters you see",
        "Type the characters you see",
        "not a robot",
        "automated access to Amazon data",
    ),
    extractionJs = AMAZON_EXTRACTION_JS,
    parseApi = { _, _ -> null },
)

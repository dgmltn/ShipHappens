# Amazon WebView Source — Live QA Checklist

Device QA for the Amazon source (design spec 2026-07-15). Run with tracing on:
`adb logcat -s ShipScrape`. Amazon requires login, so before anything else Doug signs
in with his own credentials on the device (Settings → Amazon → Sign in) — credentials
are never typed by tooling.

Selector constants in AmazonWebSpec (extraction JS, isLoggedInJs) are placeholders
until this checklist validates them against the live site — expect to iterate. Note:
the WebView may receive Amazon's mobile layout; validate selectors against what the
DOM result lines in ShipScrape actually show, not desktop DevTools.

## Setup
- [ ] Enable the Amazon source in Settings; confirm descriptor row shows and login row opens amazon.com sign-in
- [ ] Sign in; confirm `isLoggedIn=true` in ShipScrape and the Settings row reflects it (fix AMAZON_IS_LOGGED_IN_JS if not)

## Scrape paths (use a real recent order id for each)
- [ ] Single-shipment order in transit: order-details loads → `goto HOP url=` line appears → tracker page DOM result has status/events → card shows rich data
- [ ] Multi-shipment order with one delivered box: hop targets the FIRST UNDELIVERED shipment (verify hop URL against the order page)
- [ ] Fully delivered order: DELIVERED status (hop or coarse `page:'ok'` path when no tracker link)
- [ ] Order with no tracker link at all: coarse status shown, no error
- [ ] Coarse fallback: if a tracker page yields `page:'empty'`, confirm the card still shows the order-page status (not an error)
- [ ] Fix selectors/vocabulary in AMAZON_EXTRACTION_JS per the above and re-run until statuses, ETA, and event history are right

## Error paths
- [ ] Signed out (Settings → sign out): scrape → AUTH failure "Sign in to Amazon in Settings, then refresh"
- [ ] Bogus order id (e.g. 111-0000000-0000000): NOT_FOUND (validate the notFound wording matchers)
- [ ] Captcha (if Amazon serves one): RATE_LIMITED → open More details → solve manually → refresh recovers
- [ ] Airplane mode: NETWORK failure
- [ ] Second refresh within the throttle window: `throttle HIT` line, no page load

## Framework regressions
- [ ] UPS and USPS sources still refresh correctly (no goto regressions in shared scraper)
- [ ] More-details screen for an Amazon parcel opens the order-details page

## Findings

(record live findings here, as in 2026-07-12-webview-ups-qa.md)

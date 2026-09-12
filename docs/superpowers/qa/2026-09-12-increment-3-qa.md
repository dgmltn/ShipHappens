# Increment 3 device pass (2026-09-10 to 2026-09-12)

Device: a Pixel 10 Pro over USB (the Pixel 9 originally planned was swapped out before the
baseline). Doug drove the screen; the controller read `adb logcat -s ShipScrape`. No tracking
numbers appear here; the raw logs stayed in the session scratchpad and were discarded with it.

Method: install `main` (a9c711d, after Part A) and capture a baseline per carrier; then, after
each Part B task, install the branch head and refresh the same package (or open its More details
screen), comparing the `raw` payload the blob sent and the routed outcome against the baseline.
Carriers with nothing in flight were exercised through their not-found page with a fabricated
number of the right shape, which runs the same runner and helpers.

| Carrier | Package state | What was compared | Result |
|---|---|---|---|
| FedEx (B1) | delivered | statusText, etaText, todayIso, 8 travel-history rows (whenText) | identical to baseline; TRACKING found |
| USPS (B2) | in flight | statusText "On the Way", promise banner, event dates now sent as text | ETA showed "tomorrow" on screen; the exact `.tb-date` strings were pinned as a `PageEventsTest` fixture and resolve to the right local instants |
| Amazon (B3) | delivered ×2, arriving ×1 | order page cards, hop | see below |
| Amazon Logistics (B4) | fabricated (not found) | outcome, pageText, diagnostics | identical outcome; todayIso and probe now present |
| DHL eCommerce (B4) | fabricated (not found); real package via API | outcome, pageText | identical outcome; the real package resolves through the API as before |
| UPS (B4) | real (API); sample number (API); invalid check digit (page) | outcome | the invalid number forced the page read: API rejected, page routed NotFound |

Unmigrated-carrier check after B1: USPS (still on the old zero-argument blob) and AMZL produced
payloads identical to their baselines, proving the `(page, finish)` calling convention does not
disturb an extractor that ignores its arguments.

## Findings

- **Amazon order page layout changed (pre-existing, found at baseline).** Shipment cards are now
  `data-component="shipmentCardOUI"`; the old selector found zero cards and every Amazon scrape
  ended empty. B3 adds the new selector and, because the OUI card carries no tracker anchor of
  its own, falls back to the page's single progress-tracker link when there is exactly one card
  and one link.
- **Amazon's tracker page is no longer reachable.** The progress-tracker link now redirects to
  the order page, so the tracker branch of the blob (event history as `whenText`) cannot run on
  the live site. It is verified by review only. Amazon parcels currently carry card-level status
  and ETA and no event rows. Follow-up: read events from the OUI order page, which appears to
  embed the tracker.
- **USPS's broad capture pattern** grabs the tracking page's own HTML as an "API body"; the
  parser rejects it. Harmless noise, pre-existing.
- **DHL's `h1` fallback** reads the page header ("TRACK A PACKAGE") as a headline on the
  not-found page; the not-found check takes precedence, so no effect.
- **Tracking URLs for UPS and USPS did not normalize their input**: a spaced or lowercase number
  reached the site verbatim (tolerated by both sites). Fixed in B5.
- The tracer truncates DOM payloads at 1500 characters, so event comparisons covered the first
  rows only. Worth raising for debug builds in a later change.

## Outcome

Part B merged with every blob reading through the shared `page` helpers, `todayIso` and
`pageText` on every carrier, and no date, window, or status decision left in JavaScript. The
More details screen applies tracking results only and never follows a hop; adding a package
triggers the headless scrape that does, which is how the Amazon hop was exercised.

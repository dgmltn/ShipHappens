# FedEx WebView Source — Live QA

Device QA for the FedEx source, run against the LIVE fedex.com site on an emulator
(`Small_Phone`, debug build). Live number: Doug's in-flight FedEx Ground package —
**deliberately not recorded here** (kept out of source control by request); as of QA day it
showed "On the way", ETA Thursday 8/20/2026 between 10:10 AM – 2:10 PM, currently in
Sacramento, CA. Bogus number for the not-found path: `999999999999`.

## Result summary: two root-cause findings, both fixed and re-verified live

Unlike every prior source, the initial scrape failed even though the page loaded — and the
failure was NOT selectors. Both findings reshaped the source into DOM-only form:

### 1. FedEx's bot defense keys on the injected fetch/XHR capture hooks

With the standard capture script injected (document-start, wraps `window.fetch` and
`XMLHttpRequest.prototype.open/send`), every tracking lookup client-routed to
`fedextrack/system-error` with "We can't find that tracking number" — while Chrome on the
same emulator/IP rendered full tracking data for the same number. Controlled tests:

- Chrome-spoofed User-Agent (drop `; wv` and `Version/4.0`), hooks on → still system-error.
- Hooks off, UA untouched → page renders tracking data in the headless WebView.

Fix (shared machinery, `WebSessions.configure`): a spec with **no `apiUrlPatterns` gets no
capture script at all**, and `FedexWebSpec` declares none. FedEx is DOM-only; the
provisional `FedexApiParser` (trackingCal shape, never exercisable) was deleted rather than
kept as dead code — it's in git history if capture ever becomes viable.

### 2. The fedextrack SPA needs ~10s past onPageFinished

`onPageFinished` fires on `/wtrk/track/` (a client-side redirect from `/fedextrack/`), but
at the standard 3s settle the DOM is still the app shell; the SPA then routes to
`/fedextrack/?trknbr=…&trkqual=12031~<nbr>~FDEG` and renders somewhere in the 3–12s window.
Fix (shared machinery): `WebProviderSpec.settle: Duration = 3.seconds`, overridable per
provider; FedEx sets 12s (the validated capture point).

## Selectors (captured live 2026-08-19, moving FDEG package)

Off-device recon was impossible (bot protection), so the initial selectors were guesses; a
throwaway element-dump probe in the extraction JS found the real ones:

| Field | Selector | Live text |
|---|---|---|
| status | `.phase3-progress-bar__active-label` | `On the way` |
| eta | `[data-test-id="delivery-date-text"]` | `Thursday8/20/2026 Between 10:10 AM - 2:10 PM` |
| location | `.phase3-view__current-location` | `Currently in Sacramento, CA` |

Notes baked into code + tests:
- The hero flattens with **no space** between weekday and date ("Thursday8/20/2026") —
  `parseFedexEtaDate` uses a lookbehind, not `\b`, before the numeric date.
- The hero also renders **weekday-only** near delivery — "Thursday Between 10:10 AM - 2:10 PM",
  captured live later the same day — resolved via `parseWeekdayName` against `todayIso`
  (added in the 2026-08-19 deterministic-layer consolidation).
- A **cold profile's first scrape can transiently land on `/no-results-found`** ("can't be
  found right now") even for a valid number; it routes NOT_FOUND, is correctly not cached,
  and the next refresh recovers. Seen once during consolidation smoke QA.
- The summary page shows no scan-event rows; `DomRaw` gained `locationText` so the
  "Currently in …" banner survives without fake events (`fedexLocation` strips the phrasing).
- A page with only a delivery promise (no classifiable headline) still reports
  (status UNKNOWN + ETA) instead of "empty".

## Not-found wordings (two live pages)

- `/fedextrack/no-results-found`: "The tracking number you entered can't be found right
  now. Please check the number with the shipper or try again later."
- `/fedextrack/system-error`: "We can't find that tracking number. Please check with the
  shipper to make sure it's the correct one."

The extraction regex covers both; re-verified live — bogus number routes `NotFound`
("FedEx doesn't recognize this number" failure downstream).

## Checklist
- [x] Settings shows FedEx row (purple accent), disabled by default; toggling on works.
- [x] Add flow live-detects `NEW · FEDEX` while typing a 12-digit number.
- [x] Headless scrape: `page:'raw'` with status/eta/location; `scrape DONE … TRACKING
  found — cached` (~13s including the 12s settle).
- [x] List card: "FedEx · In transit", 1-DAY countdown ring, "1 arriving soon" header.
- [x] Detail: "Arrives tomorrow", "Thu, Aug 20 · 10:10 AM – 2:10 PM", location chip
  "Sacramento, CA", timeline at In transit.
- [x] Not-found: bogus 12-digit number → `NotFound` route.
- [x] Delivered state exercised live 2026-08-20 after the package arrived — and it found a bug:
  the delivered page drops `.phase3-progress-bar__active-label` entirely (statusText scraped
  null → UNKNOWN → the card kept the stale "Out for delivery"). The delivery-date eyebrow
  `[data-test-id="delivery-date-header"]` flips "ESTIMATED DELIVERY DATE" → "DELIVERED" and is
  now the status fallback; the date element reads "Thursday8/20/2026 at 1:48 pm" (delivery
  time, not a window). Fixed + re-verified live; captures pinned in `FedexPageLogicTest`.
- [ ] Out-for-delivery wording still unexercised live (vocabulary unit-tested).
- [ ] Login/`isLoggedInJs` markers best-effort — no FedEx account session was exercised.

## Debugging: ScrapeTracer
Tracing is on automatically in debug builds: `adb logcat -s ShipScrape`. The `empty`
bail-out payload carries `why`/`textHead`/`probe` (selector match counts) for selector
drift hunts.

# WebView USPS Source — Manual QA Checklist

Live carrier pages can't run in unit tests; this checklist covers the JS/live-page seam.
Run on a device/emulator with Google Play WebView. Sample number: 9434636106092288655003
(also use a real, currently-moving USPS number if available).
Re-run whenever `UspsWebSpec`'s JS or URL patterns change.

## Debugging: use the built-in ScrapeTracer, not ad-hoc logging
Same facility as UPS QA: `adb logcat -s ShipScrape` for the scrape lifecycle;
`WebScrapeDebug.dumpBodiesToFile = true` to capture full API bodies to
`…/Android/data/com.shiphappens/files/scrape-debug/` for `adb pull`.

## What live QA established (2026-07-15, emulator, tools.usps.com, sample number — delivered parcel)
The tracking page is SERVER-RENDERED (unlike ups.com's SPA) — the DOM-first design holds:
- **No separate JSON tracking API observed.** The only `apiUrlPatterns` capture was the page
  document itself (`https://tools.usps.com/tracking/<num>`, ~118 KB HTML), which
  `UspsApiParser` correctly rejects (null → Unparsed) — the DOM extractor is the working
  path. `UspsApiParser`'s DTO vocabulary remains unexercised speculation; keep it tolerant,
  don't tighten the pattern to chase an endpoint that may not exist for anonymous scrapes.
- **Akamai did NOT challenge the app's WebView** (it did bot-wall a desktop automation
  browser on 2026-07-14). Headless scrape completed in ~5 s: `scrape DONE: 2 payload(s),
  TRACKING found — cached`.
- **Live DOM structure** (delivered parcel): unique `p.tb-status` ("Delivered") inside the
  current `.tb-step`; per-step `p.tb-status-detail`, `p.tb-location`, and `p.tb-date` with
  FULL date+time ("July 11, 2026 12:12 PM") — no day-group headers, so per-step timestamps
  extract cleanly. "Latest Update" banner sentence: `p.banner-content.statusSummaryText`.
- **Fixed live (see UspsWebSpec.kt):** the original status query included
  `[class*="tracking-status"]`, which matched the ancestor `current-tracking-status-wrapper`
  earlier in document order; its concatenated text made classify() return OUT_FOR_DELIVERY
  for a delivered package. Status query is now a priority chain: `.tb-status` →
  `.delivery_status h2` → `.statusSummaryText`.
- **Delivered pages have no expected-delivery block** → `etaDate: null` is correct there.

## Known unknowns still open
- [ ] Expected-delivery selector (`.expected_delivery .date, [class*="expected-delivery"],
      .eta_info`) has never matched a live page — needs an IN-TRANSIT parcel to verify.
      Symptom while wrong: detail header shows "Waiting for first update" despite live
      data (ETA-driven headline, DetailViewModel).
- [ ] `notFound` phrasings and the bogus-number path against the live page.
- [ ] `isLoggedInJs` markers against a real logged-in session.
- [ ] Akamai challenge path: never observed in the app's WebView so far; if one appears,
      confirm headless reports RATE_LIMITED pointing at More details, and that solving it
      (human) repairs subsequent scrapes.
- [x] ~~In-page tracking API capture~~ — settled: no JSON endpoint for anonymous scrapes;
      document-HTML capture is expected noise, parser rejects it.
- [x] ~~Event date/TIME granularity / day-group headers~~ — settled: full per-step datetime.
- [x] ~~`.tb-status` selector collision~~ — settled: real (via ancestor wrapper, not
      per-step), fixed with the priority-chain query.

## Setup
- [x] Install debug build; enable the USPS source in Settings. (2026-07-15: card shows
      "Sign in to USPS", no credential fields; toggle → Connected.)
- [x] Add a parcel with the sample/real USPS number; card shows USPS accent + carrier.
      (2026-07-15: live "NEW · USPS" detection while typing; card + detail render live
      DELIVERED data, full timeline, location chip CARLSBAD, CA 92009.)

## Phase 1 — visible web view
- [x] Detail screen shows "More details on USPS ›" for the USPS parcel. (2026-07-15)
- [ ] Tapping it opens the in-app USPS tracking page; page renders and is interactive.
- [ ] After the page loads, go back: timeline/status reflect live USPS data
      (scrape-on-view wrote through `applySnapshot`). Check status, ETA, event list.
- [ ] DOM fallback: with API patterns deliberately broken (temporary local edit), the
      extractor still produces an update with the full event history. Revert the edit.

## Login
- [ ] Settings → USPS card → "Sign in to USPS" opens the login page; complete a real login.
- [ ] `isLoggedInJs` detects it (screen auto-pops, card shows "Sign out of USPS").
- [ ] Kill and relaunch the app: still signed in (cookie flush worked).
- [ ] Sign out from Settings; reopen the USPS page: logged out (cookie clear worked).

## Phase 2 — headless refresh
- [x] Initial add triggers a headless scrape that updates the parcel without opening any
      web page (2026-07-15, twice: pre- and post-selector-fix, ~5 s each).
- [ ] Pull-to-refresh on the list: USPS parcel updates without opening any web page.
- [ ] Immediately pull-to-refresh again: fast (throttle cache; no second page load in Logcat).
- [ ] Airplane mode: refresh fails with a network toast, not a crash or ANR.
- [ ] Bogus-but-valid-format number (9400111899223300119999): NOT_FOUND path, no crash.

## Bot challenge (expected for USPS — Akamai)
- [ ] Headless refresh during a challenge reports the RATE_LIMITED message pointing at
      More details.
- [ ] Opening More details shows the challenge; solving it (as the human user) repairs
      subsequent headless scrapes.

## Regression
- [ ] UPS parcels still refresh via webview scrape (shared WebSessions/cookies unaffected).
- [ ] Demo-source parcels still refresh and render normally.
- [ ] FedEx settings card unchanged ("Direct API coming soon" when enabled).
- [x] USPS settings card no longer asks for consumer key/secret (2026-07-15; FedEx card
      still shows its API-key fields).
- [ ] iOS build still compiles; USPS card shows not-implemented state.

# WebView UPS Source — Manual QA Checklist

Live carrier pages can't run in unit tests; this checklist covers the JS/live-page seam.
Run on a device/emulator with Google Play WebView, with a REAL UPS tracking number.
Re-run whenever `UpsWebSpec`'s JS or URL patterns change.

## Debugging: use the built-in ScrapeTracer, not ad-hoc logging
Tracing is on automatically in debug builds (`WebScrapeDebug.enabled` defaults to the app's
debuggable flag). Filter Logcat with `adb logcat -s ShipScrape` to see the full lifecycle of
every scrape: `[<sourceId>] scrape START`, `configure`, `API capture` (with the body dumped in
numbered chunks), `DOM result`, and `scrape DONE`. To capture a full API body to a file for
`adb pull` (instead of reassembling Logcat chunks), set `WebScrapeDebug.dumpBodiesToFile = true`
— bodies land in `…/Android/data/com.shiphappens/files/scrape-debug/`.

## What live QA established (2026-07-13, ups.com)
The current ups.com track page is a heavy Angular SPA. Verified working state to sanity-check against:
- Tracking data comes from an XHR/fetch to `https://webapis.ups.com/track/api/Track/GetStatus?loc=…`
  (host is `webapis.ups.com`, matched by the `.*ups\.com/track/api/Track/GetStatus.*` pattern).
- The status/timeline live in `trackDetails[].shipmentProgressActivities[]`; the ETA is
  `trackDetails[].sdd` (YYYYMMDD) + `sdt` (end-of-window HH:MM:SS).
- The SPA fires that XHR ~3–15s in, often BEFORE `onPageFinished` (which sometimes never fires),
  so the headless scraper completes on the captured API payload, not the DOM extractor.
  A healthy `scrape DONE … TRACKING found` lands within a second of `API capture`.
- The bundled DOM-extractor selectors do NOT match this SPA (returns `page:empty`); the API-capture
  path is the working one. Updating the DOM fallback selectors is a future nicety, not required.

## Setup
- [ ] Install debug build; enable the UPS source in Settings.
- [ ] Add a parcel with a real 1Z tracking number.

## Phase 1 — visible web view
- [ ] Detail screen shows "More details on UPS ›" for the UPS parcel (and NOT for demo parcels).
- [ ] Tapping it opens the in-app UPS page; page renders and is interactive.
- [ ] After the page loads, go back: the timeline/status reflect the live UPS data
      (scrape-on-view wrote through `applySnapshot`). Check status, ETA, event list.
- [ ] If the API capture missed (no update), check Logcat for bridge payloads; verify
      `apiUrlPatterns` still matches ups.com's tracking XHR (DevTools remote inspect:
      chrome://inspect). Update `UpsWebSpec.apiUrlPatterns`/`UpsApiParser` DTOs and the
      recorded fixture in `UpsApiParserTest` if UPS changed the endpoint or shape.
- [ ] DOM fallback: with API patterns deliberately broken (temporary local edit), the
      extractor still produces a status-only update. Validate/fix the selectors in
      `UPS_EXTRACTION_JS`. Revert the temporary edit.

## Login
- [ ] Settings → UPS card → "Sign in to UPS" opens the login page; complete a real login.
- [ ] `isLoggedInJs` detects it (screen auto-pops, card shows "Sign out of UPS"). If it
      doesn't auto-pop, inspect the logged-in DOM and fix `UPS_IS_LOGGED_IN_JS`.
- [ ] Kill and relaunch the app: still signed in (cookie flush worked).
- [ ] "More details" page shows logged-in content (no login hint banner).
- [ ] Sign out from Settings; reopen the UPS page: logged out (cookie clear worked).

## Phase 2 — headless refresh
- [x] Pull-to-refresh on the list: UPS parcel updates without opening any web page.
      (Verified 2026-07-13: two real 1Z parcels scraped, parsed, and cached; each completed
      ~3s after start, on the API capture, before onPageFinished — see ShipScrape logs.)
- [ ] Immediately pull-to-refresh again: completes fast (throttle returned cached result;
      confirm no second page load in Logcat).
- [ ] Airplane mode: refresh fails with a network toast, not a crash or ANR.
- [ ] Bogus-but-valid-format number (1Z9999999999999999): NOT_FOUND path, no crash.

## Bot challenge (opportunistic — only if UPS serves one)
- [ ] Headless refresh reports the RATE_LIMITED message pointing at More details.
- [ ] Opening More details shows the challenge; solving it repairs subsequent headless scrapes.

## Regression
- [ ] Demo-source parcels still refresh and render normally.
- [ ] Settings cards for USPS/FedEx unchanged ("Direct API coming soon" when enabled).
- [ ] iOS build still compiles; UPS card shows not-implemented state; no More-details button
      behavior expected beyond the placeholder screen.

# WebView UPS Source — Manual QA Checklist

Live carrier pages can't run in unit tests; this checklist covers the JS/live-page seam.
Run on a device/emulator with Google Play WebView, with a REAL UPS tracking number.
Re-run whenever `UpsWebSpec`'s JS or URL patterns change.

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
- [ ] Pull-to-refresh on the list: UPS parcel updates without opening any web page.
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

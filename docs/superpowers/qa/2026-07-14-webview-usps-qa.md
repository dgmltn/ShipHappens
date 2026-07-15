# WebView USPS Source — Manual QA Checklist

Live carrier pages can't run in unit tests; this checklist covers the JS/live-page seam.
Run on a device/emulator with Google Play WebView. Sample number: 9434636106092288655003
(also use a real, currently-moving USPS number if available).
Re-run whenever `UspsWebSpec`'s JS or URL patterns change.

## Debugging: use the built-in ScrapeTracer, not ad-hoc logging
Same facility as UPS QA: `adb logcat -s ShipScrape` for the scrape lifecycle;
`WebScrapeDebug.dumpBodiesToFile = true` to capture full API bodies to
`…/Android/data/com.shiphappens/files/scrape-debug/` for `adb pull`.

## Known unknowns this QA must settle (design spec 2026-07-14)
tools.usps.com is behind Akamai Bot Manager; desktop recon only received the challenge
script, so ALL of the following are provisional until observed live:
- [ ] The in-page tracking API: capture the real XHR/fetch URL + body via ScrapeTracer
      (or chrome://inspect). Tighten `UspsWebSpec.apiUrlPatterns` from the broad
      `.*tools\.usps\.com/.*[Tt]rack.*` to the real endpoint, replace the PROVISIONAL
      fixture in `UspsApiParserTest`, and fix `UspsApiParser` DTO field names to match.
- [ ] DOM selectors in `USPS_EXTRACTION_JS` (`.tb-status`, `.tb-step`, `.tb-date`,
      `.tb-location`, expected-delivery block): validate against the live page, fix
      as needed.
- [ ] Event date/TIME granularity: if the live tracking history renders a shared date
      header per day-group (rather than a full date+time per `.tb-step`), the extractor
      will drop or midnight-collapse same-day events — verify per-step timestamps and
      restructure the `.tb-date` handling in `USPS_EXTRACTION_JS` if needed.
- [ ] `.tb-status` selector collision: the document-level status query and the per-step
      description fallback both use `.tb-status`; if per-step nodes carry that class, the
      page-status query may latch onto a history step instead of the banner — verify and
      disambiguate the selectors if needed.
- [ ] `isLoggedInJs` markers against a real logged-in session.
- [ ] Akamai behavior in the app's WebView: does the interstitial self-solve (it may in a
      real WebView with JS + cookies), or does it surface as `page:'empty'`/timeout?
      Record findings here. If it blocks headless scrapes entirely, the visible
      More-details flow is the fallback and the challenge → RATE_LIMITED message must
      point there.

## Setup
- [ ] Install debug build; enable the USPS source in Settings.
- [ ] Add a parcel with the sample/real USPS number; card shows USPS accent + carrier.

## Phase 1 — visible web view
- [ ] Detail screen shows "More details on USPS ›" for the USPS parcel.
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
- [ ] USPS settings card no longer asks for consumer key/secret.
- [ ] iOS build still compiles; USPS card shows not-implemented state.

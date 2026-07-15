# USPS WebView Tracking Source — Design

**Date:** 2026-07-14
**Status:** Approved

## Goal

Turn the USPS stub source into a live tracking source by reusing the WebView scraping
framework (see `2026-07-12-webview-tracking-source-design.md`). USPS is the second
provider on that framework, so this design is deliberately small: one `WebProviderSpec`,
one thin `WebViewBasedSource` subclass, one tolerant API parser, tests, and a live-QA
checklist. No new scraping machinery.

Sample tracking number for QA: `9434636106092288655003`
Tracking URL shape: `https://tools.usps.com/tracking/9434636106092288655003`

## Decisions (settled during brainstorming)

- **Approach:** WebView scrape of tools.usps.com, exactly like UPS. The alternative —
  the official USPS Tracking API v3 that the stub's `consumerKey`/`consumerSecret`
  config sketched — requires USPS developer-account onboarding and gives no v1 benefit.
  The credential `ConfigField`s go away; web sources carry no config.
- **Extraction strategy: DOM-first, API-capture opportunistic.** Recon (2026-07-14)
  showed tools.usps.com behind Akamai Bot Manager; a desktop automation browser only
  received the challenge script, so the in-page tracking API's URL and JSON shape could
  not be captured off-device. This inverts the UPS balance: ship a rich DOM extractor
  (the USPS page renders the full event history in the DOM) as the reliable layer, plus
  a broad `apiUrlPatterns` entry whose captures are parsed tolerantly. Live device QA
  with ScrapeTracer then tightens the pattern and fills in the parser DTOs, the same
  workflow that validated UPS (`docs/superpowers/qa/2026-07-12-webview-ups-qa.md`).
- **Login supported but optional**, same as UPS: anonymous tracking works; a usps.com
  session cookie can only enrich results.

## 1. Module changes — `:source:usps` only

`build.gradle.kts` gains what `:source:ups` has: `api(projects.source.webview)`,
kotlinx-serialization (+ its Gradle plugin), kotlinx-datetime.

`UspsSource.kt` is replaced by the UPS file layout:

- **`UspsWebSpec.kt`** — the `WebProviderSpec`:
  - `sourceId = "usps"`, `carrier = WellKnownCarriers.USPS`, `cookieDomain = "usps.com"`
  - `trackingUrl = { "https://tools.usps.com/tracking/$it" }`
  - `loginUrl = "https://reg.usps.com/entreg/LoginAction_input"` (USPS account login)
  - `isLoggedInJs` — greeting/sign-out markers on usps.com chrome (validated in live QA)
  - `apiUrlPatterns = listOf(".*tools\\.usps\\.com/.*[Tt]rack.*")` — deliberately broad;
    non-tracking captures are rejected by the parser returning `null`. Tightened to the
    real endpoint during live QA.
  - `challengeMarkers` — Akamai vocabulary: "Access Denied", "Reference #",
    human-verification wording ("verify you are a human", "unusual activity").
  - `extractionJs` — see §2.
  - `parseApi = { _, body -> UspsApiParser.parse(body) }`
- **`UspsApiParser.kt`** — tolerant kotlinx-serialization parser with the same contract
  as `UpsApiParser`: every field optional, `null` on undecodable/foreign bodies, unknown
  status wording degrades to `UNKNOWN`. Initial DTOs target the long-standing
  TrackConfirmAction JSON vocabulary (`trackResults`/`trackInfo`-style: status summary,
  expected-delivery date, event list with date/time/location); the recorded live QA
  fixture becomes the regression test, as `UpsApiParserTest` did for UPS.
- **`UspsSource.kt`** — shrinks to the UPS shape:

  ```kotlin
  class UspsWebSource(scraper: WebScraper) : WebViewBasedSource(UspsWebSpec, scraper) {
      override fun detectCarrier(trackingNumber: String): Carrier? = /* existing regexes */
  }
  val uspsSourceModule: Module = module { single { UspsWebSource(get()) } bind TrackingSource::class }
  ```

  `detectCarrier` keeps the current regexes (`^(94|93|92|95|82)\d{14,24}$`,
  `^[A-Z]{2}\d{9}US$`); the sample number matches the first.

Nothing else changes. `AppModules`, `settings.gradle.kts`, `SourceRegistry`,
`BuiltInCarrierDetection`, and the Settings/Detail UI are already generic; UPS strings in
previews/demo data are cosmetic and untouched. On iOS the descriptor stays
`implemented = false` automatically because `WebScraper.isAvailable` is false there.

## 2. DOM extractor

`extractionJs` follows the framework contract — a function expression returning
`{page: 'ok'|'notFound'|'loginWall'|'challenge'|'empty', tracking: <ScrapedTracking>}`:

- **notFound:** page text matching USPS's "Status Not Available" / "could not locate the
  tracking information" wording.
- **ok:** status text from the tracking banner (`.tb-status` / banner header), expected
  delivery from the expected-delivery block, and — richer than the UPS fallback — the
  full event list from the tracking-history steps (date, time, description, location per
  step), classified with the same keyword vocabulary as the parser ("delivered",
  "out for delivery", "in transit", "arrived"/"departed", "accepted"/"picked up" →
  SHIPPED, "pre-shipment"/"label created" → LABEL_CREATED, "alert"/"attempted" →
  EXCEPTION).
- **empty:** no recognizable tracking DOM (e.g. Akamai interstitial that presents no
  marker text) — scraper falls through to timeout/error handling.

Selector constants are placeholders-by-design until live QA, like UPS's were; the shape
of the returned JSON is what unit tests and `PayloadRouter` lock down. Event timestamps
are emitted as ISO instants interpreted in the device zone, matching `UpsApiParser`'s
documented tradeoff.

## 3. Error mapping

All inherited from `WebViewBasedSource`: login wall → AUTH ("Sign in to USPS in
Settings…"), challenge → RATE_LIMITED (points at More details), notFound → NOT_FOUND,
load error/timeout → NETWORK. No USPS-specific error code paths.

## 4. Testing

- **`UspsSourceTest`** — `detectCarrier` accepts the sample number + international
  format, rejects UPS/FedEx shapes; descriptor id/kind; no config fields.
- **`UspsApiParserTest`** — representative JSON fixture(s): happy path with events and
  expected delivery, missing-fields degradation, foreign JSON → `null`, malformed →
  `null`. Fixture gets replaced/augmented with the real captured body during live QA.
- Extraction-JS output shape and routing already covered by `PayloadRouterTest` /
  `ScrapedTrackingTest`; no new harness.

## 5. Live QA (device, follows implementation)

New checklist `docs/superpowers/qa/2026-07-14-webview-usps-qa.md` mirroring the UPS one:
ScrapeTracer (`adb logcat -s ShipScrape`) to observe the real page, capture the actual
tracking XHR/fetch URL + body, then tighten `apiUrlPatterns`, finalize `UspsApiParser`
DTOs, validate/fix DOM selectors and `isLoggedInJs`, and exercise login, throttle,
airplane-mode, and bogus-number paths. The Akamai bot wall is expected to be the main
variable; the challenge → More-details repair loop is the designed mitigation.

## Out of scope

- Official USPS API integration (the deleted stub config can return in a future source).
- Informed Delivery / account-wide package discovery.
- iOS WKWebView scraper (tracked by the framework spec).

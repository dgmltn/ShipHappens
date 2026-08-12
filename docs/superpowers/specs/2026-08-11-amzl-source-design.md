# Amazon Logistics (AMZL) Tracking Source — Design

**Date:** 2026-08-11
**Status:** Approved

## Goal

Add a live tracking source for Amazon Logistics TBA numbers via track.amazon.com, on the
existing WebView scraping framework (see `2026-07-12-webview-tracking-source-design.md`).
Fourth provider on the framework: one `WebProviderSpec`, one thin `WebViewBasedSource`
subclass, one tolerant API parser, tests, and a live-QA pass. No new scraping machinery.

Sample tracking number for QA: `TBA333593378975`
Tracking URL shape: `https://track.amazon.com/tracking/TBA333593378975?trackingId=TBA333593378975`

This is distinct from the existing `:source:amazon` orders source: that one takes an
order id (3-7-7 digits) and needs a login; this one takes a TBA carrier tracking number
and works anonymously.

## Recon findings (2026-08-11, off-device curl)

- The tracking page is a JS SPA (`AmazonShippingRecipientApp`); the shell HTML carries no
  tracking data.
- The SPA fetches `https://track.amazon.com/api/tracker/{trackingId}` — **anonymous public
  JSON**, no login or cookies required (`packageAuthenticationDetails.accessType:
  "ANONYMOUS_PACKAGE_ACCESS"`). Curl with a browser UA gets HTTP 200.
- Envelope fields `progressTracker` and `eventHistory` are **double-encoded** (JSON strings
  inside the outer JSON object).
- `progressTracker.summary` carries `status` (observed: `CreationConfirmed`),
  `metadata.trackingStatus` (observed: `READY_FOR_RECEIVE`), `promisedDeliveryDate` /
  `expectedDeliveryDate` (`"Aug 13, 2026, 3:00:00 AM"` — month-name format, time is not a
  customer-facing window).
- `eventHistory.eventHistory[]` rows carry `eventCode` (`CreationConfirmed`), `eventTime`,
  `location` (empty object for this package), and localisation string ids (`swa_rex_*`) —
  **no English text**; the SPA translates client-side.
- Milestone string ids observed: `swa_rex_shipping_label_created`, `swa_rex_intransit`,
  `swa_rex_ofd`, `swa_rex_delivering_eddday`.
- An unknown TBA still returns HTTP 200, with
  `progressTracker.errors[].errorCode == "TRACKING_ID_NOT_FOUND"`.
- Other envelope fields (`proofOfDeliveryImage`, `geocodeDetails`,
  `predictiveDeliveryWindowDetails`, …) are present but null/unneeded for v1, except that
  `predictiveDeliveryWindowDetails`, when non-null, should feed the ETA window.
- Captured fixtures (real package + not-found) are checked into the parser tests.

## Decisions (settled during brainstorming)

- **Approach: WebView + API capture**, the UPS pattern. `apiUrlPatterns` captures the
  SPA's own `/api/tracker/` XHR; all parsing happens in unit-testable Kotlin
  (`parseApi`). Direct Ktor HTTP was considered (lighter, cross-platform) but rejected:
  it would reintroduce a second source pattern (removed with trackingmore), lose the
  in-app More-details page (`DetailViewModel` hides the button for non-web-capable
  carriers), and be the first thing to break if Amazon adds bot-gating. The WebView
  path rides a real browser fingerprint.
- **Distinct carrier identity**: `WellKnownCarriers.AMAZON_LOGISTICS` — code `amzl`,
  display "Amazon Logistics", accent `#37475A` (Amazon squid-ink navy). Reusing the
  `amazon` carrier would break the carrier-code→webSpec lookup
  (`WebDetailViewModel.kt` finds specs by carrier code; a TBA parcel would open the
  order-details URL).
- **`cookieDomain = "track.amazon.com"`, not `amazon.com`.** Android's `CookieManager`
  is app-global, so an existing amazon.com login session reaches track.amazon.com pages
  no matter what the spec says — `cookieDomain` only controls bridge origin rules,
  goto-hop validation, and sign-out `clearForDomain`. Scoping it to the subdomain keeps
  an AMZL sign-out from expiring the Amazon orders session, and confines bridge
  injection and hops to the tracking site. The source neither needs nor manages any
  login state.
- **Module name `:source:amzl`**, matching the carrier code and the short sibling
  modules.
- **Not-found is classified by the DOM extractor, not the parser.** `parseApi` can only
  return tracking-or-null (null routes to `Unparsed`), so a `TRACKING_ID_NOT_FOUND`
  body parses to null and the extraction JS recognizes the SPA's not-found rendering as
  `{page:'notFound'}`. Its wording is a live-QA item.

## 1. Module & wiring

- `settings.gradle.kts`: `include(":source:amzl")`.
- `source/amzl/build.gradle.kts`: copy of `:source:usps`'s (webview + serialization +
  datetime + koin), namespace `com.dgmltn.shiphappens.source.amzl`.
- `domain/Carrier.kt`: add `AMAZON_LOGISTICS = Carrier("amzl", "Amazon Logistics",
  "#37475A")` to `WellKnownCarriers` (+ `all`).
- `data/BuiltInCarrierDetection.kt`: add `^TBA\d{9,15}$` (normalized input is already
  uppercased with hyphens/whitespace stripped).
- `ui/di/AppModules.kt`: add `amzlSourceModule`; `ui/build.gradle.kts` gains the module
  dependency. Settings row + enable toggle appear via the registry, disabled by default.

## 2. `AmzlWebSpec`

- `sourceId = "amzl"`, `carrier = WellKnownCarriers.AMAZON_LOGISTICS`.
- `cookieDomain = "track.amazon.com"` (see Decisions).
- `trackingUrl = { "https://track.amazon.com/tracking/$it?trackingId=$it" }` (Amazon's
  own link shape; input normalized upstream).
- `loginUrl = "https://www.amazon.com/gp/sign-in.html"` — framework-required; the
  source never demands auth (anonymous API), so the login path is vestigial.
- `isLoggedInJs`: constant-false variant (`(function(){ return false; })()`); the SPA
  chrome has no account menu worth probing, and login state is irrelevant here.
- `apiUrlPatterns = [".*track\.amazon\.com/api/tracker/.*"]`.
- `challengeMarkers`: same captcha wordings as `AmazonWebSpec`.
- `extractionJs` (deliberately minimal — API capture is the primary layer):
  - not-found rendering → `{page:'notFound'}` (selector/wording validated in QA);
  - a visible status headline → `{page:'raw', raw:{kind:'tracker', statusText}}` as a
    coarse DOM fallback, classified in Kotlin by `parseRaw`;
  - otherwise `empty` with why/probe/detail diagnostics for ScrapeTracer, per the
    Amazon-source lesson (2026-07-19).
- `parseApi = { _, body -> AmzlApiParser.parse(body) }`; `parseRaw` maps `statusText`
  through the same status vocabulary.

## 3. `AmzlApiParser` (the heart — pure Kotlin, commonTest-covered)

- Decode outer envelope with `ignoreUnknownKeys`; decode the `progressTracker` and
  `eventHistory` string fields as JSON again. Any decode failure → null.
- Bodies whose `errors[]` contain `TRACKING_ID_NOT_FOUND` (or that carry no summary at
  all) → null.
- **Status**: text-first-then-code layering like UPS. `summary.status` vocabulary:
  `CreationConfirmed` → LABEL_CREATED; `PickupDone`/`PickedUp` → SHIPPED;
  `InTransit`/`ArrivedAtDeliveryCenter` → IN_TRANSIT; `OutForDelivery` →
  OUT_FOR_DELIVERY; `Delivered` → DELIVERED; `DeliveryAttempted`/`Undeliverable`/
  `Lost`/`Damaged`/`ReturningToSeller`/`ReturnedToSeller` → EXCEPTION; else fall back
  to `metadata.trackingStatus` (`READY_FOR_RECEIVE` → LABEL_CREATED,
  `OUT_FOR_DELIVERY` → OUT_FOR_DELIVERY, `DELIVERED` → DELIVERED, …), else UNKNOWN.
  Only `CreationConfirmed`/`READY_FOR_RECEIVE` were observed live; the rest are
  provisional until QA across a package's lifecycle.
- **ETA**: `promisedDeliveryDate` (fallback `expectedDeliveryDate`) parsed by a
  tolerant `"MMM d, yyyy"` month-name regex that accepts U+202F/U+00A0 spaces; **date
  only** — the embedded time is not a delivery window. `etaWindowStart`/`etaWindowEnd`
  stay null in v1: `predictiveDeliveryWindowDetails` was null for the recon package and
  its shape is unknown; ScrapeTracer captures during QA will reveal it, and wiring it
  into the window fields is a follow-up.
- **Events**: map `eventHistory[]` rows — timestamp from `eventTime` (same month-name
  parser + device-zone interpretation, the documented UPS trade-off), description from
  a small eventCode→English map (`CreationConfirmed` → "Label created", …) with a
  CamelCase→"Camel case" splitter as fallback, location from `location.city`/`state`
  when present, per-event `status` via the same vocabulary (UNKNOWN dropped). Events
  sorted ascending.
- Proof-of-delivery image and geocode fields: ignored in v1.

## 4. Tests

- `AmzlApiParserTest` (commonTest): the two live fixtures (real + not-found) inline;
  status-vocabulary table; month-name date parsing incl. narrow-space variants;
  double-decode failure tolerance (garbage, missing fields ⇒ null/UNKNOWN, never
  throw).
- `AmzlSourceTest`: TBA detection boundaries (`TBA` + 9–15 digits; rejects order ids,
  1Z…, bare digits; accepts lowercase/hyphenated raw input via normalize).
- `AmzlWebSpecTest`: tracking-URL shape, origin rules confined to track.amazon.com,
  API pattern matches the captured XHR URL, parseRaw classification.
- `BuiltInCarrierDetectionTest`: TBA cases.
- Device QA via the verify skill with the live TBA: enable source, add package,
  confirm status/ETA render, confirm not-found wording for a bogus TBA, capture
  ScrapeTracer output to tighten selectors/vocabulary. QA doc under
  `docs/superpowers/qa/`.

## Out of scope (v1)

- Login-gated packages (`accessType` other than anonymous), proof-of-delivery photo,
  map/geocode display, delivery-window prediction UI beyond the existing ETA fields,
  iOS scraper availability (unchanged framework limitation).

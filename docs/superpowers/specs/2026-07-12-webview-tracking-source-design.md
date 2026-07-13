# WebView-Based Tracking Source (UPS First) — Design

**Date:** 2026-07-12
**Status:** Approved

## Goal

Add a tracking source that gets its data by driving a real browser session against the
carrier's own website, instead of a paid/official API. UPS is the first provider, but the
machinery is reusable: adding another carrier later should mean writing a provider spec,
not a new source. The user can optionally log in to the carrier site once; the session
cookies persist and enrich subsequent scrapes. At minimum, the user gets a "More details"
button on the parcel detail screen that opens the carrier's tracking page in-app.

## Decisions (settled during brainstorming)

- **Engine:** platform system WebView (Android WebView now; WKWebView is the future iOS
  analog). Not GeckoView — it adds ~70–100 MB, is Android-only, and has no compelling
  benefit here.
- **Platform scope:** Android-only implementation, KMP-ready. Abstractions live in
  commonMain with platform seams (same pattern the removed TrackingMore source used for
  its Ktor engine). iOS keeps the UPS source `implemented = false` until a WKWebView
  actual exists.
- **Extraction:** three layers, all emitting one canonical format — (1) capture the
  carrier page's own internal JSON API calls, (2) injected-JS DOM extraction as fallback,
  (3) an on-device-AI hook (interface only in v1; ML Kit GenAI / Gemini Nano later).
- **Login scope:** enrich tracking of parcels the user already added. No account-wide
  package discovery (My Choice import) in v1.
- **Delivery:** staged. Phase 1 = visible WebView + scrape-on-view. Phase 2 = headless
  scraping wired into the normal refresh path. True background refresh (WorkManager)
  stays out of scope per the v1 no-background-schedulers spec.

## 1. Modules and layering

New KMP module **`:source:webview`**:

- **commonMain**
  - `WebViewBasedSource` — abstract class implementing `TrackingSource`. Owns the
    generic flow: `track()` = ask the `WebScraper` to scrape the spec's tracking URL,
    parse the canonical result, map failures to `SourceResult.Failure`.
  - `WebProviderSpec` — the per-provider recipe (see §2).
  - `WebScraper` — interface: `suspend fun scrape(request: ScrapeRequest): ScrapeResult`.
    Injected via Koin; Android provides a real one, iOS an "unavailable" one.
  - `ScrapedTracking` — canonical extraction output model (status, ETA, events[]),
    serialized as JSON across the JS bridge.
  - `AiPageExtractor` — interface with a no-op default implementation; hook for the
    future on-device-AI fallback layer.
- **androidMain**
  - `HeadlessWebViewScraper` (Phase 2) — implements `WebScraper` with an off-screen
    WebView.
  - `WebSession` — shared WebView plumbing: cookie management (`CookieManager`),
    user-agent, and the JS capture bridge. Used by both the headless scraper and the
    visible WebView screen so behavior and session state are identical.
- **iosMain**
  - `WebScraper` bound to an implementation that reports unavailable; the UPS source
    descriptor stays `implemented = false` on iOS.

**`:source:ups`** changes from stub to `UpsWebSource : WebViewBasedSource`, contributing
only a `WebProviderSpec` for UPS plus its existing `detectCarrier` regex. Sources still
never touch the database — `track()` returns a `TrackingSnapshot` exactly as today, so
`ParcelRepository`, `SourceRegistry`, and the Settings screen keep working unchanged.

## 2. Provider spec

```kotlin
class WebProviderSpec(
    val sourceId: String,
    val carrier: Carrier,
    val trackingUrl: (trackingNumber: String) -> String,
    val loginUrl: String,
    val isLoggedInJs: String,               // evaluates to a boolean in page context
    val apiUrlPatterns: List<Regex>,        // page-internal JSON endpoints to capture
    val extractionJs: String,               // DOM-fallback extractor -> ScrapedTracking JSON
    val challengeMarkers: List<String>,     // bot-wall detection strings
    val parse: (String) -> TrackingSnapshot?, // captured/extracted JSON -> domain snapshot
)
```

Adding FedEx/USPS/DHL later = write a spec + a thin `WebViewBasedSource` subclass.

## 3. Extraction pipeline

All three layers emit the same canonical `ScrapedTracking` JSON, so parsing downstream of
the bridge is identical regardless of which layer produced the data:

1. **API capture (primary).** Using `androidx.webkit`'s `addDocumentStartJavaScript`, a
   bridge script hooks `window.fetch` and `XMLHttpRequest` before any page script runs.
   Responses whose URLs match `apiUrlPatterns` are posted to Kotlin via a
   `WebMessageListener`. For UPS, the tracking page populates itself from an internal
   JSON endpoint, so this yields structured data with no DOM parsing.
2. **DOM extraction (fallback).** After page quiescence (page finished + short settle
   delay + no matching API capture), run `extractionJs` over the rendered DOM.
3. **AI fallback (hook only in v1).** `AiPageExtractor.extract(pageText)` is called when
   layers 1–2 fail; the v1 binding is a no-op returning null. A later phase implements it
   with ML Kit GenAI / Gemini Nano on supported devices.

## 4. Login and cookies

- Android `CookieManager` is app-global and persists to disk automatically; logging in
  once makes the session available to every WebView in the app, visible or headless.
  `flush()` after login to force persistence.
- New capability marker `WebLoginCapable` on the source. Settings renders a
  "Sign in to UPS" action for sources with this capability, which opens the WebView
  screen at `loginUrl`. `isLoggedInJs` detects success; we record `loggedIn=true` in the
  existing `SourceConfig` values. Cookies themselves never leave the WebView store —
  no secrets go into DataStore.
- Sign-out clears the provider domain's cookies and resets the flag.
- Login is optional: anonymous scrapes work; logged-in sessions get richer data and fewer
  bot walls. The visible web screen shows a "sign in for more detail" banner when
  applicable.

## 5. Phase 1 — visible WebView + scrape-on-view

- New Nav3 route `WebDetailRoute(parcelId)` and `WebDetailScreen` in `:ui`, with an
  `expect`/`actual` `PlatformWebView` composable. Android actual wraps a `WebView` via
  `AndroidView`, wired to `WebSession` (same cookies + bridge as headless). iOS actual is
  a "not available yet" placeholder.
- `DetailScreen` gets a "More details" button, shown when the registry has a web-capable
  source for the parcel's carrier.
- While the user views the page, the capture bridge runs; on success the snapshot is
  written through a new `ParcelRepository.applySnapshot(parcelId, snapshot)`, so the
  timeline is fresh when the user navigates back.

## 6. Phase 2 — headless scraping in normal refresh

- `HeadlessWebViewScraper`: one lazily created off-screen WebView (created and driven on
  the main thread, never attached to a window), a Mutex-serialized scrape queue, ~25 s
  timeout per scrape.
- `UpsWebSource.track()` delegates to it, so UPS parcels update during pull-to-refresh
  and on-foreground refresh like any other source, honoring the existing
  `RefreshFrequency` staleness rules.
- Politeness throttle: minimum ~15 min between headless scrapes of the same parcel, even
  on forced refresh.
- WorkManager/background execution remains out of scope (v1 spec: foreground only). The
  scraper is UI-independent, so a future background phase is additive.

## 7. Error handling

Map scrape failures onto the existing `FailureReason` taxonomy:

| Condition | Result |
|---|---|
| Login wall / expired session detected | `AUTH` |
| Timeout, offline, page load error | `NETWORK` |
| Carrier page reports unknown number | `NOT_FOUND` |
| Bot challenge page (via `challengeMarkers`) | `RATE_LIMITED` |
| Bridge returned unparseable data | `UNKNOWN` |

The visible WebView is the escape hatch: a bot challenge surfaced during headless refresh
prompts the user to open the More-details page, where solving the challenge also repairs
the shared session for future headless scrapes.

## 8. Testing

- Everything downstream of "JSON arrives from the bridge" is pure Kotlin: commonMain unit
  tests parse recorded UPS API responses and extractor-output fixtures into
  `TrackingSnapshot`s, including malformed/partial payloads.
- `WebViewBasedSource` flow tests with a fake `WebScraper` (success, each failure mode,
  timeout) — no WebView required.
- Bridge/extraction JS is kept small and versioned as string assets; live-page behavior
  is a documented manual QA checklist (anonymous scrape, logged-in scrape, bot challenge,
  session expiry), since real carrier pages cannot run in unit tests.

## 9. Out of scope / future phases

- WorkManager background scraping (requires revisiting the v1 foreground-only spec).
- iOS WKWebView actuals (`WKHTTPCookieStore`, `WKContentWorld` script injection).
- My Choice account-wide package discovery/import.
- Real `AiPageExtractor` implementation (ML Kit GenAI / Gemini Nano).
- Additional providers (FedEx, USPS, DHL) via new `WebProviderSpec`s.

## Appendix: Considered alternative — KMP WebView wrapper library

Evaluated `io.github.kevinnzou:compose-webview-multiplatform` (the library staged, but
unused, in HackerNews-KMP's version catalog) for the visible WebView. Decision:
**not for v1**, reconsider at the iOS phase.

- Its last release (2.0.3) is from August 2024 — unvalidated against Kotlin 2.4 /
  Compose MP 1.11, and the ecosystem has fragmented into forks
  (vickyleu's fork, `parkwoocheol/compose-webview`).
- Its main value is iOS/desktop composables, which are out of scope for v1; the Android
  visible WebView is trivial `AndroidView` interop.
- The scraping design needs `androidx.webkit` document-start injection,
  `WebMessageListener`, `CookieManager.flush()`, and a custom `WebViewClient` — all below
  the wrapper's abstraction (reachable only via its `onCreated` native-view escape hatch).
- Phase 2's headless scraper cannot use a Compose-only wrapper at all.

When implementing the iOS "More details" screen, re-evaluate the healthiest option
(KevinnZou's library, a maintained fork, or `parkwoocheol/compose-webview`) as a drop-in
behind our own `PlatformWebView` expect/actual seam — the design's interfaces are ours,
so this swap stays contained.

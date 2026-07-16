# Amazon WebView Tracking Source — Design

**Date:** 2026-07-15
**Status:** Approved

## Goal

Add Amazon order tracking as the third provider on the WebView scraping framework
(see `2026-07-12-webview-tracking-source-design.md`). Amazon differs from UPS/USPS in
two ways that shape this design: **login is required** (no anonymous tracking page),
and the data spans **two pages** — the order-details page lists shipments, and each
shipment's full event history lives one click deeper on its progress-tracker page.

Users paste an Amazon **order ID** (e.g. `113-1234567-1234567`) from their
confirmation email. US `amazon.com` only for v1.

## Decisions (settled during brainstorming)

- **Identifier: order IDs**, not TBA shipment IDs — it's the number users actually
  see, and it matches the login-required expectation.
- **Multi-package orders: track the first undelivered shipment**, falling back to the
  last one when everything is delivered. One snapshot per tracked item, matching the
  app's one-number-one-card model.
- **Approach: two-hop scrape with fallback.** Land on order-details, pick the target
  shipment, hop to its progress tracker for full events; if the hop yields nothing
  readable, the coarse status already extracted from order-details is the result.
  Chosen over (a) constructing a progress-tracker URL directly from the order ID
  (unverified URL shape; Amazon, not us, would pick the shipment) and (b)
  order-details-only extraction (no event history).
- **DOM-only for v1:** no `apiUrlPatterns`, `parseApi` returns `null`. Amazon has no
  stable public tracking-JSON vocabulary to target blind; ScrapeTracer during live QA
  can justify adding API capture later. This deliberately diverges from the UPS/USPS
  recipe.

## 1. Framework extension — the bounded `goto` hop (`:source:webview`)

The only shared-machinery change. The extraction contract gains one page kind:
`{page: 'goto', url: '...', tracking: <coarse ScrapedTracking?>}`.

- `DomExtraction` gains an optional `url` field.
- `PayloadRouter` gains `RouteResult.Goto(url, tracking?)`. A `goto` payload whose URL
  is not https, not within the spec's registrable `cookieDomain`, or missing entirely
  routes to `Unparsed`.
- `HeadlessWebViewScraper` watches dom payloads: on a valid `goto` it navigates once
  more **in the same session** and re-runs extraction on the new page. Hard bounds:
  max one hop per scrape; a second `goto` is ignored. The scrape timeout continues to
  cover the whole session, both hops included.
- `WebViewBasedSource` result priority: any full `Tracking` payload wins; otherwise
  the `Goto` payload's embedded coarse tracking; otherwise the existing
  loginWall/challenge/notFound/unknown ladder.
- UPS/USPS never emit `goto`; their behavior is untouched.

## 2. Module — `:source:amazon`

New Gradle module mirroring `:source:usps` (webview dependency, serialization,
datetime), registered in `settings.gradle.kts` and the app's module wiring alongside
the other sources. Domain gains
`WellKnownCarriers.AMAZON = Carrier("amazon", "Amazon", "#995C00")` — Amazon orange
(#FF9900) darkened to match the muted UPS/USPS/FedEx accent palette.

- **`AmazonWebSpec.kt`**:
  - `sourceId = "amazon"`, `carrier = WellKnownCarriers.AMAZON`,
    `cookieDomain = "amazon.com"`
  - `trackingUrl = { "https://www.amazon.com/gp/your-account/order-details?orderID=$it" }`
    — landing on order-details is what makes the multi-package rule work, and it's a
    sensible page for the visible More-details screen.
  - `loginUrl = "https://www.amazon.com/gp/sign-in.html"`
  - `isLoggedInJs` — account-nav greeting markers (placeholder until live QA)
  - `apiUrlPatterns = emptyList()`, `parseApi = { _, _ -> null }`
  - `challengeMarkers` — Amazon captcha/robot vocabulary: "Enter the characters you
    see", "not a robot", "automated access". Challenges route to the existing
    RATE_LIMITED → More-details repair loop where the **user** solves the check in the
    visible WebView; the app never auto-solves.
  - `extractionJs` — see §3.
- **`AmazonSource.kt`** — standard thin subclass;
  `detectCarrier` matches `^\d{3}-\d{7}-\d{7}$`.

No config fields; auth is the cookie session from the existing `WebLoginScreen` /
Settings plumbing, discovered generically via `WebCapableSource`. On iOS the
descriptor stays `implemented = false` automatically.

## 3. DOM extractor

One function expression that branches on which page it's running on:

- **Order-details page:**
  - signed-out redirect / sign-in form markers → `loginWall`
  - "problem finding this order" wording → `notFound`
  - otherwise scan shipment cards, classify each status line — ordered / not yet
    shipped → LABEL_CREATED; shipped → SHIPPED; in transit / arriving → IN_TRANSIT;
    out for delivery → OUT_FOR_DELIVERY; delivered → DELIVERED; delayed /
    undeliverable / return → EXCEPTION — pick the **first undelivered** card
    (fallback: last card), and emit `{page:'goto', url: <its Track-package link>,
    tracking: <coarse status>}`.
- **Progress-tracker page:** full extraction — status headline, expected delivery,
  event history (date, time, description, location), same keyword classification.
- **Either page:** challenge markers → `challenge`; no recognizable DOM → `empty`.

Selector constants are placeholders-by-design until live QA, as UPS's and USPS's
were; the returned JSON shape is what unit tests and `PayloadRouter` lock down. Event
timestamps follow the same device-zone tradeoff documented for `UpsApiParser`.

## 4. Error mapping

Fully inherited from `WebViewBasedSource`. The mandatory-login UX is exactly the
existing AUTH path: loginWall → "Sign in to Amazon in Settings, then refresh". No new
UI.

## 5. Testing

- **`AmazonSourceTest`** — `detectCarrier` accepts 3-7-7 digit order IDs, rejects
  UPS/USPS/FedEx shapes; descriptor id/kind; no config fields.
- **`PayloadRouterTest` additions** — `goto` routing: valid hop; embedded fallback
  tracking preserved; off-domain, http, and missing-url payloads → `Unparsed`.
- **`WebViewBasedSourceTest` additions** — result priority: rich `Tracking` beats the
  `Goto` coarse fallback; coarse fallback used when the hop yields nothing; hop-less
  providers unaffected.
- Scraper hop-bounding (one hop max) verified in live QA; the scraper is
  androidMain and has no unit harness, consistent with the framework spec.

## 6. Live QA (device, follows implementation)

New checklist `docs/superpowers/qa/2026-07-15-webview-amazon-qa.md` mirroring USPS's:
ScrapeTracer (`adb logcat -s ShipScrape`) to fix selectors and `isLoggedInJs`; the
sign-in flow (Doug logs in with his own credentials on-device — credentials are never
handled by tooling); multi-package and single-package orders; delivered order; bogus
order ID; signed-out AUTH path; captcha repair loop; throttle; airplane mode.

## Out of scope

- Non-US Amazon domains
- Per-shipment multi-card fidelity (one order → several tracked cards)
- "Your Orders" auto-import / order discovery — **planned as the immediate follow-up
  feature** (decided 2026-07-15): discovery composes on top of this source (login
  session, `goto` hop, extractors are prerequisites), but its sync/dedup/card-lifecycle
  questions get their own brainstorm and spec.
- API-capture parser (`apiUrlPatterns`) — revisit after live QA
- iOS WKWebView scraper (tracked by the framework spec)

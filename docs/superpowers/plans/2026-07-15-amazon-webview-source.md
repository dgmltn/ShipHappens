# Amazon WebView Tracking Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Track Amazon orders by order ID (`113-1234567-1234567`) via a logged-in amazon.com WebView scrape, per `docs/superpowers/specs/2026-07-15-amazon-webview-source-design.md`.

**Architecture:** Third provider on the existing WebView scraping framework. One generic framework extension in `:source:webview` — a bounded `goto` hop so a scrape can navigate from the order-details page to the chosen shipment's progress-tracker page — plus a new `:source:amazon` module (spec + thin source), a new `WellKnownCarriers.AMAZON`, and built-in order-ID detection. DOM-only extraction; no API capture in v1.

**Tech Stack:** Kotlin Multiplatform, Koin, kotlinx-serialization (webview module only), Android WebView (androidMain scraper).

## Global Constraints

- KMP library modules use the `com.android.kotlin.multiplatform.library` AGP-9 plugin, with android config inside `kotlin { android { } }` (see `source/usps/build.gradle.kts` as the template).
- Unit tests for KMP modules run on the JVM target: `./gradlew :<module>:jvmTest`. Android-source compile check: `./gradlew :source:webview:compileAndroidMain`. iOS compile check: `./gradlew :source:amazon:compileKotlinIosSimulatorArm64`.
- Commit subjects start with a bracketed one-word topic: `[domain]`, `[source]`, `[data]`, `[docs]`.
- JS selector constants inside extraction/login scripts are **placeholders-by-design**: they are finalized during live device QA (Amazon blocks meaningful off-device recon behind login). Unit tests lock the *shapes* (returned JSON contract, URL formats, routing), never live selectors.
- The scraper must never auto-solve captchas: challenge pages route to the existing RATE_LIMITED → "open More details" repair loop where the user acts.
- Before any `git add -A`, watch for an auto-generated `gradle/gradle-daemon-jvm.properties` — do NOT commit it.

---

### Task 1: `WellKnownCarriers.AMAZON`

**Files:**
- Modify: `domain/src/commonMain/kotlin/com/shiphappens/domain/Carrier.kt`
- Test: `domain/src/commonTest/kotlin/com/shiphappens/domain/ModelTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `WellKnownCarriers.AMAZON: Carrier` with `code = "amazon"`, `displayName = "Amazon"`, `accentColorHex = "#995C00"`, included in `WellKnownCarriers.all` (Tasks 5–7 reference it).

- [ ] **Step 1: Write the failing test**

Add to `ModelTest.kt` (inside `class ModelTest`, after `wellKnown_carriers_have_brand_colors`):

```kotlin
@Test fun amazon_carrier_is_well_known() {
    assertEquals("#995C00", WellKnownCarriers.AMAZON.accentColorHex)
    assertEquals(WellKnownCarriers.AMAZON, WellKnownCarriers.byCode("Amazon"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :domain:jvmTest --tests "com.shiphappens.domain.ModelTest" 2>&1 | tail -20`
Expected: compilation FAILS with `Unresolved reference 'AMAZON'`.

- [ ] **Step 3: Write minimal implementation**

In `Carrier.kt`, change the `WellKnownCarriers` object:

```kotlin
object WellKnownCarriers {
    val UPS = Carrier("ups", "UPS", "#5A3A22")
    val USPS = Carrier("usps", "USPS", "#1E3A8F")
    val FEDEX = Carrier("fedex", "FedEx", "#5A1B9A")
    // Amazon orange (#FF9900) darkened to sit with the muted brand accents above.
    val AMAZON = Carrier("amazon", "Amazon", "#995C00")
    val all = listOf(UPS, USPS, FEDEX, AMAZON)
    fun byCode(code: String): Carrier? = all.firstOrNull { it.code == code.trim().lowercase() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :domain:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add domain/src
git commit -m "[domain] Add Amazon to well-known carriers"
```

---

### Task 2: `goto` routing in PayloadRouter

**Files:**
- Modify: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/PayloadRouter.kt`
- Test: `source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/PayloadRouterTest.kt`

**Interfaces:**
- Consumes: existing `BridgePayload`, `DomExtraction`, `WebProviderSpec.cookieDomain`.
- Produces (Tasks 3 & 4 rely on these exact shapes):
  - `DomExtraction(page: String, url: String? = null, tracking: ScrapedTracking? = null)` — new `url` field.
  - `RouteResult.Goto(val url: String, val tracking: ScrapedTracking?)` — new sealed member.
  - `internal fun isAllowedHopUrl(url: String, domain: String): Boolean` (top level in `PayloadRouter.kt`).
  - Routing rule: dom payload with `page == "goto"` → `Goto` when the URL passes `isAllowedHopUrl`; otherwise degrades to `Tracking(embedded)` when coarse tracking is present, else `Unparsed`.

- [ ] **Step 1: Write the failing tests**

Add to `PayloadRouterTest.kt` (inside `class PayloadRouterTest`; note the file already imports `kotlin.test.assertEquals`/`assertIs` — also add `import kotlin.test.assertNull` and `import kotlin.test.assertTrue`, `import kotlin.test.assertFalse`):

```kotlin
private fun domPayload(body: String) =
    """{"kind":"dom","body":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(body))}}"""

@Test fun goto_payload_routes_to_goto_with_embedded_tracking() {
    val body = """{"page":"goto","url":"https://www.example.com/track/2","tracking":{"status":"IN_TRANSIT"}}"""
    val goto = assertIs<RouteResult.Goto>(PayloadRouter(testSpec()).route(domPayload(body)))
    assertEquals("https://www.example.com/track/2", goto.url)
    assertEquals("IN_TRANSIT", goto.tracking?.status)
}

@Test fun goto_without_tracking_still_routes_to_goto() {
    val body = """{"page":"goto","url":"https://example.com/track/2"}"""
    val goto = assertIs<RouteResult.Goto>(PayloadRouter(testSpec()).route(domPayload(body)))
    assertNull(goto.tracking)
}

@Test fun goto_with_disallowed_url_degrades_to_embedded_tracking() {
    val badUrls = listOf(
        "http://www.example.com/track/2",        // not https
        "https://evil.com/track/2",              // foreign host
        "https://evilexample.com/track/2",       // suffix trick — not a subdomain
        "https://example.com@evil.com/track/2",  // userinfo smuggling
    )
    for (bad in badUrls) {
        val body = """{"page":"goto","url":"$bad","tracking":{"status":"SHIPPED"}}"""
        val result = PayloadRouter(testSpec()).route(domPayload(body))
        assertEquals("SHIPPED", assertIs<RouteResult.Tracking>(result).tracking.status, "url: $bad")
    }
}

@Test fun goto_with_disallowed_or_missing_url_and_no_tracking_is_unparsed() {
    assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route(domPayload("""{"page":"goto","url":"https://evil.com/x"}""")))
    assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route(domPayload("""{"page":"goto"}""")))
}

@Test fun hop_url_allowlist_semantics() {
    assertTrue(isAllowedHopUrl("https://example.com/a", "example.com"))
    assertTrue(isAllowedHopUrl("https://www.example.com:443/a?b#c", "example.com"))
    assertFalse(isAllowedHopUrl("https://evilexample.com/a", "example.com"))
    assertFalse(isAllowedHopUrl("http://example.com/a", "example.com"))
    assertFalse(isAllowedHopUrl("https://example.com@evil.com/a", "example.com"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :source:webview:jvmTest --tests "com.shiphappens.source.webview.PayloadRouterTest" 2>&1 | tail -20`
Expected: compilation FAILS with `Unresolved reference 'Goto'` / `'isAllowedHopUrl'`.

- [ ] **Step 3: Implement**

In `PayloadRouter.kt`:

```kotlin
/** Parsed body of a `kind == "dom"` payload (the extraction runner's output). */
@Serializable
data class DomExtraction(val page: String, val url: String? = null, val tracking: ScrapedTracking? = null)

sealed interface RouteResult {
    data class Tracking(val tracking: ScrapedTracking) : RouteResult
    /** A validated one-hop navigation request from the extractor, optionally carrying a coarse
     *  tracking fallback extracted from the page that requested the hop (design spec §1). */
    data class Goto(val url: String, val tracking: ScrapedTracking?) : RouteResult
    data object NotFound : RouteResult
    data object LoginWall : RouteResult
    data object Challenge : RouteResult
    data object Unparsed : RouteResult
}

/**
 * True when [url] is an https URL whose host is [domain] or a subdomain of it — the only targets
 * a 'goto' hop may navigate to. Plain string parsing (commonMain has no platform URL class);
 * an authority containing userinfo ('@') is rejected outright rather than parsed around.
 */
internal fun isAllowedHopUrl(url: String, domain: String): Boolean {
    if (!url.startsWith("https://")) return false
    val authority = url.removePrefix("https://").takeWhile { it != '/' && it != '?' && it != '#' }
    if ('@' in authority) return false
    val host = authority.substringBefore(':').lowercase()
    val d = domain.lowercase()
    return host == d || host.endsWith(".$d")
}
```

and in `route(...)`, inside the `"dom"` branch's `when (dom.page)`:

```kotlin
"ok" -> dom.tracking?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
"goto" ->
    if (dom.url != null && isAllowedHopUrl(dom.url, spec.cookieDomain)) {
        RouteResult.Goto(dom.url, dom.tracking)
    } else {
        // Bad hop target: salvage the coarse tracking if the extractor sent one.
        dom.tracking?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
    }
"notFound" -> RouteResult.NotFound
```

(`notFound`/`loginWall`/`challenge`/`else` branches unchanged.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :source:webview:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL — new tests pass and every pre-existing router/source/bridge test still passes.

- [ ] **Step 5: Commit**

```bash
git add source/webview/src
git commit -m "[source] Route dom goto payloads with same-domain https validation"
```

---

### Task 3: Coarse-fallback priority in WebViewBasedSource

**Files:**
- Modify: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/WebViewBasedSource.kt`
- Test: `source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/WebViewBasedSourceTest.kt`

**Interfaces:**
- Consumes: `RouteResult.Goto(url, tracking)` from Task 2.
- Produces: result priority contract — full `Tracking` > `Goto.tracking` (coarse) > loginWall/challenge/notFound/unknown ladder. Task 8's QA relies on this being the shipped behavior.

- [ ] **Step 1: Write the failing tests**

Add to `WebViewBasedSourceTest.kt` (file-private helpers exist: `FakeScraper`, `TestWebSource`, `dom(page)`):

```kotlin
private fun gotoDom(withTracking: Boolean): String {
    val tracking = if (withTracking) ""","tracking":{"status":"IN_TRANSIT"}""" else ""
    val body = """{"page":"goto","url":"https://www.example.com/t/2"$tracking}"""
    return """{"kind":"dom","body":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(body))}}"""
}
```

and inside `class WebViewBasedSourceTest`:

```kotlin
@Test fun goto_coarse_tracking_is_the_fallback_result() = runTest {
    val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(gotoDom(withTracking = true), dom("empty")))))
    val result = assertIs<SourceResult.Success<com.shiphappens.domain.TrackingSnapshot>>(src.track("113", null))
    assertEquals(TrackingStatus.IN_TRANSIT, result.value.status)
}

@Test fun rich_tracking_beats_goto_coarse() = runTest {
    val rich = """{"kind":"dom","body":"{\"page\":\"ok\",\"tracking\":{\"status\":\"DELIVERED\"}}"}"""
    val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(gotoDom(withTracking = true), rich))))
    val result = assertIs<SourceResult.Success<com.shiphappens.domain.TrackingSnapshot>>(src.track("113", null))
    assertEquals(TrackingStatus.DELIVERED, result.value.status)
}

@Test fun goto_coarse_beats_error_signals() = runTest {
    // The order page proved we're logged in and produced a status; a confused post-hop page
    // must not turn that into an AUTH failure.
    val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(gotoDom(withTracking = true), dom("loginWall")))))
    assertIs<SourceResult.Success<com.shiphappens.domain.TrackingSnapshot>>(src.track("113", null))
}

@Test fun goto_without_tracking_alone_is_unknown_failure() = runTest {
    val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(gotoDom(withTracking = false)))))
    assertEquals(FailureReason.UNKNOWN, assertIs<SourceResult.Failure>(src.track("113", null)).reason)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :source:webview:jvmTest --tests "com.shiphappens.source.webview.WebViewBasedSourceTest" 2>&1 | tail -20`
Expected: `goto_coarse_tracking_is_the_fallback_result`, `goto_coarse_beats_error_signals` FAIL (track returns UNKNOWN failure — Goto isn't consulted yet). The other two may pass; that's fine.

- [ ] **Step 3: Implement**

In `WebViewBasedSource.track`, the `Payloads` branch becomes:

```kotlin
is ScrapeResult.Payloads -> {
    val router = PayloadRouter(webSpec)
    val routed = result.payloads.map(router::route)
    routed.firstNotNullOfOrNull { (it as? RouteResult.Tracking)?.tracking }
        ?.let { return SourceResult.Success(it.toSnapshot()) }
    // A goto hop's embedded coarse tracking is the designed fallback when the hop's target
    // page never produced a rich extraction (design spec §1) — and it outranks the error
    // ladder because the page that emitted it was already past login and order lookup.
    routed.firstNotNullOfOrNull { (it as? RouteResult.Goto)?.tracking }
        ?.let { return SourceResult.Success(it.toSnapshot()) }
    when {
        routed.any { it is RouteResult.LoginWall } ->
            SourceResult.Failure(FailureReason.AUTH, "Sign in to $name in Settings, then refresh")
        routed.any { it is RouteResult.Challenge } ->
            SourceResult.Failure(FailureReason.RATE_LIMITED, "$name wants a human check — open More details to continue")
        routed.any { it is RouteResult.NotFound } ->
            SourceResult.Failure(FailureReason.NOT_FOUND, "$name doesn't recognize this number")
        else -> SourceResult.Failure(FailureReason.UNKNOWN, "Couldn't read tracking data from $name")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :source:webview:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add source/webview/src
git commit -m "[source] Use goto coarse tracking as scrape fallback result"
```

---

### Task 4: One-hop navigation in HeadlessWebViewScraper

**Files:**
- Modify: `source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/HeadlessWebViewScraper.kt`
- Modify: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/debug/ScrapeTracer.kt`
- Modify: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/debug/LoggingScrapeTracer.kt`

**Interfaces:**
- Consumes: `RouteResult.Goto` from Task 2.
- Produces: scraper behavior — first `Goto` payload triggers one same-session navigation; any subsequent `Goto` ends the scrape; post-hop, the target page's dom payload ends the scrape even when it routes `Unparsed`; results whose payloads include a `Goto` with coarse tracking are throttle-cached. New tracer event `hopStarted(sourceId, url)`.

There is no unit harness for the androidMain scraper (consistent with the framework spec) — this task is verified by compilation plus Task 8's live QA.

- [ ] **Step 1: Add the tracer event**

In `ScrapeTracer.kt`, after `pageFinished`:

```kotlin
/** The extractor requested a goto hop and the scraper is navigating to [url] in-session. */
fun hopStarted(sourceId: String, url: String) {}
```

In `LoggingScrapeTracer.kt`, after `pageFinished`:

```kotlin
override fun hopStarted(sourceId: String, url: String) = trace { log.i { "[$sourceId] goto HOP url=$url" } }
```

- [ ] **Step 2: Implement the hop in `runScrape`**

Add imports to `HeadlessWebViewScraper.kt`:

```kotlin
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json
```

Add fields to the class:

```kotlin
private val mainHandler = Handler(Looper.getMainLooper())
private val json = Json { ignoreUnknownKeys = true }

private fun isDomPayload(payload: String): Boolean =
    runCatching { json.decodeFromString<BridgePayload>(payload).kind }.getOrNull() == "dom"
```

Replace the `onPayload` lambda inside `runScrape` (the `payloads`/`done`/`router` declarations stay; add `val hopped = AtomicBoolean(false)` next to them):

```kotlin
onPayload = { payload ->
    synchronized(payloads) { payloads += payload }
    fun completeWithPayloads() = done.complete(ScrapeResult.Payloads(synchronized(payloads) { payloads.toList() }))
    // Complete as soon as a payload routes to a DEFINITIVE outcome — normally the
    // captured API JSON (Tracking), which lands well before the heavy UPS SPA fires
    // onPageFinished (and sometimes it never fires at all). A DOM "page:empty"
    // result routes to Unparsed and must NOT complete the scrape: the DOM extractor
    // frequently runs before the tracking XHR lands, so completing on empty would
    // discard the API capture that arrives moments later.
    when (val routed = router.route(payload)) {
        is RouteResult.Tracking, is RouteResult.LoginWall,
        is RouteResult.Challenge, is RouteResult.NotFound -> completeWithPayloads()
        is RouteResult.Goto ->
            // Bounded to ONE hop per scrape (design spec §1): the first goto navigates to
            // the shipment tracker within the same session/cookies; any later goto ends the
            // scrape instead, so a page cycle can't loop the WebView until timeout.
            if (hopped.compareAndSet(false, true)) {
                tracer.hopStarted(spec.sourceId, routed.url)
                // This callback runs on the WebView JavaBridge thread; WebView methods
                // must be called on main. The view may already be destroyed — absorb.
                mainHandler.post { runCatching { webView.loadUrl(routed.url) } }
            } else {
                completeWithPayloads()
            }
        is RouteResult.Unparsed ->
            // Pre-hop: keep waiting (empty DOM often precedes the API capture). Post-hop:
            // the target page's dom payload is the only terminator a DOM-only provider
            // will ever send, so even page:'empty' ends the scrape — the goto's embedded
            // coarse tracking still yields a result downstream.
            if (hopped.get() && isDomPayload(payload)) completeWithPayloads()
    }
},
```

- [ ] **Step 3: Make coarse-bearing results cacheable**

In `scrape(...)`, the summary block's condition currently caches only on `RouteResult.Tracking`. Replace:

```kotlin
if (routed.any { it is RouteResult.Tracking }) {
```

with:

```kotlin
// A Goto with embedded coarse tracking is a genuine result (WebViewBasedSource will
// surface it), so it gets the same politeness caching as a rich extraction.
if (routed.any { it is RouteResult.Tracking || (it is RouteResult.Goto && it.tracking != null) }) {
```

- [ ] **Step 4: Verify compilation and the shared-module tests**

Run: `./gradlew :source:webview:compileAndroidMain :source:webview:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add source/webview/src
git commit -m "[source] Add bounded goto hop to headless scraper"
```

---

### Task 5: `:source:amazon` module with AmazonWebSpec

**Files:**
- Modify: `settings.gradle.kts`
- Create: `source/amazon/build.gradle.kts`
- Create: `source/amazon/src/commonMain/kotlin/com/shiphappens/source/amazon/AmazonWebSpec.kt`
- Test: `source/amazon/src/commonTest/kotlin/com/shiphappens/source/amazon/AmazonWebSpecTest.kt`

**Interfaces:**
- Consumes: `WebProviderSpec` (unchanged), `WellKnownCarriers.AMAZON` (Task 1), the `goto` extraction contract (Task 2).
- Produces: `AmazonWebSpec: WebProviderSpec` with `sourceId = "amazon"`, `cookieDomain = "amazon.com"` (Task 6 wraps it in the source class).

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, after `include(":source:usps")`:

```kotlin
include(":source:amazon")
```

- [ ] **Step 2: Create `source/amazon/build.gradle.kts`**

Like `:source:usps` but without the serialization plugin/deps — the spec is DOM-only with no API parser:

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.shiphappens.source.amazon"
        compileSdk = providers.gradleProperty("shiphappens.compileSdk").get().toInt()
        minSdk = providers.gradleProperty("shiphappens.minSdk").get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(projects.source.api)
            api(projects.source.webview)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
```

- [ ] **Step 3: Write the failing test**

Create `AmazonWebSpecTest.kt`:

```kotlin
package com.shiphappens.source.amazon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmazonWebSpecTest {

    @Test fun tracking_url_targets_order_details_with_hyphenated_id() {
        assertEquals(
            "https://www.amazon.com/gp/your-account/order-details?orderID=113-1234567-1234567",
            AmazonWebSpec.trackingUrl("113-1234567-1234567"),
        )
    }

    @Test fun tracking_url_rehyphenates_normalized_ids() {
        // normalizeTracking strips hyphens app-wide; the URL must restore the 3-7-7 shape.
        assertEquals(
            "https://www.amazon.com/gp/your-account/order-details?orderID=113-1234567-1234567",
            AmazonWebSpec.trackingUrl("11312345671234567"),
        )
    }

    @Test fun spec_identity_and_origins() {
        assertEquals("amazon", AmazonWebSpec.sourceId)
        assertEquals("amazon.com", AmazonWebSpec.cookieDomain)
        assertEquals(listOf("https://*.amazon.com", "https://amazon.com"), AmazonWebSpec.allowedOriginRules())
        assertTrue(AmazonWebSpec.challengeMarkers.isNotEmpty())
    }

    @Test fun api_capture_is_disabled_in_v1() {
        assertTrue(AmazonWebSpec.apiUrlPatterns.isEmpty())
        assertNull(AmazonWebSpec.parseApi("https://www.amazon.com/x", """{"anything":true}"""))
    }

    @Test fun extraction_js_is_a_function_expression_covering_both_pages() {
        assertTrue(AmazonWebSpec.extractionJs.trimStart().startsWith("function"))
        assertTrue(AmazonWebSpec.extractionJs.contains("progress-tracker"))   // tracker-page branch
        assertTrue(AmazonWebSpec.extractionJs.contains("'goto'"))             // order-details hop
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :source:amazon:jvmTest 2>&1 | tail -20`
Expected: compilation FAILS with `Unresolved reference 'AmazonWebSpec'`.

- [ ] **Step 5: Create `AmazonWebSpec.kt`**

```kotlin
package com.shiphappens.source.amazon

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.webview.WebProviderSpec

// DOM extractor for BOTH Amazon pages a scrape can visit (design spec §3). The scrape lands on
// the order-details page (needs a signed-in session), picks the first undelivered shipment, and
// emits {page:'goto'} toward its progress-tracker page, carrying the shipment's coarse status as
// the fallback tracking. On the tracker page it extracts the full event history. Selector
// constants are validated against the live site during device QA (Amazon requires login, so
// off-device recon can't see these pages); the returned JSON shape is what unit tests and
// PayloadRouter lock down.
private val AMAZON_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  var href = location.href;

  // Signed-out: order pages bounce to /ap/signin (selector fallbacks for A/B variants).
  if (/\/ap\/signin/.test(href) || document.querySelector('form[name="signIn"], #ap_email, #signInSubmit')) return {page: 'loginWall'};

  function classify(raw) {
    var t = (raw || '').toLowerCase();
    if (t.indexOf('out for delivery') >= 0) return 'OUT_FOR_DELIVERY';
    if (t.indexOf('delivered') >= 0) return 'DELIVERED';
    if (t.indexOf('undeliverable') >= 0 || t.indexOf('running late') >= 0 || t.indexOf('delayed') >= 0 ||
        t.indexOf('problem') >= 0 || t.indexOf('return') >= 0 || t.indexOf('lost') >= 0) return 'EXCEPTION';
    if (t.indexOf('not yet shipped') >= 0 || t.indexOf('not shipped') >= 0 || t.indexOf('order placed') >= 0 ||
        t.indexOf('ordered') >= 0 || t.indexOf('preparing for shipment') >= 0) return 'LABEL_CREATED';
    if (t.indexOf('shipped') >= 0 || t.indexOf('dispatched') >= 0 || t.indexOf('picked up') >= 0) return 'SHIPPED';
    if (t.indexOf('arriving') >= 0 || t.indexOf('arrives') >= 0 || t.indexOf('in transit') >= 0 ||
        t.indexOf('on the way') >= 0 || t.indexOf('on its way') >= 0 || t.indexOf('at carrier') >= 0) return 'IN_TRANSIT';
    return 'UNKNOWN';
  }
  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  // Amazon's day labels ("Today", "Yesterday", "Tuesday, July 15") omit the year; V8 would guess
  // 2001, so append the current year, with a rollover guard for December events read in January.
  function parseDay(label) {
    var now = new Date();
    var l = (label || '').toLowerCase();
    if (l.indexOf('today') >= 0) return now;
    if (l.indexOf('yesterday') >= 0) return new Date(now.getTime() - 864e5);
    var d = new Date(('' + label).replace(/^[a-z]+,\s*/i, '') + ' ' + now.getFullYear());
    if (isNaN(d.getTime())) return null;
    if (d.getTime() - now.getTime() > 45 * 864e5) d.setFullYear(d.getFullYear() - 1);
    return d;
  }
  function isoDate(d) {
    return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
  }

  if (/progress-tracker|ship-track/.test(href)) {
    // --- Shipment tracker page: the rich layer ---
    var statusEl = document.querySelector('#primaryStatus')
      || document.querySelector('[class*="pt-status-main"]')
      || document.querySelector('#shipment-status-container h1, .promise-slot h1');
    var statusText = clean(statusEl);
    var events = [];
    var container = document.querySelector('#tracking-events-container') || document.body;
    var nodes = container.querySelectorAll('[class*="tracking-event"]');
    var day = null;
    for (var i = 0; i < nodes.length; i++) {
      var cls = '' + nodes[i].className;
      if (cls.indexOf('date-header') >= 0) { day = parseDay(clean(nodes[i])); continue; }
      var msg = clean(nodes[i].querySelector('[class*="event-message"], .tracking-event-message'));
      if (!msg || !day) continue;
      var timeText = clean(nodes[i].querySelector('[class*="event-time"], .tracking-event-time'));
      var ts = new Date(day.toDateString() + ' ' + (timeText || '00:00'));
      if (isNaN(ts.getTime())) ts = new Date(day.toDateString());
      var st = classify(msg);
      events.push({
        timestamp: ts.toISOString(),
        description: msg,
        location: clean(nodes[i].querySelector('[class*="event-location"], .tracking-event-location')),
        status: st === 'UNKNOWN' ? null : st
      });
    }
    events.reverse();  // page lists newest first; canonical order is ascending
    if (!statusText && !events.length) return {page: 'empty'};
    var etaDay = parseDay(clean(document.querySelector('[class*="promise"], #expected-delivery-date')));
    if (!etaDay && statusText && /arriving/i.test(statusText)) etaDay = parseDay(statusText.replace(/.*arriving/i, ''));
    var newestLoc = null;
    for (var j = events.length - 1; j >= 0; j--) { if (events[j].location) { newestLoc = events[j].location; break; } }
    return {page: 'ok', tracking: {
      status: classify(statusText || (events.length ? events[events.length - 1].description : '')),
      etaDate: etaDay ? isoDate(etaDay) : null,
      location: newestLoc,
      events: events
    }};
  }

  // --- Order-details page: pick the target shipment, hop to its tracker ---
  if (/problem finding this order|couldn't find that order|can't find that order|not a valid order/i.test(text)) return {page: 'notFound'};
  var cards = document.querySelectorAll('.shipment, [class*="shipment-info-container"], [data-component="shipments"] .a-box');
  var picks = [];
  for (var k = 0; k < cards.length; k++) {
    var head = clean(cards[k].querySelector('.shipment-top-row, [class*="shipment-status"], h4, h5')) || '';
    picks.push({card: cards[k], status: classify(head)});
  }
  // First undelivered shipment; when everything is delivered, the last card (design spec §Decisions).
  var pick = null;
  for (var m = 0; m < picks.length; m++) { if (picks[m].status !== 'DELIVERED') { pick = picks[m]; break; } }
  if (!pick && picks.length) pick = picks[picks.length - 1];
  if (!pick) return {page: 'empty'};
  var coarse = pick.status === 'UNKNOWN' ? null : {status: pick.status, etaDate: null, location: null, events: []};
  var link = pick.card.querySelector('a[href*="progress-tracker"], a[href*="ship-track"]');
  if (link && link.href) return {page: 'goto', url: link.href, tracking: coarse};
  if (coarse) return {page: 'ok', tracking: coarse};  // no tracker link (e.g. old delivered order)
  return {page: 'empty'};
}
""".trimIndent()

// Account-menu greeting on amazon.com chrome — validated in live QA like the selectors above.
private val AMAZON_IS_LOGGED_IN_JS = """
(function() {
  try {
    var nav = document.querySelector('#nav-link-accountList');
    if (nav) return !/sign in/i.test(nav.textContent || '');
    return /sign out/i.test((document.body && document.body.innerText) || '');
  } catch (e) { return false; }
})()
""".trimIndent()

val AmazonWebSpec = WebProviderSpec(
    sourceId = "amazon",
    carrier = WellKnownCarriers.AMAZON,
    cookieDomain = "amazon.com",
    trackingUrl = { raw ->
        // The app normalizes tracking numbers by stripping hyphens; Amazon's orderID param
        // needs the displayed 3-7-7 shape, so re-hyphenate a bare 17-digit id.
        val digits = raw.filter { it.isDigit() }
        val orderId = if (digits.length == 17) {
            "${digits.substring(0, 3)}-${digits.substring(3, 10)}-${digits.substring(10)}"
        } else raw
        "https://www.amazon.com/gp/your-account/order-details?orderID=$orderId"
    },
    loginUrl = "https://www.amazon.com/gp/sign-in.html",
    isLoggedInJs = AMAZON_IS_LOGGED_IN_JS,
    // DOM-only in v1 (design spec §Decisions): no stable public tracking-JSON vocabulary to
    // target blind. Live-QA ScrapeTracer captures can justify API patterns later.
    apiUrlPatterns = emptyList(),
    challengeMarkers = listOf(
        "Enter the characters you see",
        "Type the characters you see",
        "not a robot",
        "automated access to Amazon data",
    ),
    extractionJs = AMAZON_EXTRACTION_JS,
    parseApi = { _, _ -> null },
)
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :source:amazon:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, 5 tests pass.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts source/amazon
git commit -m "[source] Add :source:amazon module with two-page web spec"
```

---

### Task 6: AmazonWebSource + DI wiring

**Files:**
- Create: `source/amazon/src/commonMain/kotlin/com/shiphappens/source/amazon/AmazonSource.kt`
- Modify: `ui/build.gradle.kts` (commonMain dependencies block, next to `projects.source.usps`)
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt`
- Test: `source/amazon/src/commonTest/kotlin/com/shiphappens/source/amazon/AmazonSourceTest.kt`

**Interfaces:**
- Consumes: `AmazonWebSpec` (Task 5), `WebViewBasedSource`/`NoWebScraper` (framework), `normalizeTracking` (domain).
- Produces: `AmazonWebSource(scraper: WebScraper)` and `val amazonSourceModule: Module`, registered in `appModules()`.

- [ ] **Step 1: Write the failing test**

Create `AmazonSourceTest.kt`:

```kotlin
package com.shiphappens.source.amazon

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.api.SourceConfig
import com.shiphappens.source.api.SourceKind
import com.shiphappens.source.api.SourceResult
import com.shiphappens.source.webview.NoWebScraper
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmazonSourceTest {
    private val src = AmazonWebSource(NoWebScraper)

    @Test fun descriptor_is_configless_web_carrier() {
        assertEquals("amazon", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertTrue(src.descriptor.configSpec.isEmpty())
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }

    @Test fun detects_order_ids_hyphenated_spaced_and_bare() {
        assertEquals(WellKnownCarriers.AMAZON, src.detectCarrier("113-1234567-1234567"))
        assertEquals(WellKnownCarriers.AMAZON, src.detectCarrier("701 2345678 9012345"))
        assertEquals(WellKnownCarriers.AMAZON, src.detectCarrier("11312345671234567"))
    }

    @Test fun rejects_other_carrier_shapes() {
        assertNull(src.detectCarrier("1Z999AA10123456784"))      // UPS
        assertNull(src.detectCarrier("9434636106092288655003"))  // USPS
        assertNull(src.detectCarrier("123456789012"))            // FedEx 12-digit
        assertNull(src.detectCarrier("213-1234567-1234567"))     // US order ids start 1 or 7
        assertNull(src.detectCarrier("113-1234567-123456"))      // wrong length
    }

    @Test fun track_unavailable_without_scraper_and_test_connection_succeeds() = runTest {
        assertIs<SourceResult.Failure>(src.track("113-1234567-1234567", null))
        assertIs<SourceResult.Success<Unit>>(src.testConnection(SourceConfig(enabled = true)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :source:amazon:jvmTest 2>&1 | tail -20`
Expected: compilation FAILS with `Unresolved reference 'AmazonWebSource'`.

- [ ] **Step 3: Create `AmazonSource.kt`**

```kotlin
package com.shiphappens.source.amazon

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.domain.normalizeTracking
import com.shiphappens.source.api.TrackingSource
import com.shiphappens.source.webview.WebScraper
import com.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Amazon orders via the logged-in amazon.com order pages — see docs/superpowers/specs/2026-07-15-amazon-webview-source-design.md. */
class AmazonWebSource(scraper: WebScraper) : WebViewBasedSource(AmazonWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? =
        // Order ids display as 3-7-7 digits (113-1234567-1234567) but normalizeTracking strips
        // the hyphens, so match the normalized 17-digit form. US order ids start with 1 or 7,
        // which keeps 17-digit numbers of other carriers from false-matching.
        WellKnownCarriers.AMAZON.takeIf { Regex("^[17]\\d{16}$").matches(normalizeTracking(trackingNumber)) }
}

val amazonSourceModule: Module = module { single { AmazonWebSource(get()) } bind TrackingSource::class }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :source:amazon:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Wire into the app**

In `ui/build.gradle.kts`, add next to the other source deps:

```kotlin
implementation(projects.source.amazon)
```

In `AppModules.kt`, add the import and list entry (order matches the imports/sources lists):

```kotlin
import com.shiphappens.source.amazon.amazonSourceModule
```

```kotlin
    uspsSourceModule,
    amazonSourceModule,
    fedexSourceModule,
```

- [ ] **Step 6: Verify the app module compiles and its tests pass**

Run: `./gradlew :ui:testAndroidHostTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add source/amazon ui/build.gradle.kts ui/src
git commit -m "[source] Add Amazon web source and register it in the app"
```

---

### Task 7: Built-in order-ID detection

**Files:**
- Modify: `data/src/commonMain/kotlin/com/shiphappens/data/source/BuiltInCarrierDetection.kt`
- Test: `data/src/jvmTest/kotlin/com/shiphappens/data/source/BuiltInCarrierDetectionTest.kt`

**Interfaces:**
- Consumes: `WellKnownCarriers.AMAZON` (Task 1).
- Produces: pasting/typing an order ID preselects Amazon in the add flow and clipboard import (`ListViewModel` and `SourceRegistry.detectCarrier` both call this object).

- [ ] **Step 1: Write the failing test**

Add to `BuiltInCarrierDetectionTest.kt`:

```kotlin
@Test fun detects_amazon_order_ids() {
    assertEquals(WellKnownCarriers.AMAZON, BuiltInCarrierDetection.detect("113-1234567-1234567"))
    assertEquals(WellKnownCarriers.AMAZON, BuiltInCarrierDetection.detect("701-2345678-9012345"))
    assertNull(BuiltInCarrierDetection.detect("213-1234567-1234567"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :data:jvmTest --tests "com.shiphappens.data.source.BuiltInCarrierDetectionTest" 2>&1 | tail -20`
Expected: FAIL — `detect` returns null for the first assertion.

- [ ] **Step 3: Implement**

In `BuiltInCarrierDetection.kt`:

```kotlin
    private val FEDEX = Regex("^\\d{12}$|^\\d{15}$|^\\d{20,22}$")
    // Amazon order ids: 3-7-7 digits normalized to 17 (hyphens stripped); US ids start 1 or 7.
    private val AMAZON = Regex("^[17]\\d{16}$")
```

and in the `when`:

```kotlin
            FEDEX.matches(norm) -> WellKnownCarriers.FEDEX
            AMAZON.matches(norm) -> WellKnownCarriers.AMAZON
            else -> null
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :data:jvmTest 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL, all data tests pass.

- [ ] **Step 5: Commit**

```bash
git add data/src
git commit -m "[data] Detect Amazon order ids in built-in carrier detection"
```

---

### Task 8: Full verification + live-QA checklist

**Files:**
- Create: `docs/superpowers/qa/2026-07-15-webview-amazon-qa.md`

**Interfaces:**
- Consumes: everything above.
- Produces: green cross-module build and the device-QA checklist that finalizes selectors (the same workflow that validated UPS and USPS).

- [ ] **Step 1: Run the full verification suite**

Run:
```bash
./gradlew :domain:jvmTest :data:jvmTest :source:webview:jvmTest :source:amazon:jvmTest :source:ups:jvmTest :source:usps:jvmTest :ui:testAndroidHostTest :source:webview:compileAndroidMain :source:amazon:compileKotlinIosSimulatorArm64 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL — every module's tests pass, Android and iOS both compile.

- [ ] **Step 2: Create the live-QA checklist**

Create `docs/superpowers/qa/2026-07-15-webview-amazon-qa.md`:

```markdown
# Amazon WebView Source — Live QA Checklist

Device QA for the Amazon source (design spec 2026-07-15). Run with tracing on:
`adb logcat -s ShipScrape`. Amazon requires login, so before anything else Doug signs
in with his own credentials on the device (Settings → Amazon → Sign in) — credentials
are never typed by tooling.

Selector constants in AmazonWebSpec (extraction JS, isLoggedInJs) are placeholders
until this checklist validates them against the live site — expect to iterate. Note:
the WebView may receive Amazon's mobile layout; validate selectors against what the
DOM result lines in ShipScrape actually show, not desktop DevTools.

## Setup
- [ ] Enable the Amazon source in Settings; confirm descriptor row shows and login row opens amazon.com sign-in
- [ ] Sign in; confirm `isLoggedIn=true` in ShipScrape and the Settings row reflects it (fix AMAZON_IS_LOGGED_IN_JS if not)

## Scrape paths (use a real recent order id for each)
- [ ] Single-shipment order in transit: order-details loads → `goto HOP url=` line appears → tracker page DOM result has status/events → card shows rich data
- [ ] Multi-shipment order with one delivered box: hop targets the FIRST UNDELIVERED shipment (verify hop URL against the order page)
- [ ] Fully delivered order: DELIVERED status (hop or coarse `page:'ok'` path when no tracker link)
- [ ] Order with no tracker link at all: coarse status shown, no error
- [ ] Coarse fallback: if a tracker page yields `page:'empty'`, confirm the card still shows the order-page status (not an error)
- [ ] Fix selectors/vocabulary in AMAZON_EXTRACTION_JS per the above and re-run until statuses, ETA, and event history are right

## Error paths
- [ ] Signed out (Settings → sign out): scrape → AUTH failure "Sign in to Amazon in Settings, then refresh"
- [ ] Bogus order id (e.g. 111-0000000-0000000): NOT_FOUND (validate the notFound wording matchers)
- [ ] Captcha (if Amazon serves one): RATE_LIMITED → open More details → solve manually → refresh recovers
- [ ] Airplane mode: NETWORK failure
- [ ] Second refresh within the throttle window: `throttle HIT` line, no page load

## Framework regressions
- [ ] UPS and USPS sources still refresh correctly (no goto regressions in shared scraper)
- [ ] More-details screen for an Amazon parcel opens the order-details page

## Findings

(record live findings here, as in 2026-07-12-webview-ups-qa.md)
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/qa/2026-07-15-webview-amazon-qa.md
git commit -m "[docs] Add Amazon webview live-QA checklist"
```

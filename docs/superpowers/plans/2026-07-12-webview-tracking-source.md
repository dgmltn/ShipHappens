# WebView-Based Tracking Source (UPS First) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a WebView-driven tracking source (UPS first): a "More details" in-app web page that scrapes tracking data into the DB while the user views it (Phase 1), then a headless WebView scraper that powers normal refresh (Phase 2).

**Architecture:** New KMP module `:source:webview` holds reusable machinery — `WebViewBasedSource` (a `TrackingSource` driven by a `WebScraper`), per-provider `WebProviderSpec` recipes, a canonical `ScrapedTracking` model, and a JS capture bridge. `:source:ups` becomes a thin `WebViewBasedSource` subclass. Sources still never touch the DB; scrape results flow through `TrackingSnapshot` into `ParcelRepository`. All extraction layers (API capture, DOM JS) emit one canonical JSON shape, so everything downstream of the bridge is pure, testable Kotlin.

**Tech Stack:** Kotlin 2.4.0 KMP, Compose Multiplatform 1.11.1, Navigation 3, Koin 4.2, Room 3, kotlinx-serialization, Android system WebView + `androidx.webkit`.

**Spec:** `docs/superpowers/specs/2026-07-12-webview-tracking-source-design.md`

## Global Constraints

- AGP 9: KMP library modules MUST use `id("com.android.kotlin.multiplatform.library")` with android config inside `kotlin { android { } }` (namespace, compileSdk, minSdk from `shiphappens.*` gradle properties). Never apply `org.jetbrains.kotlin.android`.
- Test tasks: common/JVM tests run as `:module:jvmTest`; `:ui` tests run as `:ui:testAndroidHostTest`. Android compile check: `:module:compileAndroidMain`.
- ViewModel tests over Room flows: NEVER `advanceUntilIdle(); state.value` — use the awaitState pattern (real-time `withTimeout` on `Dispatchers.Default`, gating on the exact fields asserted), as in `ui/src/androidHostTest/.../DetailViewModelTest.kt`.
- Commit messages start with a bracketed one-word tag: `[source]`, `[ui]`, `[data]`, `[gradle]`, `[docs]`.
- `Dispatchers.IO` is internal on Kotlin/Native — never use it in commonMain.
- Watch for an auto-generated `gradle/gradle-daemon-jvm.properties` before `git add` — do NOT commit it.
- Platform scope: Android implements everything; iOS/JVM get no-op actuals (`implemented = false` behavior preserved on iOS).
- Sources never touch the database. `TrackingSource.track()` returns `SourceResult<TrackingSnapshot>`; only `ParcelRepository` writes.
- Sandboxed/offline execution note: all Gradle commands assume the dependency cache; the only NEW external artifact this plan introduces is `androidx.webkit:webkit` (Task 6).

---

### Task 1: `:source:webview` module + canonical `ScrapedTracking` model

**Files:**
- Modify: `settings.gradle.kts` (add include)
- Create: `source/webview/build.gradle.kts`
- Create: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/ScrapedTracking.kt`
- Test: `source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/ScrapedTrackingTest.kt`

**Interfaces:**
- Consumes: `TrackingSnapshot`, `TrackingEvent`, `TrackingStatus` from `:domain` (via `api(projects.source.api)`).
- Produces: `@Serializable ScrapedTracking(status: String, etaDate: String?, etaTime: String?, location: String?, events: List<ScrapedEvent>)`, `@Serializable ScrapedEvent(timestamp: String, description: String, location: String?, status: String?)`, `fun ScrapedTracking.toSnapshot(): TrackingSnapshot`. Status strings are canonical `TrackingStatus` enum names; timestamps ISO-8601 instants; etaDate/etaTime ISO local date/time.

- [ ] **Step 1: Register the module**

In `settings.gradle.kts`, after `include(":source:api")` add:

```kotlin
include(":source:webview")
```

- [ ] **Step 2: Create the build file**

`source/webview/build.gradle.kts` (mirror of `source/ups/build.gradle.kts` plus serialization):

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.shiphappens.source.webview"
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
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
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

`source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/ScrapedTrackingTest.kt`:

```kotlin
package com.shiphappens.source.webview

import com.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScrapedTrackingTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decodes_canonical_json() {
        val decoded = json.decodeFromString<ScrapedTracking>(
            """{"status":"IN_TRANSIT","etaDate":"2026-07-15","etaTime":"21:00","location":"Louisville, KY",
                "events":[{"timestamp":"2026-07-11T12:15:00Z","description":"Departed from Facility","location":"Louisville, KY","status":"IN_TRANSIT"}]}""",
        )
        assertEquals("IN_TRANSIT", decoded.status)
        assertEquals(1, decoded.events.size)
    }

    @Test fun toSnapshot_maps_all_fields() {
        val snap = ScrapedTracking(
            status = "OUT_FOR_DELIVERY", etaDate = "2026-07-15", etaTime = "21:00", location = "Memphis, TN",
            events = listOf(ScrapedEvent("2026-07-11T12:15:00Z", "Departed", "Louisville, KY", "IN_TRANSIT")),
        ).toSnapshot()
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, snap.status)
        assertEquals(LocalDate(2026, 7, 15), snap.etaDate)
        assertEquals(LocalTime(21, 0), snap.etaTime)
        assertEquals("Memphis, TN", snap.latestLocation)
        assertEquals(1, snap.events.size)
        assertEquals(TrackingStatus.IN_TRANSIT, snap.events[0].status)
    }

    @Test fun toSnapshot_tolerates_garbage() {
        val snap = ScrapedTracking(
            status = "SOMETHING_NEW", etaDate = "not-a-date", etaTime = "late",
            events = listOf(
                ScrapedEvent("garbage-timestamp", "dropped", null, null),
                ScrapedEvent("2026-07-11T12:15:00Z", "kept", null, "NOT_A_STATUS"),
            ),
        ).toSnapshot()
        assertEquals(TrackingStatus.UNKNOWN, snap.status)
        assertNull(snap.etaDate)
        assertNull(snap.etaTime)
        assertEquals(1, snap.events.size)          // bad-timestamp event dropped
        assertEquals("kept", snap.events[0].description)
        assertNull(snap.events[0].status)          // unknown status string -> null
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :source:webview:jvmTest --tests '*ScrapedTrackingTest*'`
Expected: FAIL — compilation error, `ScrapedTracking` not defined.

- [ ] **Step 5: Write the implementation**

`source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/ScrapedTracking.kt`:

```kotlin
package com.shiphappens.source.webview

import com.shiphappens.domain.TrackingEvent
import com.shiphappens.domain.TrackingSnapshot
import com.shiphappens.domain.TrackingStatus
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * Canonical extraction output. Every extraction layer (captured provider API JSON after
 * provider-side parsing, DOM extraction JS, future AI extractor) produces this one shape, so
 * everything downstream of the JS bridge is provider-agnostic Kotlin.
 *
 * `status` values are [TrackingStatus] enum names; `timestamp` is an ISO-8601 instant;
 * `etaDate`/`etaTime` are ISO local date/time. Unknown or malformed values degrade gracefully
 * (UNKNOWN status, dropped event, null eta) rather than failing the whole scrape.
 */
@Serializable
data class ScrapedTracking(
    val status: String,
    val etaDate: String? = null,
    val etaTime: String? = null,
    val location: String? = null,
    val events: List<ScrapedEvent> = emptyList(),
)

@Serializable
data class ScrapedEvent(
    val timestamp: String,
    val description: String,
    val location: String? = null,
    val status: String? = null,
)

private fun statusOrNull(name: String?): TrackingStatus? =
    name?.let { n -> TrackingStatus.entries.firstOrNull { it.name == n } }

fun ScrapedTracking.toSnapshot(): TrackingSnapshot = TrackingSnapshot(
    status = statusOrNull(status) ?: TrackingStatus.UNKNOWN,
    etaDate = etaDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    etaTime = etaTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
    latestLocation = location,
    events = events.mapNotNull { e ->
        runCatching { Instant.parse(e.timestamp) }.getOrNull()?.let { ts ->
            TrackingEvent(timestamp = ts, description = e.description, location = e.location, status = statusOrNull(e.status))
        }
    },
)
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :source:webview:jvmTest --tests '*ScrapedTrackingTest*'`
Expected: PASS (3 tests)

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts source/webview
git commit -m "[source] Add :source:webview module with canonical ScrapedTracking model"
```

---

### Task 2: `WebProviderSpec` + bridge payload routing

**Files:**
- Create: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/WebProviderSpec.kt`
- Create: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/PayloadRouter.kt`
- Test: `source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/PayloadRouterTest.kt`

**Interfaces:**
- Consumes: `ScrapedTracking` (Task 1), `Carrier` from `:domain`.
- Produces:
  - `class WebProviderSpec(sourceId: String, carrier: Carrier, cookieDomain: String, trackingUrl: (String) -> String, loginUrl: String, isLoggedInJs: String, apiUrlPatterns: List<String>, challengeMarkers: List<String>, extractionJs: String, parseApi: (url: String?, body: String) -> ScrapedTracking?)` with `fun allowedOriginRules(): List<String>`.
  - `@Serializable BridgePayload(kind: String, url: String?, body: String)` — `kind` is `"api"` or `"dom"`.
  - `@Serializable DomExtraction(page: String, tracking: ScrapedTracking?)` — `page` ∈ `ok|notFound|loginWall|challenge|empty`.
  - `sealed interface RouteResult { Tracking(tracking); NotFound; LoginWall; Challenge; Unparsed }`
  - `class PayloadRouter(spec) { fun route(payloadJson: String): RouteResult }`

- [ ] **Step 1: Write the failing test**

`source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/PayloadRouterTest.kt`:

```kotlin
package com.shiphappens.source.webview

import com.shiphappens.domain.WellKnownCarriers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

internal fun testSpec(parseApi: (String?, String) -> ScrapedTracking? = { _, _ -> null }) = WebProviderSpec(
    sourceId = "test",
    carrier = WellKnownCarriers.UPS,
    cookieDomain = "example.com",
    trackingUrl = { "https://www.example.com/track?n=$it" },
    loginUrl = "https://www.example.com/login",
    isLoggedInJs = "(function(){return false})()",
    apiUrlPatterns = listOf(".*example\\.com/api/track.*"),
    challengeMarkers = listOf("verify you are a human"),
    extractionJs = "function(){return {page:'empty'}}",
    parseApi = parseApi,
)

class PayloadRouterTest {

    @Test fun api_payload_routes_through_parseApi() {
        val router = PayloadRouter(testSpec(parseApi = { url, body ->
            if (url?.contains("/api/track") == true && body == "{...}") ScrapedTracking(status = "IN_TRANSIT") else null
        }))
        val result = router.route("""{"kind":"api","url":"https://www.example.com/api/track?x=1","body":"{...}"}""")
        assertEquals("IN_TRANSIT", assertIs<RouteResult.Tracking>(result).tracking.status)
    }

    @Test fun api_payload_parse_failure_is_unparsed() {
        val result = PayloadRouter(testSpec()).route("""{"kind":"api","url":"u","body":"junk"}""")
        assertIs<RouteResult.Unparsed>(result)
    }

    @Test fun dom_ok_payload_yields_tracking() {
        val body = """{"page":"ok","tracking":{"status":"DELIVERED"}}"""
        val result = PayloadRouter(testSpec()).route(
            """{"kind":"dom","body":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(body))}}""",
        )
        assertEquals("DELIVERED", assertIs<RouteResult.Tracking>(result).tracking.status)
    }

    @Test fun dom_page_states_route_to_signals() {
        val router = PayloadRouter(testSpec())
        fun dom(page: String) = """{"kind":"dom","body":"{\"page\":\"$page\"}"}"""
        assertIs<RouteResult.NotFound>(router.route(dom("notFound")))
        assertIs<RouteResult.LoginWall>(router.route(dom("loginWall")))
        assertIs<RouteResult.Challenge>(router.route(dom("challenge")))
        assertIs<RouteResult.Unparsed>(router.route(dom("empty")))
    }

    @Test fun garbage_payload_is_unparsed() {
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("not json at all"))
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("""{"kind":"mystery","body":""}"""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :source:webview:jvmTest --tests '*PayloadRouterTest*'`
Expected: FAIL — `WebProviderSpec` / `PayloadRouter` not defined.

- [ ] **Step 3: Write the implementation**

`source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/WebProviderSpec.kt`:

```kotlin
package com.shiphappens.source.webview

import com.shiphappens.domain.Carrier

/**
 * Everything provider-specific about scraping one carrier's website. Adding a new provider
 * (FedEx, USPS, DHL...) means writing one of these plus a thin WebViewBasedSource subclass —
 * no new scraping machinery.
 *
 * JS fields are small, versioned-in-code scripts:
 *  - [isLoggedInJs]: expression evaluating to a boolean in page context.
 *  - [extractionJs]: a JS *function expression* `function(){...}` returning
 *    `{page: 'ok'|'notFound'|'loginWall'|'challenge'|'empty', tracking: <canonical ScrapedTracking>}`.
 *  - [apiUrlPatterns]: JS-compatible regex source strings matched against fetch/XHR URLs.
 */
class WebProviderSpec(
    val sourceId: String,
    val carrier: Carrier,
    val cookieDomain: String,
    val trackingUrl: (trackingNumber: String) -> String,
    val loginUrl: String,
    val isLoggedInJs: String,
    val apiUrlPatterns: List<String>,
    val challengeMarkers: List<String>,
    val extractionJs: String,
    val parseApi: (url: String?, body: String) -> ScrapedTracking?,
) {
    /** Origin rules for androidx.webkit's WebMessageListener / document-start script APIs. */
    fun allowedOriginRules(): List<String> = listOf("https://*.$cookieDomain", "https://$cookieDomain")
}
```

`source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/PayloadRouter.kt`:

```kotlin
package com.shiphappens.source.webview

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One message posted from the in-page JS bridge to Kotlin. */
@Serializable
data class BridgePayload(val kind: String, val url: String? = null, val body: String)

/** Parsed body of a `kind == "dom"` payload (the extraction runner's output). */
@Serializable
data class DomExtraction(val page: String, val tracking: ScrapedTracking? = null)

sealed interface RouteResult {
    data class Tracking(val tracking: ScrapedTracking) : RouteResult
    data object NotFound : RouteResult
    data object LoginWall : RouteResult
    data object Challenge : RouteResult
    data object Unparsed : RouteResult
}

/** Routes raw bridge payload JSON to a provider-agnostic [RouteResult]. Pure, commonMain, tested. */
class PayloadRouter(private val spec: WebProviderSpec) {
    private val json = Json { ignoreUnknownKeys = true }

    fun route(payloadJson: String): RouteResult {
        val payload = runCatching { json.decodeFromString<BridgePayload>(payloadJson) }.getOrNull()
            ?: return RouteResult.Unparsed
        return when (payload.kind) {
            "api" -> spec.parseApi(payload.url, payload.body)
                ?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
            "dom" -> {
                val dom = runCatching { json.decodeFromString<DomExtraction>(payload.body) }.getOrNull()
                    ?: return RouteResult.Unparsed
                when (dom.page) {
                    "ok" -> dom.tracking?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
                    "notFound" -> RouteResult.NotFound
                    "loginWall" -> RouteResult.LoginWall
                    "challenge" -> RouteResult.Challenge
                    else -> RouteResult.Unparsed
                }
            }
            else -> RouteResult.Unparsed
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :source:webview:jvmTest --tests '*PayloadRouterTest*'`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add source/webview
git commit -m "[source] Add WebProviderSpec and bridge payload routing"
```

---

### Task 3: `WebScraper`, `WebViewBasedSource`, and DI seams

**Files:**
- Create: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/WebScraper.kt`
- Create: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/WebViewBasedSource.kt`
- Create: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/di/WebModule.kt`
- Create: `source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/di/WebModule.android.kt`
- Create: `source/webview/src/iosMain/kotlin/com/shiphappens/source/webview/di/WebModule.ios.kt`
- Create: `source/webview/src/jvmMain/kotlin/com/shiphappens/source/webview/di/WebModule.jvm.kt`
- Modify: `ui/build.gradle.kts` (commonMain deps), `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt`, `ui/src/androidHostTest/kotlin/com/shiphappens/ui/di/AppModulesTest.kt`
- Test: `source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/WebViewBasedSourceTest.kt`

**Interfaces:**
- Consumes: `WebProviderSpec`, `PayloadRouter`, `RouteResult`, `ScrapedTracking.toSnapshot()` (Tasks 1–2); `TrackingSource`, `SourceDescriptor`, `SourceKind`, `SourceResult`, `FailureReason`, `SourceConfig` from `:source:api`.
- Produces:
  - `sealed interface ScrapeResult { Payloads(payloads: List<String>); LoadError(message: String?); Timeout; Unavailable }`
  - `interface WebScraper { val isAvailable: Boolean; suspend fun scrape(spec: WebProviderSpec, trackingNumber: String): ScrapeResult }` + `object NoWebScraper`
  - `sealed interface PageEvent { Finished(url: String); LoggedIn(loggedIn: Boolean); LoadFailed(message: String?) }`
  - `interface WebCookieJar { fun flush(); fun clearForDomain(domain: String) }` + `object NoOpCookieJar`
  - `interface WebCapableSource { val webSpec: WebProviderSpec }`
  - `abstract class WebViewBasedSource(webSpec, scraper) : TrackingSource, WebCapableSource` — subclass supplies only `detectCarrier`.
  - `expect fun platformWebModule(): Module` — binds `WebScraper` and `WebCookieJar` per platform (all no-op until Task 6/10).

- [ ] **Step 1: Write the failing test**

`source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/WebViewBasedSourceTest.kt`:

```kotlin
package com.shiphappens.source.webview

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.TrackingStatus
import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.api.FailureReason
import com.shiphappens.source.api.SourceResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeScraper(private val result: ScrapeResult) : WebScraper {
    override val isAvailable = true
    override suspend fun scrape(spec: WebProviderSpec, trackingNumber: String) = result
}

private class TestWebSource(scraper: WebScraper, spec: WebProviderSpec = testSpec()) :
    WebViewBasedSource(spec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? = WellKnownCarriers.UPS
}

private fun dom(page: String) = """{"kind":"dom","body":"{\"page\":\"$page\"}"}"""

class WebViewBasedSourceTest {

    @Test fun descriptor_derives_from_spec_and_scraper() {
        val available = TestWebSource(FakeScraper(ScrapeResult.Unavailable))
        assertTrue(available.descriptor.implemented)
        assertEquals("test", available.descriptor.id)
        assertFalse(TestWebSource(NoWebScraper).descriptor.implemented)
    }

    @Test fun tracking_payload_becomes_success() = runTest {
        val spec = testSpec(parseApi = { _, _ -> ScrapedTracking(status = "IN_TRANSIT", location = "Louisville, KY") })
        val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf("""{"kind":"api","url":"u","body":"b"}"""))), spec)
        val result = assertIs<SourceResult.Success<com.shiphappens.domain.TrackingSnapshot>>(src.track("1Z1", null))
        assertEquals(TrackingStatus.IN_TRANSIT, result.value.status)
        assertEquals("Louisville, KY", result.value.latestLocation)
    }

    @Test fun failure_taxonomy_mapping() = runTest {
        suspend fun reasonFor(r: ScrapeResult): FailureReason =
            assertIs<SourceResult.Failure>(TestWebSource(FakeScraper(r)).track("1Z1", null)).reason
        assertEquals(FailureReason.AUTH, reasonFor(ScrapeResult.Payloads(listOf(dom("loginWall")))))
        assertEquals(FailureReason.RATE_LIMITED, reasonFor(ScrapeResult.Payloads(listOf(dom("challenge")))))
        assertEquals(FailureReason.NOT_FOUND, reasonFor(ScrapeResult.Payloads(listOf(dom("notFound")))))
        assertEquals(FailureReason.UNKNOWN, reasonFor(ScrapeResult.Payloads(listOf(dom("empty")))))
        assertEquals(FailureReason.NETWORK, reasonFor(ScrapeResult.LoadError("dns")))
        assertEquals(FailureReason.NETWORK, reasonFor(ScrapeResult.Timeout))
        assertEquals(FailureReason.UNKNOWN, reasonFor(ScrapeResult.Unavailable))
    }

    @Test fun unavailable_scraper_fails_without_scraping() = runTest {
        val src = TestWebSource(NoWebScraper)
        assertEquals(FailureReason.UNKNOWN, assertIs<SourceResult.Failure>(src.track("1Z1", null)).reason)
    }

    @Test fun first_tracking_payload_wins() = runTest {
        val src = TestWebSource(
            FakeScraper(ScrapeResult.Payloads(listOf(dom("loginWall"), """{"kind":"dom","body":"{\"page\":\"ok\",\"tracking\":{\"status\":\"DELIVERED\"}}"}"""))),
        )
        val result = assertIs<SourceResult.Success<com.shiphappens.domain.TrackingSnapshot>>(src.track("1Z1", null))
        assertEquals(TrackingStatus.DELIVERED, result.value.status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :source:webview:jvmTest --tests '*WebViewBasedSourceTest*'`
Expected: FAIL — `WebScraper` / `WebViewBasedSource` not defined.

- [ ] **Step 3: Write the implementations**

`source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/WebScraper.kt`:

```kotlin
package com.shiphappens.source.webview

/** Raw result of one headless (or visible) page scrape: bridge payload JSON strings, in arrival order. */
sealed interface ScrapeResult {
    /** Payloads arrive chronologically; API captures land before the DOM extraction runner's output. */
    data class Payloads(val payloads: List<String>) : ScrapeResult
    data class LoadError(val message: String?) : ScrapeResult
    data object Timeout : ScrapeResult
    data object Unavailable : ScrapeResult
}

interface WebScraper {
    val isAvailable: Boolean
    suspend fun scrape(spec: WebProviderSpec, trackingNumber: String): ScrapeResult
}

/** Platforms without a WebView implementation (iOS/JVM for now; Android until Phase 2). */
object NoWebScraper : WebScraper {
    override val isAvailable = false
    override suspend fun scrape(spec: WebProviderSpec, trackingNumber: String) = ScrapeResult.Unavailable
}

/** Page lifecycle signals shared by the visible WebView composable and the headless scraper. */
sealed interface PageEvent {
    data class Finished(val url: String) : PageEvent
    data class LoggedIn(val loggedIn: Boolean) : PageEvent
    data class LoadFailed(val message: String?) : PageEvent
}

/** Cookie store operations the app needs (login persistence, sign-out). */
interface WebCookieJar {
    fun flush()
    fun clearForDomain(domain: String)
}

object NoOpCookieJar : WebCookieJar {
    override fun flush() {}
    override fun clearForDomain(domain: String) {}
}
```

`source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/WebViewBasedSource.kt`:

```kotlin
package com.shiphappens.source.webview

import com.shiphappens.domain.TrackingSnapshot
import com.shiphappens.domain.Carrier
import com.shiphappens.source.api.FailureReason
import com.shiphappens.source.api.SourceConfig
import com.shiphappens.source.api.SourceDescriptor
import com.shiphappens.source.api.SourceKind
import com.shiphappens.source.api.SourceResult
import com.shiphappens.source.api.TrackingSource

/** Marker capability: lets UI find the web recipe for a carrier (More-details screen, login). */
interface WebCapableSource {
    val webSpec: WebProviderSpec
}

/**
 * A TrackingSource whose data comes from driving the carrier's own website. Subclasses supply
 * only [detectCarrier]; everything else derives from the [webSpec] recipe. No credentials in
 * configSpec — auth is an optional cookie session established in the login WebView.
 */
abstract class WebViewBasedSource(
    final override val webSpec: WebProviderSpec,
    private val scraper: WebScraper,
) : TrackingSource, WebCapableSource {

    final override val descriptor = SourceDescriptor(
        id = webSpec.sourceId,
        displayName = webSpec.carrier.displayName,
        kind = SourceKind.CARRIER,
        accentColorHex = webSpec.carrier.accentColorHex,
        configSpec = emptyList(),
        implemented = scraper.isAvailable,
    )

    abstract override fun detectCarrier(trackingNumber: String): Carrier?

    final override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val name = webSpec.carrier.displayName
        if (!scraper.isAvailable) {
            return SourceResult.Failure(FailureReason.UNKNOWN, "$name web tracking isn't available on this platform yet")
        }
        return when (val result = scraper.scrape(webSpec, trackingNumber)) {
            is ScrapeResult.Payloads -> {
                val router = PayloadRouter(webSpec)
                val routed = result.payloads.map(router::route)
                routed.firstNotNullOfOrNull { (it as? RouteResult.Tracking)?.tracking }
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
            is ScrapeResult.LoadError -> SourceResult.Failure(FailureReason.NETWORK, result.message)
            ScrapeResult.Timeout -> SourceResult.Failure(FailureReason.NETWORK, "Timed out loading $name")
            ScrapeResult.Unavailable -> SourceResult.Failure(FailureReason.UNKNOWN, "$name web tracking unavailable")
        }
    }

    final override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> = SourceResult.Success(Unit)
}
```

`source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/di/WebModule.kt`:

```kotlin
package com.shiphappens.source.webview.di

import org.koin.core.module.Module

/**
 * Binds WebScraper and WebCookieJar for the current platform. Android provides real
 * implementations (headless scraper in Phase 2); iOS/JVM bind no-ops so web sources
 * report implemented = false and the registry skips them.
 */
expect fun platformWebModule(): Module
```

`source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/di/WebModule.android.kt` (Phase 1: still no-op scraper; real cookie jar arrives in Task 6, headless scraper in Task 10):

```kotlin
package com.shiphappens.source.webview.di

import com.shiphappens.source.webview.NoOpCookieJar
import com.shiphappens.source.webview.NoWebScraper
import com.shiphappens.source.webview.WebCookieJar
import com.shiphappens.source.webview.WebScraper
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformWebModule(): Module = module {
    single<WebScraper> { NoWebScraper }
    single<WebCookieJar> { NoOpCookieJar }
}
```

`source/webview/src/iosMain/kotlin/com/shiphappens/source/webview/di/WebModule.ios.kt` and `source/webview/src/jvmMain/kotlin/com/shiphappens/source/webview/di/WebModule.jvm.kt` — identical bodies to the Android actual above (package + imports the same, only the file name differs per source set).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :source:webview:jvmTest`
Expected: PASS (all tests in module)

- [ ] **Step 5: Wire into the app graph**

In `ui/build.gradle.kts` `commonMain.dependencies`, after `implementation(projects.source.fedex)` add:

```kotlin
implementation(projects.source.webview)
```

In `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt` add the import and module (order matters — platform modules first; keep the documented `platformDataModule()` at index 0):

```kotlin
import com.shiphappens.source.webview.di.platformWebModule
```

```kotlin
fun appModules(): List<Module> = listOf(
    platformDataModule(),
    platformWebModule(),
    dataModule,
    demoSourceModule,
    upsSourceModule,
    uspsSourceModule,
    fedexSourceModule,
    uiModule,
)
```

Update `AppModulesTest` — it drops index 0 and substitutes a host-safe platform module; it must now drop the first TWO and substitute both (the Android `platformWebModule()` will later construct a Context-needing scraper, which has no Context in a JVM host test). Replace the test method and add the web module:

```kotlin
    private fun testWebModule(): Module = module {
        single<com.shiphappens.source.webview.WebScraper> { com.shiphappens.source.webview.NoWebScraper }
        single<com.shiphappens.source.webview.WebCookieJar> { com.shiphappens.source.webview.NoOpCookieJar }
    }

    @Test fun appModules_graph_resolves() {
        // appModules() puts platformDataModule() then platformWebModule() first (documented
        // order); swap both for host-test-safe modules and keep the rest of the real graph as-is.
        val realModulesMinusPlatform = appModules().drop(2)

        koinApplication {
            modules(listOf(testPlatformDataModule(), testWebModule()) + realModulesMinusPlatform)
        }.checkModules()
    }
```

- [ ] **Step 6: Run the graph test and compile checks**

Run: `./gradlew :ui:testAndroidHostTest --tests '*AppModulesTest*' :source:webview:compileAndroidMain :source:webview:compileKotlinIosSimulatorArm64`
Expected: PASS / BUILD SUCCESSFUL (all three platform actuals compile).

- [ ] **Step 7: Commit**

```bash
git add source/webview ui/build.gradle.kts ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt ui/src/androidHostTest/kotlin/com/shiphappens/ui/di/AppModulesTest.kt
git commit -m "[source] Add WebScraper seam and WebViewBasedSource base class"
```

---

### Task 4: UPS provider — API parser, spec, and `UpsWebSource`

**Files:**
- Modify: `source/ups/build.gradle.kts`
- Create: `source/ups/src/commonMain/kotlin/com/shiphappens/source/ups/UpsApiParser.kt`
- Create: `source/ups/src/commonMain/kotlin/com/shiphappens/source/ups/UpsWebSpec.kt`
- Modify: `source/ups/src/commonMain/kotlin/com/shiphappens/source/ups/UpsSource.kt` (rewrite)
- Test: `source/ups/src/commonTest/kotlin/com/shiphappens/source/ups/UpsApiParserTest.kt`
- Modify: `source/ups/src/commonTest/kotlin/com/shiphappens/source/ups/UpsSourceTest.kt` (constructor update)

**Interfaces:**
- Consumes: `WebProviderSpec`, `ScrapedTracking`, `ScrapedEvent`, `WebViewBasedSource`, `WebScraper` (Tasks 1–3).
- Produces: `object UpsApiParser { fun parse(body: String): ScrapedTracking? }`, `val UpsWebSpec: WebProviderSpec` (sourceId `"ups"`, cookieDomain `"ups.com"`), `class UpsWebSource(scraper: WebScraper) : WebViewBasedSource(UpsWebSpec, scraper)`, `val upsSourceModule: Module` (same name as today — `AppModules.kt` needs no change).

**Reality note:** the UPS endpoint pattern (`track/api/Track/GetStatus`), field names, and the DOM/login JS below are best-effort recipes validated and refined during the manual QA task (Task 11). The *parsing* is what's locked by tests; fixtures get re-recorded from the live site during QA if drift is found.

- [ ] **Step 1: Add dependencies**

In `source/ups/build.gradle.kts`: add `alias(libs.plugins.kotlinSerialization)` to the `plugins` block, and in `commonMain.dependencies` add:

```kotlin
api(projects.source.webview)
implementation(libs.kotlinx.serialization.json)
implementation(libs.kotlinx.datetime)
```

- [ ] **Step 2: Write the failing parser test**

`source/ups/src/commonTest/kotlin/com/shiphappens/source/ups/UpsApiParserTest.kt`:

```kotlin
package com.shiphappens.source.ups

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Recorded shape of ups.com's in-page tracking API (see QA checklist for re-recording steps).
private const val FIXTURE = """
{
  "statusCode": "200",
  "trackDetails": [{
    "trackingNumber": "1Z999AA10123456784",
    "packageStatus": "On the Way",
    "packageStatusType": "I",
    "scheduledDeliveryDate": "07/15/2026",
    "shipmentProgressActivities": [
      {"date": "07/11/2026", "time": "8:15 A.M.", "location": "Louisville, KY, United States", "activityScan": "Departed from Facility"},
      {"date": "07/10/2026", "time": "9:03 P.M.", "location": "Louisville, KY, United States", "activityScan": "Arrived at Facility"},
      {"date": "07/10/2026", "time": "2:00 P.M.", "location": "United States", "activityScan": "Shipper created a label, UPS has not received the package yet."}
    ]
  }]
}
"""

class UpsApiParserTest {

    @Test fun parses_status_eta_and_events() {
        val t = assertNotNull(UpsApiParser.parse(FIXTURE))
        assertEquals("IN_TRANSIT", t.status)
        assertEquals("2026-07-15", t.etaDate)
        assertEquals("Louisville, KY, United States", t.location)  // newest activity's location
        assertEquals(3, t.events.size)
        // Events must be chronological ASCENDING (domain expectation); UPS sends newest-first.
        assertTrue(t.events.first().description.startsWith("Shipper created a label"))
        assertEquals("LABEL_CREATED", t.events.first().status)
        assertEquals("IN_TRANSIT", t.events.last().status)
        // Timestamps are ISO instants (parseable by the canonical layer).
        assertTrue(t.events.all { runCatching { kotlin.time.Instant.parse(it.timestamp) }.isSuccess })
    }

    @Test fun status_type_codes_map_to_canonical() {
        fun withType(type: String, text: String = "x") = """{"trackDetails":[{"packageStatus":"$text","packageStatusType":"$type"}]}"""
        assertEquals("LABEL_CREATED", UpsApiParser.parse(withType("M"))!!.status)
        assertEquals("IN_TRANSIT", UpsApiParser.parse(withType("I"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", UpsApiParser.parse(withType("O"))!!.status)
        assertEquals("DELIVERED", UpsApiParser.parse(withType("D"))!!.status)
        assertEquals("EXCEPTION", UpsApiParser.parse(withType("X"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", UpsApiParser.parse(withType("", "Out for Delivery Today"))!!.status)
        assertEquals("UNKNOWN", UpsApiParser.parse(withType("", "Some New Wording"))!!.status)
    }

    @Test fun rejects_non_tracking_json() {
        assertNull(UpsApiParser.parse("""{"unrelated": true}"""))
        assertNull(UpsApiParser.parse("""{"trackDetails": []}"""))
        assertNull(UpsApiParser.parse("not json"))
    }

    @Test fun tolerates_missing_fields() {
        val t = assertNotNull(UpsApiParser.parse("""{"trackDetails":[{"packageStatusType":"D"}]}"""))
        assertEquals("DELIVERED", t.status)
        assertNull(t.etaDate)
        assertTrue(t.events.isEmpty())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :source:ups:jvmTest --tests '*UpsApiParserTest*'`
Expected: FAIL — `UpsApiParser` not defined.

- [ ] **Step 4: Write the parser**

`source/ups/src/commonMain/kotlin/com/shiphappens/source/ups/UpsApiParser.kt`:

```kotlin
package com.shiphappens.source.ups

import com.shiphappens.source.webview.ScrapedEvent
import com.shiphappens.source.webview.ScrapedTracking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class UpsTrackResponse(val trackDetails: List<UpsTrackDetail>? = null)

@Serializable private data class UpsTrackDetail(
    val packageStatus: String? = null,
    val packageStatusType: String? = null,
    val scheduledDeliveryDate: String? = null,
    val shipmentProgressActivities: List<UpsActivity>? = null,
)

@Serializable private data class UpsActivity(
    val date: String? = null,
    val time: String? = null,
    val location: String? = null,
    val activityScan: String? = null,
)

/**
 * Maps ups.com's in-page tracking API JSON to the canonical [ScrapedTracking].
 * Field vocabulary is tolerant: every field optional, unknown wording degrades to UNKNOWN.
 * UPS reports local wall-clock times with no zone; we interpret them in the device zone —
 * imperfect for cross-zone shipments, but only event ordering and dates surface in the UI.
 */
object UpsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): ScrapedTracking? {
        val detail = runCatching { json.decodeFromString<UpsTrackResponse>(body) }
            .getOrNull()?.trackDetails?.firstOrNull() ?: return null
        val activities = detail.shipmentProgressActivities.orEmpty()
        val tz = TimeZone.currentSystemDefault()
        val events = activities.mapNotNull { a ->
            val date = parseUpsDate(a.date) ?: return@mapNotNull null
            val time = parseUpsTime(a.time) ?: LocalTime(0, 0)
            val scan = a.activityScan ?: return@mapNotNull null
            ScrapedEvent(
                timestamp = LocalDateTime(date, time).toInstant(tz).toString(),
                description = scan,
                location = a.location,
                status = classify(null, scan).takeIf { it != "UNKNOWN" },
            )
        }.reversed()  // UPS is newest-first; domain expects chronological ascending
        return ScrapedTracking(
            status = classify(detail.packageStatusType, detail.packageStatus ?: ""),
            etaDate = parseUpsDate(detail.scheduledDeliveryDate)?.toString(),
            location = activities.firstOrNull()?.location,
            events = events,
        )
    }

    /** "07/15/2026" -> LocalDate. */
    private fun parseUpsDate(raw: String?): LocalDate? {
        val m = Regex("""(\d{2})/(\d{2})/(\d{4})""").find(raw ?: "") ?: return null
        val (mm, dd, yyyy) = m.destructured
        return runCatching { LocalDate(yyyy.toInt(), mm.toInt(), dd.toInt()) }.getOrNull()
    }

    /** "8:15 A.M." / "12:07 P.M." -> LocalTime. */
    private fun parseUpsTime(raw: String?): LocalTime? {
        val m = Regex("""(\d{1,2}):(\d{2})\s*([AP])\.?M\.?""", RegexOption.IGNORE_CASE).find(raw ?: "") ?: return null
        val (h, min, ap) = m.destructured
        val hour24 = (h.toInt() % 12) + if (ap.uppercase() == "P") 12 else 0
        return runCatching { LocalTime(hour24, min.toInt()) }.getOrNull()
    }

    private fun classify(typeCode: String?, text: String): String = when (typeCode?.uppercase()) {
        "M" -> "LABEL_CREATED"
        "P" -> "SHIPPED"
        "I" -> "IN_TRANSIT"
        "O" -> "OUT_FOR_DELIVERY"
        "D" -> "DELIVERED"
        "X" -> "EXCEPTION"
        else -> {
            val t = text.lowercase()
            when {
                "out for delivery" in t -> "OUT_FOR_DELIVERY"
                "delivered" in t -> "DELIVERED"
                "exception" in t || "action required" in t || "attempt" in t -> "EXCEPTION"
                "label" in t || "not received" in t || "order processed" in t -> "LABEL_CREATED"
                "origin scan" in t || "pickup" in t || "picked up" in t -> "SHIPPED"
                "on the way" in t || "in transit" in t || "departed" in t || "arrived" in t -> "IN_TRANSIT"
                else -> "UNKNOWN"
            }
        }
    }
}
```

- [ ] **Step 5: Run parser test to verify it passes**

Run: `./gradlew :source:ups:jvmTest --tests '*UpsApiParserTest*'`
Expected: PASS (4 tests)

- [ ] **Step 6: Write the spec and rewrite the source**

`source/ups/src/commonMain/kotlin/com/shiphappens/source/ups/UpsWebSpec.kt`:

```kotlin
package com.shiphappens.source.ups

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.webview.WebProviderSpec

// DOM fallback extractor. Selector constants are validated against the live page during
// manual QA (Task 11) — the structure (page states + canonical tracking JSON) is what the
// rest of the pipeline depends on, and that is locked by PayloadRouter/canonical-model tests.
private val UPS_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  if (/tracking number.{0,40}(invalid|not found|couldn.t locate)/i.test(text)) return {page: 'notFound'};
  if (/log in|sign in to view/i.test(text) && !/track/i.test(document.title)) return {page: 'loginWall'};
  var statusEl = document.querySelector('#stApp_txtPackageStatus, [id*="PackageStatus"], .ups-tracking_status');
  if (!statusEl) return {page: 'empty'};
  var raw = statusEl.textContent.trim().toLowerCase();
  var status =
    raw.indexOf('out for delivery') >= 0 ? 'OUT_FOR_DELIVERY' :
    raw.indexOf('delivered') >= 0 ? 'DELIVERED' :
    raw.indexOf('exception') >= 0 || raw.indexOf('action') >= 0 ? 'EXCEPTION' :
    raw.indexOf('label') >= 0 || raw.indexOf('order processed') >= 0 ? 'LABEL_CREATED' :
    raw.indexOf('on the way') >= 0 || raw.indexOf('in transit') >= 0 ? 'IN_TRANSIT' : 'UNKNOWN';
  return {page: 'ok', tracking: {status: status, events: []}};
}
""".trimIndent()

private val UPS_IS_LOGGED_IN_JS = """
(function() {
  try {
    if (document.querySelector('#ups-header a[href*="logout"], [data-testid*="account"], .ups-header_avatar')) return true;
    return /welcome,|my profile|sign out/i.test((document.body && document.body.innerText) || '');
  } catch (e) { return false; }
})()
""".trimIndent()

val UpsWebSpec = WebProviderSpec(
    sourceId = "ups",
    carrier = WellKnownCarriers.UPS,
    cookieDomain = "ups.com",
    trackingUrl = { "https://www.ups.com/track?loc=en_US&tracknum=$it" },
    loginUrl = "https://www.ups.com/lasso/signin?loc=en_US",
    isLoggedInJs = UPS_IS_LOGGED_IN_JS,
    apiUrlPatterns = listOf(""".*ups\.com/track/api/Track/GetStatus.*"""),
    challengeMarkers = listOf("verify you are a human", "unusual activity", "Pardon Our Interruption", "Access Denied"),
    extractionJs = UPS_EXTRACTION_JS,
    parseApi = { _, body -> UpsApiParser.parse(body) },
)
```

Replace the whole of `source/ups/src/commonMain/kotlin/com/shiphappens/source/ups/UpsSource.kt` with:

```kotlin
package com.shiphappens.source.ups

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.domain.normalizeTracking
import com.shiphappens.source.api.TrackingSource
import com.shiphappens.source.webview.WebScraper
import com.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** UPS via ups.com in a WebView — see docs/superpowers/specs/2026-07-12-webview-tracking-source-design.md. */
class UpsWebSource(scraper: WebScraper) : WebViewBasedSource(UpsWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? =
        WellKnownCarriers.UPS.takeIf { Regex("^1Z[0-9A-Z]{10,}$").matches(normalizeTracking(trackingNumber)) }
}

val upsSourceModule: Module = module { single { UpsWebSource(get()) } bind TrackingSource::class }
```

- [ ] **Step 7: Update the existing UpsSourceTest**

Open `source/ups/src/commonTest/kotlin/com/shiphappens/source/ups/UpsSourceTest.kt`. Wherever it constructs `UpsSource()`, construct `UpsWebSource(com.shiphappens.source.webview.NoWebScraper)` instead. Delete any assertions about the old OAuth `configSpec` fields (`clientId`/`clientSecret`) — the descriptor now has an empty `configSpec`; replace them with:

```kotlin
assertTrue(source.descriptor.configSpec.isEmpty())
assertFalse(source.descriptor.implemented)  // NoWebScraper => not implemented
```

Keep all `detectCarrier` assertions exactly as they are (the regex is unchanged).

- [ ] **Step 8: Run module tests + full source sweep**

Run: `./gradlew :source:ups:jvmTest :source:webview:jvmTest :ui:testAndroidHostTest --tests '*AppModulesTest*'`
Expected: PASS — parser, source, and the Koin graph (upsSourceModule now needs `WebScraper`, provided by `platformWebModule()` / the test substitute).

- [ ] **Step 9: Commit**

```bash
git add source/ups
git commit -m "[source] Turn UPS stub into UpsWebSource with web spec and API parser"
```

---

### Task 5: `ParcelRepository.applySnapshot`

**Files:**
- Modify: `data/src/commonMain/kotlin/com/shiphappens/data/ParcelRepository.kt`
- Test: `data/src/jvmTest/kotlin/com/shiphappens/data/ApplySnapshotTest.kt`

**Interfaces:**
- Produces: `suspend fun ParcelRepository.applySnapshot(id: String, snapshot: TrackingSnapshot, sourceId: String): Boolean` — persists a snapshot exactly the way a successful `track()` does (status/eta/location merge semantics, event replacement, `lastRefreshedAt` stamp); returns false if the parcel doesn't exist. `refreshRow` delegates to it (single write path).

- [ ] **Step 1: Write the failing test**

`data/src/jvmTest/kotlin/com/shiphappens/data/ApplySnapshotTest.kt`:

```kotlin
package com.shiphappens.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.db.ShipHappensDb
import com.shiphappens.data.db.toEntity
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import okio.Path.Companion.toPath

class ApplySnapshotTest {
    private class FixedClock : AppClock {
        override fun now() = Instant.fromEpochMilliseconds(1_752_300_000_000)
        override fun today() = LocalDate(2026, 7, 12)
    }

    private lateinit var db: ShipHappensDb

    private fun repo(scope: kotlinx.coroutines.CoroutineScope): ParcelRepository {
        val dir = createTempDirectory("applysnap").toString()
        val settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        return ParcelRepository(db.parcelDao(), SourceRegistry(emptyList(), settings), settings, FixedClock())
    }

    private val parcel = Parcel(
        id = "p1", name = "Web-scraped parcel", trackingNumber = "1Z999AA10123456784",
        carrier = WellKnownCarriers.UPS, status = TrackingStatus.UNKNOWN,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    @Test fun applies_status_eta_events_and_source_pin() = runTest {
        val r = repo(backgroundScope)
        db.parcelDao().upsertParcel(parcel.toEntity())
        val ok = r.applySnapshot(
            "p1",
            TrackingSnapshot(
                status = TrackingStatus.IN_TRANSIT,
                etaDate = LocalDate(2026, 7, 15), etaTime = LocalTime(21, 0),
                latestLocation = "Louisville, KY",
                events = listOf(TrackingEvent(Instant.parse("2026-07-11T12:15:00Z"), "Departed from Facility", "Louisville, KY", TrackingStatus.IN_TRANSIT)),
            ),
            sourceId = "ups",
        )
        assertTrue(ok)
        val row = assertNotNull(db.parcelDao().getById("p1"))
        assertEquals(TrackingStatus.IN_TRANSIT.name, row.parcel.status)
        assertEquals("2026-07-15", row.parcel.etaDate)
        assertEquals("ups", row.parcel.sourceId)
        assertEquals(1_752_300_000_000, row.parcel.lastRefreshedAt)
        assertEquals(1, row.events.size)
    }

    @Test fun unknown_status_and_null_fields_preserve_existing() = runTest {
        val r = repo(backgroundScope)
        db.parcelDao().upsertParcel(
            parcel.copy(status = TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 14), latestLocation = "Memphis, TN").toEntity(),
        )
        assertTrue(r.applySnapshot("p1", TrackingSnapshot(status = TrackingStatus.UNKNOWN), sourceId = "ups"))
        val row = assertNotNull(db.parcelDao().getById("p1"))
        assertEquals(TrackingStatus.IN_TRANSIT.name, row.parcel.status)
        assertEquals("2026-07-14", row.parcel.etaDate)
        assertEquals("Memphis, TN", row.parcel.latestLocation)
    }

    @Test fun missing_parcel_returns_false() = runTest {
        val r = repo(backgroundScope)
        assertFalse(r.applySnapshot("nope", TrackingSnapshot(status = TrackingStatus.DELIVERED), sourceId = "ups"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :data:jvmTest --tests '*ApplySnapshotTest*'`
Expected: FAIL — `applySnapshot` unresolved.

- [ ] **Step 3: Implement by refactoring `refreshRow`**

In `data/src/commonMain/kotlin/com/shiphappens/data/ParcelRepository.kt`, add the public method (below `refresh`) and collapse `refreshRow`'s success branch onto it:

```kotlin
    /**
     * Persist a snapshot produced OUTSIDE the normal track() path (e.g. the visible web view's
     * scrape-on-view). Identical merge semantics to a successful refresh: UNKNOWN status and null
     * fields never clobber existing values, events replace wholesale when non-empty, and the
     * parcel is pinned to [sourceId]. Returns false when the parcel no longer exists.
     */
    suspend fun applySnapshot(id: String, snapshot: TrackingSnapshot, sourceId: String): Boolean {
        val row = dao.getById(id) ?: return false
        val updated = row.parcel.copy(
            status = if (snapshot.status == TrackingStatus.UNKNOWN) row.parcel.status else snapshot.status.name,
            etaDate = snapshot.etaDate?.toString() ?: row.parcel.etaDate,
            etaTime = snapshot.etaTime?.toString() ?: row.parcel.etaTime,
            latestLocation = snapshot.latestLocation ?: row.parcel.latestLocation,
            sourceId = sourceId,
            lastRefreshedAt = clock.now().toEpochMilliseconds(),
        )
        dao.upsertParcel(updated)
        if (snapshot.events.isNotEmpty()) dao.replaceEvents(id, snapshot.events.map { it.toEntity(id) })
        return true
    }
```

Then replace `refreshRow`'s `is SourceResult.Success -> { ... }` branch body with:

```kotlin
            is SourceResult.Success -> {
                applySnapshot(id, result.value, source.descriptor.id)
                RefreshOutcome.Success
            }
```

(The old inline copy/upsert/replaceEvents block is deleted — one write path.)

- [ ] **Step 4: Run tests to verify pass + no regression**

Run: `./gradlew :data:jvmTest`
Expected: PASS — new tests plus the existing `ParcelRepositoryTest` (which exercises the refresh path now flowing through `applySnapshot`).

- [ ] **Step 5: Commit**

```bash
git add data/src
git commit -m "[data] Extract ParcelRepository.applySnapshot as the single snapshot write path"
```

---

### Task 6: Bridge scripts + Android `WebSessions` and cookie jar

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `source/webview/build.gradle.kts` (androidMain deps)
- Create: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/BridgeScripts.kt`
- Create: `source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/WebSessions.kt`
- Create: `source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/AndroidWebCookieJar.kt`
- Modify: `source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/di/WebModule.android.kt`
- Test: `source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/BridgeScriptsTest.kt`

**Interfaces:**
- Produces:
  - `object BridgeScripts { const val BRIDGE_NAME = "shipBridge"; fun captureScript(apiUrlPatterns: List<String>): String; fun extractionRunner(spec: WebProviderSpec): String }` (commonMain — pure string building, unit-tested).
  - Android: `object WebSessions { fun configure(webView: WebView, spec: WebProviderSpec, onPayload: (String) -> Unit, onEvent: (PageEvent) -> Unit) }` — installs the bridge (`addJavascriptInterface`), the capture script (document-start when supported, else page-start injection), cookie acceptance, logged-in probing, and the settle-then-extract runner. `onPayload`/`onEvent` may fire on non-main threads.
  - Android: `class AndroidWebCookieJar : WebCookieJar` (CookieManager flush + expire-all-cookies-for-domain).

- [ ] **Step 1: Add the androidx.webkit dependency**

`gradle/libs.versions.toml` — under `[versions]` add `androidxWebkit = "1.14.0"` (bump to the latest stable if newer resolves), and under `[libraries]`:

```toml
androidx-webkit = { module = "androidx.webkit:webkit", version.ref = "androidxWebkit" }
```

`source/webview/build.gradle.kts` — inside `sourceSets`, after the `commonTest.dependencies` block, add:

```kotlin
getByName("androidMain") {
    dependencies {
        implementation(libs.androidx.webkit)
        implementation(libs.koin.android)
    }
}
```

- [ ] **Step 2: Write the failing script-builder test**

`source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/BridgeScriptsTest.kt`:

```kotlin
package com.shiphappens.source.webview

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BridgeScriptsTest {
    @Test fun capture_script_embeds_patterns_and_bridge() {
        val js = BridgeScripts.captureScript(listOf(""".*ups\.com/track/api/Track/GetStatus.*"""))
        assertContains(js, BridgeScripts.BRIDGE_NAME)
        assertContains(js, """ups\\.com/track/api/Track/GetStatus""")  // regex source JSON-escaped for JS string literal
        assertContains(js, "window.fetch")
        assertContains(js, "XMLHttpRequest")
        assertTrue(js.contains("'api'") || js.contains("\"api\""))
    }

    @Test fun capture_script_is_idempotent_guarded() {
        assertContains(BridgeScripts.captureScript(emptyList()), "__shipCaptureInstalled")
    }

    @Test fun extraction_runner_embeds_markers_and_extractor() {
        val js = BridgeScripts.extractionRunner(testSpec())
        assertContains(js, "verify you are a human")            // challenge marker
        assertContains(js, "function(){return {page:'empty'}}") // spec extractionJs verbatim
        assertContains(js, BridgeScripts.BRIDGE_NAME)
        assertContains(js, "challenge")
        assertFalse(js.contains("\${"))                          // no unresolved Kotlin templates
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew :source:webview:jvmTest --tests '*BridgeScriptsTest*'`
Expected: FAIL — `BridgeScripts` not defined.

- [ ] **Step 4: Write `BridgeScripts` (commonMain)**

`source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/BridgeScripts.kt`:

```kotlin
package com.shiphappens.source.webview

/**
 * Builds the two JS programs injected into provider pages. Pure string assembly — kept in
 * commonMain so tests lock the JS surface without a browser. The bridge object (name
 * [BRIDGE_NAME]) is injected from Kotlin and exposes `postMessage(string)`.
 */
object BridgeScripts {
    const val BRIDGE_NAME = "shipBridge"

    private fun jsString(s: String): String = "\"" + s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "") + "\""

    /**
     * Installed at document start: taps window.fetch and XMLHttpRequest; response bodies whose
     * request URL matches any pattern are posted as {kind:'api', url, body} bridge payloads.
     */
    fun captureScript(apiUrlPatterns: List<String>): String {
        val patternArray = apiUrlPatterns.joinToString(",") { "new RegExp(${jsString(it)})" }
        return """
(function() {
  if (window.__shipCaptureInstalled) return;
  window.__shipCaptureInstalled = true;
  var patterns = [$patternArray];
  function matches(url) { try { return patterns.some(function(p) { return p.test(url); }); } catch (e) { return false; } }
  function post(kind, url, body) {
    try { $BRIDGE_NAME.postMessage(JSON.stringify({kind: kind, url: url, body: body})); } catch (e) {}
  }
  var origFetch = window.fetch;
  if (origFetch) {
    window.fetch = function(input, init) {
      var url = (typeof input === 'string') ? input : ((input && input.url) || '');
      var p = origFetch.apply(this, arguments);
      if (matches(url)) {
        p.then(function(resp) { try { resp.clone().text().then(function(t) { post('api', url, t); }); } catch (e) {} });
      }
      return p;
    };
  }
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function(method, url) { this.__shipUrl = '' + url; return origOpen.apply(this, arguments); };
  var origSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.send = function() {
    var xhr = this;
    if (matches(xhr.__shipUrl)) {
      xhr.addEventListener('load', function() { try { post('api', xhr.__shipUrl, xhr.responseText); } catch (e) {} });
    }
    return origSend.apply(this, arguments);
  };
})();
""".trimIndent()
    }

    /**
     * Run after page quiescence: checks challenge markers against visible text, then runs the
     * provider's extractor and posts its result as a {kind:'dom', body} payload. Always posts
     * exactly one payload (page:'empty' on any error) so callers can treat 'dom' as end-of-scrape.
     */
    fun extractionRunner(spec: WebProviderSpec): String {
        val markerArray = spec.challengeMarkers.joinToString(",") { jsString(it.lowercase()) }
        return """
(function() {
  function post(body) {
    try { $BRIDGE_NAME.postMessage(JSON.stringify({kind: 'dom', body: JSON.stringify(body)})); } catch (e) {}
  }
  try {
    var text = ((document.body && document.body.innerText) || '').toLowerCase();
    var markers = [$markerArray];
    for (var i = 0; i < markers.length; i++) {
      if (text.indexOf(markers[i]) !== -1) { post({page: 'challenge'}); return; }
    }
    var extractor = (${spec.extractionJs});
    post(extractor() || {page: 'empty'});
  } catch (e) {
    post({page: 'empty'});
  }
})();
""".trimIndent()
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :source:webview:jvmTest --tests '*BridgeScriptsTest*'`
Expected: PASS (3 tests)

- [ ] **Step 6: Write the Android session plumbing**

`source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/WebSessions.kt`:

```kotlin
package com.shiphappens.source.webview

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Shared WebView wiring for BOTH the visible More-details/login screens and the headless
 * scraper, so cookies, UA, and extraction behave identically everywhere.
 *
 * Threading: [onPayload] fires on the WebView's JavaBridge thread and [onEvent] on the main
 * thread — callers must hop to their own scope (ViewModels use viewModelScope.launch).
 */
object WebSessions {

    /** Delay after onPageFinished before running the DOM extraction runner (lets XHRs land first). */
    const val SETTLE_MS = 3_000L

    private class Bridge(private val onPayload: (String) -> Unit) {
        @JavascriptInterface
        fun postMessage(message: String) = onPayload(message)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun configure(webView: WebView, spec: WebProviderSpec, onPayload: (String) -> Unit, onEvent: (PageEvent) -> Unit) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Bridge object: same JS call shape (shipBridge.postMessage) on every WebView version.
        webView.addJavascriptInterface(Bridge(onPayload), BridgeScripts.BRIDGE_NAME)

        val captureJs = BridgeScripts.captureScript(spec.apiUrlPatterns)
        val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(webView, captureJs, spec.allowedOriginRules().toSet())
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // Fallback when document-start injection isn't available: inject ASAP at page
                // start. Racy against very early page requests, but the DOM extractor still
                // provides coverage when the capture layer misses.
                if (!documentStartSupported) view.evaluateJavascript(captureJs, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                onEvent(PageEvent.Finished(url))
                view.evaluateJavascript(spec.isLoggedInJs) { value ->
                    onEvent(PageEvent.LoggedIn(value == "true"))
                }
                view.postDelayed({
                    // The view may be destroyed (headless teardown) before the settle delay fires.
                    if (view.isAttachedToWindow || view.parent == null) {
                        runCatching { view.evaluateJavascript(BridgeScripts.extractionRunner(spec), null) }
                    }
                }, SETTLE_MS)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onEvent(PageEvent.LoadFailed(error.description?.toString()))
            }
        }
    }
}
```

`source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/AndroidWebCookieJar.kt`:

```kotlin
package com.shiphappens.source.webview

import android.webkit.CookieManager

/**
 * CookieManager is app-global and persists to disk on its own schedule; [flush] forces
 * persistence (call after login). Android has no per-domain clear API, so [clearForDomain]
 * expires every cookie readable for the domain by rewriting it with an epoch expiry.
 */
class AndroidWebCookieJar : WebCookieJar {
    override fun flush() = CookieManager.getInstance().flush()

    override fun clearForDomain(domain: String) {
        val manager = CookieManager.getInstance()
        listOf("https://$domain", "https://www.$domain").forEach { url ->
            manager.getCookie(url)?.split(";")?.forEach { cookie ->
                val name = cookie.substringBefore("=").trim()
                if (name.isNotEmpty()) {
                    manager.setCookie(url, "$name=; Domain=$domain; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                }
            }
        }
        manager.flush()
    }
}
```

Update `source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/di/WebModule.android.kt` to bind the real cookie jar (scraper stays no-op until Task 10):

```kotlin
package com.shiphappens.source.webview.di

import com.shiphappens.source.webview.AndroidWebCookieJar
import com.shiphappens.source.webview.NoWebScraper
import com.shiphappens.source.webview.WebCookieJar
import com.shiphappens.source.webview.WebScraper
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformWebModule(): Module = module {
    single<WebScraper> { NoWebScraper }
    single<WebCookieJar> { AndroidWebCookieJar() }
}
```

- [ ] **Step 7: Compile checks + tests**

Run: `./gradlew :source:webview:compileAndroidMain :source:webview:jvmTest :ui:testAndroidHostTest --tests '*AppModulesTest*'`
Expected: BUILD SUCCESSFUL / PASS (AppModulesTest already substitutes the web module, so `AndroidWebCookieJar` is never instantiated on the host JVM).

- [ ] **Step 8: Commit**

```bash
git add gradle/libs.versions.toml source/webview
git commit -m "[source] Add JS capture bridge, Android WebSessions wiring, and cookie jar"
```

---

### Task 7: Web detail screen + scrape-on-view + More-details button

**Files:**
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/navigation/Routes.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/web/PlatformWebView.kt`
- Create: `ui/src/androidMain/kotlin/com/shiphappens/ui/web/PlatformWebView.android.kt`
- Create: `ui/src/iosMain/kotlin/com/shiphappens/ui/web/PlatformWebView.ios.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/web/WebDetailViewModel.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/web/WebDetailScreen.kt`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/detail/DetailViewModel.kt`, `ui/src/commonMain/kotlin/com/shiphappens/ui/detail/DetailScreen.kt`, `ui/src/commonMain/kotlin/com/shiphappens/ui/App.kt`, `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt`
- Test: `ui/src/androidHostTest/kotlin/com/shiphappens/ui/web/WebDetailViewModelTest.kt`, modify `ui/src/androidHostTest/kotlin/com/shiphappens/ui/detail/DetailViewModelTest.kt`

**Interfaces:**
- Consumes: `WebProviderSpec`, `PageEvent`, `PayloadRouter`, `RouteResult`, `toSnapshot()`, `WebCapableSource` (Tasks 1–3); `ParcelRepository.applySnapshot` (Task 5); `SourceRegistry.all()`.
- Produces:
  - `@Serializable data class WebDetailRoute(val parcelId: String) : NavKey`
  - `@Composable expect fun PlatformWebView(url: String, spec: WebProviderSpec, onPayload: (String) -> Unit, onEvent: (PageEvent) -> Unit, modifier: Modifier)`
  - `class WebDetailViewModel(parcelId, ParcelRepository, SourceRegistry, SettingsRepository)` with `state: StateFlow<WebDetailUiState>`, `fun onPayload(json: String)`, `fun onEvent(event: PageEvent)`
  - `DetailUiState.webCarrierName: String?` (null hides the button); `DetailScreen(parcelId, onBack, onOpenWeb)`.

- [ ] **Step 1: Add the route**

In `ui/src/commonMain/kotlin/com/shiphappens/ui/navigation/Routes.kt` add:

```kotlin
@Serializable data class WebDetailRoute(val parcelId: String) : NavKey
```

- [ ] **Step 2: Write the failing ViewModel tests**

`ui/src/androidHostTest/kotlin/com/shiphappens/ui/web/WebDetailViewModelTest.kt` (same harness discipline as `DetailViewModelTest` — awaitState over real Room, never `advanceUntilIdle`):

```kotlin
package com.shiphappens.ui.web

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.*
import com.shiphappens.data.db.ShipHappensDb
import com.shiphappens.data.db.toEntity
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import com.shiphappens.source.ups.UpsWebSource
import com.shiphappens.source.webview.NoWebScraper
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath

class WebDetailViewModelTest {
    private class FixedClock : AppClock {
        override fun now() = Instant.fromEpochMilliseconds(1_752_300_000_000)
        override fun today() = LocalDate(2026, 7, 12)
    }

    private lateinit var db: ShipHappensDb
    private lateinit var repo: ParcelRepository
    private lateinit var vm: WebDetailViewModel

    private val parcel = Parcel(
        id = "p1", name = "Web parcel", trackingNumber = "1Z999AA10123456784",
        carrier = WellKnownCarriers.UPS, createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun TestScope.buildVm(): WebDetailViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = createTempDirectory("webdetail").toString()
        val settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        val registry = SourceRegistry(listOf(UpsWebSource(NoWebScraper)), settings)
        repo = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        val v = WebDetailViewModel(parcel.id, repo, registry, settings)
        backgroundScope.launch { v.state.collect() }
        vm = v
        return v
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private suspend fun awaitState(predicate: (WebDetailUiState) -> Boolean): WebDetailUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first(predicate) } }

    @Test fun state_exposes_ups_tracking_url_and_spec() = runTest {
        buildVm()
        db.parcelDao().upsertParcel(parcel.toEntity())
        val s = awaitState { it.loaded }
        assertEquals("UPS", s.carrierName)
        assertTrue(s.url.contains("ups.com/track"))
        assertTrue(s.url.contains("1Z999AA10123456784"))
        assertNotNull(s.spec)
        assertTrue(s.showLoginHint)  // not signed in yet
    }

    @Test fun tracking_payload_is_applied_to_repository() = runTest {
        buildVm()
        db.parcelDao().upsertParcel(parcel.toEntity())
        awaitState { it.loaded }
        vm.onPayload("""{"kind":"dom","body":"{\"page\":\"ok\",\"tracking\":{\"status\":\"OUT_FOR_DELIVERY\",\"location\":\"Memphis, TN\"}}"}""")
        val updated = withContext(Dispatchers.Default) {
            withTimeout(10_000) { repo.observeParcel("p1").first { it?.status == TrackingStatus.OUT_FOR_DELIVERY } }
        }
        assertEquals("Memphis, TN", assertNotNull(updated).latestLocation)
        assertEquals("ups", updated.sourceId)
    }

    @Test fun non_tracking_payload_changes_nothing() = runTest {
        buildVm()
        db.parcelDao().upsertParcel(parcel.toEntity())
        awaitState { it.loaded }
        vm.onPayload("garbage")
        vm.onPayload("""{"kind":"dom","body":"{\"page\":\"challenge\"}"}""")
        // Give writes (if any, wrongly) a chance to land, then confirm status unchanged.
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(250) }
        assertEquals(TrackingStatus.UNKNOWN, assertNotNull(db.parcelDao().getById("p1")).parcel.status.let { TrackingStatus.valueOf(it) })
    }
}
```

Also add to `DetailViewModelTest.kt`: the `vm(parcel)` builder constructs `DetailViewModel(parcel.id, repo, FixedClock())` — it gains a registry parameter (Step 5). Update the construction to:

```kotlin
val registry = SourceRegistry(listOf(com.shiphappens.source.ups.UpsWebSource(com.shiphappens.source.webview.NoWebScraper)), settings)
val repo = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
val v = DetailViewModel(parcel.id, repo, FixedClock(), registry)
```

and add one test:

```kotlin
    @Test fun web_button_shows_only_for_web_capable_carrier() = runTest {
        val ups = base(TrackingStatus.IN_TRANSIT, LocalDate(2026, 7, 14)).copy(carrier = WellKnownCarriers.UPS)
        vm(ups)
        db.parcelDao().upsertParcel(ups.toEntity())
        assertEquals("UPS", awaitState { it.loaded }.webCarrierName)
    }
```

(The existing tests use FEDEX parcels; after the change they additionally verify `webCarrierName == null` implicitly — no web source is registered for FedEx.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :ui:testAndroidHostTest --tests '*WebDetailViewModelTest*' --tests '*DetailViewModelTest*'`
Expected: FAIL — `WebDetailViewModel` missing; `DetailViewModel` constructor arity mismatch.

- [ ] **Step 4: Create the expect/actual WebView composable**

`ui/src/commonMain/kotlin/com/shiphappens/ui/web/PlatformWebView.kt`:

```kotlin
package com.shiphappens.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shiphappens.source.webview.PageEvent
import com.shiphappens.source.webview.WebProviderSpec

/**
 * A carrier web page with the ShipHappens capture bridge installed. Android backs this with
 * android.webkit.WebView via WebSessions; iOS shows a placeholder until a WKWebView actual
 * lands (spec §9). Callbacks may fire on non-main threads.
 */
@Composable
expect fun PlatformWebView(
    url: String,
    spec: WebProviderSpec,
    onPayload: (String) -> Unit,
    onEvent: (PageEvent) -> Unit,
    modifier: Modifier = Modifier,
)
```

`ui/src/androidMain/kotlin/com/shiphappens/ui/web/PlatformWebView.android.kt`:

```kotlin
package com.shiphappens.ui.web

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.shiphappens.source.webview.PageEvent
import com.shiphappens.source.webview.WebProviderSpec
import com.shiphappens.source.webview.WebSessions

@Composable
actual fun PlatformWebView(
    url: String,
    spec: WebProviderSpec,
    onPayload: (String) -> Unit,
    onEvent: (PageEvent) -> Unit,
    modifier: Modifier,
) {
    // key(url): recreate rather than reload — redirects mutate WebView.url, so an update-block
    // "reload if changed" check would loop.
    key(url) {
        // remember'd holder (not a plain local): the factory runs once, but the composable can
        // recompose before disposal — a plain local would reset to null and leak the WebView.
        val holder = remember { arrayOfNulls<WebView>(1) }
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    WebSessions.configure(this, spec, onPayload, onEvent)
                    loadUrl(url)
                    holder[0] = this
                }
            },
        )
        DisposableEffect(Unit) {
            onDispose {
                holder[0]?.destroy()
                holder[0] = null
            }
        }
    }
}
```

`ui/src/iosMain/kotlin/com/shiphappens/ui/web/PlatformWebView.ios.kt`:

```kotlin
package com.shiphappens.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.shiphappens.source.webview.PageEvent
import com.shiphappens.source.webview.WebProviderSpec

@Composable
actual fun PlatformWebView(
    url: String,
    spec: WebProviderSpec,
    onPayload: (String) -> Unit,
    onEvent: (PageEvent) -> Unit,
    modifier: Modifier,
) {
    // WKWebView actual is a future phase (spec §9).
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("The in-app ${spec.carrier.displayName} page isn't available on iOS yet.", Modifier.padding(24.dp))
    }
}
```

- [ ] **Step 5: Write the ViewModels and screen**

`ui/src/commonMain/kotlin/com/shiphappens/ui/web/WebDetailViewModel.kt`:

```kotlin
package com.shiphappens.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.data.ParcelRepository
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.design.accentHex
import com.shiphappens.source.webview.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WebDetailUiState(
    val loaded: Boolean = false,
    val url: String = "",
    val carrierName: String = "",
    val accentHex: String = "#17150F",
    val spec: WebProviderSpec? = null,
    val showLoginHint: Boolean = false,
)

class WebDetailViewModel(
    private val parcelId: String,
    private val repository: ParcelRepository,
    registry: SourceRegistry,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val webSources = registry.all().filterIsInstance<WebCapableSource>()

    val state: StateFlow<WebDetailUiState> =
        combine(repository.observeParcel(parcelId), settings.settings) { parcel, appSettings ->
            val spec = parcel?.let { p -> webSources.firstOrNull { it.webSpec.carrier.code == p.carrier.code }?.webSpec }
            if (parcel == null || spec == null) WebDetailUiState()
            else WebDetailUiState(
                loaded = true,
                url = spec.trackingUrl(parcel.normalizedTracking),
                carrierName = spec.carrier.displayName,
                accentHex = parcel.carrier.accentHex(),
                spec = spec,
                showLoginHint = appSettings.sourceConfigs[spec.sourceId]?.values?.get("loggedIn") != "true",
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebDetailUiState())

    /** Scrape-on-view: every Tracking payload the page produces is persisted immediately. */
    fun onPayload(json: String) {
        val spec = state.value.spec ?: return
        val routed = PayloadRouter(spec).route(json)
        if (routed is RouteResult.Tracking) {
            viewModelScope.launch { repository.applySnapshot(parcelId, routed.tracking.toSnapshot(), spec.sourceId) }
        }
    }

    /** A login that happens mid-browse also flips the persisted flag. */
    fun onEvent(event: PageEvent) {
        val spec = state.value.spec ?: return
        if (event is PageEvent.LoggedIn && event.loggedIn && state.value.showLoginHint) {
            viewModelScope.launch {
                settings.updateSourceConfig(spec.sourceId) { it.copy(values = it.values + ("loggedIn" to "true")) }
            }
        }
    }
}
```

`ui/src/commonMain/kotlin/com/shiphappens/ui/web/WebDetailScreen.kt`:

```kotlin
package com.shiphappens.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiphappens.design.ShipColors
import com.shiphappens.design.colorFromHex
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WebDetailScreen(parcelId: String, onBack: () -> Unit) {
    val vm: WebDetailViewModel = koinViewModel(key = "web-$parcelId") { parametersOf(parcelId) }
    val s by vm.state.collectAsState()
    val accent = colorFromHex(s.accentHex)

    Column(Modifier.fillMaxSize().background(ShipColors.bg)) {
        Row(
            Modifier.fillMaxWidth().background(accent)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ Back", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .16f))
                    .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp))
            Text(s.carrierName, color = Color.White.copy(alpha = .9f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        if (s.showLoginHint) {
            Text(
                "Sign in on this page for delivery photos and precise windows — Ship Happens remembers the session.",
                color = ShipColors.muted, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(ShipColors.card).padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }
        val spec = s.spec
        if (s.loaded && spec != null) {
            PlatformWebView(
                url = s.url, spec = spec,
                onPayload = vm::onPayload, onEvent = vm::onEvent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

- [ ] **Step 6: Thread the button through Detail + App + DI**

`DetailViewModel.kt` — constructor gains a registry, state gains the button field:

```kotlin
// imports to add:
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.source.webview.WebCapableSource
```

```kotlin
data class DetailUiState(
    // ...existing fields unchanged...
    val timeline: List<TimelineStepUi> = emptyList(),
    /** Carrier display name when an in-app web page exists for this parcel; null hides the button. */
    val webCarrierName: String? = null,
)
```

```kotlin
class DetailViewModel(
    parcelId: String,
    repository: ParcelRepository,
    private val clock: AppClock,
    registry: SourceRegistry,
) : ViewModel() {

    private val webCarrierNames: Map<String, String> =
        registry.all().filterIsInstance<WebCapableSource>()
            .associate { it.webSpec.carrier.code to it.webSpec.carrier.displayName }
```

and in `toDetail()`'s final `DetailUiState(...)` add:

```kotlin
            webCarrierName = webCarrierNames[carrier.code],
```

`DetailScreen.kt` — signatures gain the callback (defaults keep previews compiling):

```kotlin
@Composable
fun DetailScreen(parcelId: String, onBack: () -> Unit, onOpenWeb: () -> Unit = {}) {
    val vm: DetailViewModel = koinViewModel(key = parcelId) { parametersOf(parcelId) }
    val s by vm.state.collectAsState()
    DetailContent(s, onBack, onOpenWeb)
}

@Composable
fun DetailContent(state: DetailUiState, onBack: () -> Unit = {}, onOpenWeb: () -> Unit = {}) {
```

and after the tracking-number `DetailCard` (the last card in the scroll column) insert:

```kotlin
            state.webCarrierName?.let { name ->
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ShipColors.card)
                        .border(1.dp, ShipColors.hairline, RoundedCornerShape(18.dp))
                        .clickable(onClick = onOpenWeb).padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("More details on $name", color = ShipColors.ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("›", color = ShipColors.faint, fontSize = 16.sp)
                }
            }
```

`App.kt` — import `com.shiphappens.ui.web.WebDetailScreen`, update entries:

```kotlin
                entry<DetailRoute> { route ->
                    DetailScreen(
                        route.parcelId,
                        onBack = { backStack.removeLastOrNull() },
                        onOpenWeb = { backStack.add(WebDetailRoute(route.parcelId)) },
                    )
                }
                entry<WebDetailRoute> { route -> WebDetailScreen(route.parcelId, onBack = { backStack.removeLastOrNull() }) }
```

`AppModules.kt` — update the two parameterized VM factories in `uiModule`:

```kotlin
val uiModule = module {
    viewModelOf(::ListViewModel)
    viewModelOf(::SettingsViewModel)
    viewModel { params -> DetailViewModel(params.get(), get(), get(), get()) }
    viewModel { params -> com.shiphappens.ui.web.WebDetailViewModel(params.get(), get(), get(), get()) }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :ui:testAndroidHostTest`
Expected: PASS — WebDetailViewModelTest, updated DetailViewModelTest, AppModulesTest (new VM factory resolves), and all pre-existing tests.

- [ ] **Step 8: Compile the iOS actual**

Run: `./gradlew :ui:compileKotlinIosSimulatorArm64`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add ui/src ui/build.gradle.kts
git commit -m "[ui] Add web detail screen with scrape-on-view and More-details button"
```

---

### Task 8: UPS login flow (Settings sign-in/out + login WebView)

**Files:**
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/navigation/Routes.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/web/WebLoginViewModel.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/web/WebLoginScreen.kt`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/settings/SettingsViewModel.kt`, `ui/src/commonMain/kotlin/com/shiphappens/ui/settings/SettingsScreen.kt`, `ui/src/commonMain/kotlin/com/shiphappens/ui/App.kt`, `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt`
- Test: modify `ui/src/androidHostTest/kotlin/com/shiphappens/ui/settings/SettingsViewModelTest.kt`; create `ui/src/androidHostTest/kotlin/com/shiphappens/ui/web/WebLoginViewModelTest.kt`

**Interfaces:**
- Consumes: `WebCapableSource`, `WebCookieJar`, `PageEvent` (Task 3); `SettingsRepository.updateSourceConfig`; `PlatformWebView` (Task 7).
- Produces:
  - `@Serializable data class WebLoginRoute(val sourceId: String) : NavKey`
  - `class WebLoginViewModel(sourceId, SourceRegistry, SettingsRepository, WebCookieJar)`; state has `done: Boolean` — screen pops on done.
  - `SourceCardUi` gains `val webCapable: Boolean = false, val signedIn: Boolean = false`.
  - `SettingsViewModel` gains ctor param `cookieJar: WebCookieJar` and `fun onSignOut(sourceId: String)`; `SettingsScreen(onBack, onOpenLogin: (String) -> Unit)`.

- [ ] **Step 1: Add the route**

In `Routes.kt` add:

```kotlin
@Serializable data class WebLoginRoute(val sourceId: String) : NavKey
```

- [ ] **Step 2: Write the failing tests**

`ui/src/androidHostTest/kotlin/com/shiphappens/ui/web/WebLoginViewModelTest.kt`:

```kotlin
package com.shiphappens.ui.web

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.source.ups.UpsWebSource
import com.shiphappens.source.webview.NoOpCookieJar
import com.shiphappens.source.webview.NoWebScraper
import com.shiphappens.source.webview.PageEvent
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

class WebLoginViewModelTest {
    private lateinit var settings: SettingsRepository
    private lateinit var vm: WebLoginViewModel

    private fun TestScope.buildVm(): WebLoginViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = createTempDirectory("weblogin").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        val registry = SourceRegistry(listOf(UpsWebSource(NoWebScraper)), settings)
        val v = WebLoginViewModel("ups", registry, settings, NoOpCookieJar)
        backgroundScope.launch { v.state.collect() }
        vm = v
        return v
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private suspend fun awaitState(predicate: (WebLoginUiState) -> Boolean): WebLoginUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first(predicate) } }

    @Test fun state_exposes_login_url() = runTest {
        buildVm()
        val s = awaitState { it.url.isNotEmpty() }
        assertTrue(s.url.contains("ups.com"))
        assertEquals("UPS", s.name)
        assertFalse(s.done)
    }

    @Test fun logged_in_event_persists_flag_and_completes() = runTest {
        buildVm()
        awaitState { it.url.isNotEmpty() }
        vm.onEvent(PageEvent.LoggedIn(true))
        assertTrue(awaitState { it.done }.done)
        val cfg = withContext(Dispatchers.Default) {
            withTimeout(10_000) { settings.settings.first { it.sourceConfigs["ups"]?.values?.get("loggedIn") == "true" } }
        }
        assertEquals("true", cfg.sourceConfigs["ups"]?.values?.get("loggedIn"))
    }

    @Test fun logged_out_event_is_ignored() = runTest {
        buildVm()
        awaitState { it.url.isNotEmpty() }
        vm.onEvent(PageEvent.LoggedIn(false))
        vm.onEvent(PageEvent.Finished("https://www.ups.com/lasso/signin"))
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(250) }
        assertFalse(vm.state.value.done)
    }
}
```

In `SettingsViewModelTest.kt`: every direct construction of `SettingsViewModel(...)` gains the new final argument `com.shiphappens.source.webview.NoOpCookieJar`. Add one test following the file's existing await pattern:

```kotlin
    @Test fun ups_card_is_web_capable_and_sign_out_clears_flag() = runTest {
        // Build the VM exactly as this file's existing helper does, with sources including
        // UpsWebSource(NoWebScraper). Then:
        settings.updateSourceConfig("ups") { it.copy(enabled = true, values = mapOf("loggedIn" to "true")) }
        val signedIn = awaitState { s -> s.carriers.firstOrNull { it.id == "ups" }?.signedIn == true }
        assertTrue(signedIn.carriers.first { it.id == "ups" }.webCapable)
        vm.onSignOut("ups")
        awaitState { s -> s.carriers.firstOrNull { it.id == "ups" }?.signedIn == false }
    }
```

(Adapt helper/property names to the file's existing conventions — it already builds a registry + settings + VM and has an awaitState helper mirroring the other VM tests. If its registry uses `FakeSource`s only, add a `UpsWebSource(NoWebScraper)` to the source list.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :ui:testAndroidHostTest --tests '*WebLoginViewModelTest*' --tests '*SettingsViewModelTest*'`
Expected: FAIL — `WebLoginViewModel` missing; `SettingsViewModel` arity/field mismatches.

- [ ] **Step 4: Implement the login ViewModel + screen**

`ui/src/commonMain/kotlin/com/shiphappens/ui/web/WebLoginViewModel.kt`:

```kotlin
package com.shiphappens.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.source.webview.PageEvent
import com.shiphappens.source.webview.WebCapableSource
import com.shiphappens.source.webview.WebCookieJar
import com.shiphappens.source.webview.WebProviderSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WebLoginUiState(
    val url: String = "",
    val name: String = "",
    val accentHex: String = "#17150F",
    val spec: WebProviderSpec? = null,
    val done: Boolean = false,
)

class WebLoginViewModel(
    private val sourceId: String,
    registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val cookieJar: WebCookieJar,
) : ViewModel() {

    private val spec: WebProviderSpec? =
        registry.all().filterIsInstance<WebCapableSource>().firstOrNull { it.webSpec.sourceId == sourceId }?.webSpec

    private val done = MutableStateFlow(false)

    val state: StateFlow<WebLoginUiState> = done.map { d ->
        spec?.let {
            WebLoginUiState(
                url = it.loginUrl, name = it.carrier.displayName,
                accentHex = it.carrier.accentColorHex ?: "#17150F", spec = it, done = d,
            )
        } ?: WebLoginUiState(done = d)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebLoginUiState())

    fun onEvent(event: PageEvent) {
        if (event is PageEvent.LoggedIn && event.loggedIn && !done.value) {
            viewModelScope.launch {
                settings.updateSourceConfig(sourceId) { it.copy(values = it.values + ("loggedIn" to "true")) }
                cookieJar.flush()  // force cookie persistence so the session survives process death
                done.value = true
            }
        }
    }
}
```

`ui/src/commonMain/kotlin/com/shiphappens/ui/web/WebLoginScreen.kt`:

```kotlin
package com.shiphappens.ui.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiphappens.design.ShipColors
import com.shiphappens.design.colorFromHex
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WebLoginScreen(sourceId: String, onBack: () -> Unit) {
    val vm: WebLoginViewModel = koinViewModel(key = "login-$sourceId") { parametersOf(sourceId) }
    val s by vm.state.collectAsState()

    LaunchedEffect(s.done) { if (s.done) onBack() }

    Column(Modifier.fillMaxSize().background(ShipColors.bg)) {
        Row(
            Modifier.fillMaxWidth().background(colorFromHex(s.accentHex))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹ Cancel", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .16f))
                    .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp))
            Text("Sign in to ${s.name}", color = Color.White.copy(alpha = .9f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        val spec = s.spec
        if (spec != null) {
            PlatformWebView(
                url = s.url, spec = spec,
                onPayload = { /* login page produces no tracking payloads worth applying */ },
                onEvent = vm::onEvent,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

- [ ] **Step 5: Extend Settings (ViewModel, screen, wiring)**

`SettingsViewModel.kt` changes:

```kotlin
// imports to add:
import com.shiphappens.source.webview.WebCapableSource
import com.shiphappens.source.webview.WebCookieJar
```

`SourceCardUi` gains two defaulted fields (previews keep compiling):

```kotlin
data class SourceCardUi(
    val id: String, val name: String, val accentHex: String, val enabled: Boolean,
    val statusText: String, val statusColorHex: String, val fields: List<FieldUi>, val endpointText: String?,
    val webCapable: Boolean = false, val signedIn: Boolean = false,
)
```

Constructor gains the cookie jar:

```kotlin
class SettingsViewModel(
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val repository: ParcelRepository,
    private val cookieJar: WebCookieJar,
) : ViewModel() {
```

In the card-building `map`, resolve web capability and populate the fields (add before the `SourceCardUi(` construction, inside the lambda):

```kotlin
            val webSpec = (src as? WebCapableSource)?.webSpec
```

and add to the `SourceCardUi(...)` call:

```kotlin
                webCapable = webSpec != null,
                signedIn = webSpec != null && cfg.values["loggedIn"] == "true",
```

Add the sign-out action (after `onTest`):

```kotlin
    fun onSignOut(sourceId: String) {
        viewModelScope.launch {
            val spec = (registry.all().firstOrNull { it.descriptor.id == sourceId } as? WebCapableSource)?.webSpec ?: return@launch
            cookieJar.clearForDomain(spec.cookieDomain)
            settings.updateSourceConfig(sourceId) { it.copy(values = it.values - "loggedIn") }
            flash("Signed out of ${spec.carrier.displayName}")
        }
    }
```

`SettingsScreen.kt` changes — thread two new callbacks:

```kotlin
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogin: (String) -> Unit = {}, vm: SettingsViewModel = koinViewModel()) {
```

pass `onSignIn = onOpenLogin, onSignOut = vm::onSignOut` into `SettingsContent`, whose signature gains:

```kotlin
    onSignIn: (String) -> Unit = {},
    onSignOut: (String) -> Unit = {},
```

and forwards both into each `SourceCard(it, onToggle, onField, onTest, onSignIn, onSignOut)`. In `SourceCard` (same parameter additions), render the login row when applicable — insert immediately before the existing `TextButton(onClick = { onTest(card.id) })` row:

```kotlin
        if (card.webCapable && card.enabled) {
            TextButton(onClick = { if (card.signedIn) onSignOut(card.id) else onSignIn(card.id) }) {
                Text(if (card.signedIn) "Sign out of ${card.name}" else "Sign in to ${card.name}", fontSize = 13.sp)
            }
        }
```

`App.kt` — import `com.shiphappens.ui.web.WebLoginScreen`, update entries:

```kotlin
                entry<SettingsRoute> {
                    SettingsScreen(
                        onBack = { backStack.removeLastOrNull() },
                        onOpenLogin = { sourceId -> backStack.add(WebLoginRoute(sourceId)) },
                    )
                }
                entry<WebLoginRoute> { route -> WebLoginScreen(route.sourceId, onBack = { backStack.removeLastOrNull() }) }
```

`AppModules.kt` — `viewModelOf(::SettingsViewModel)` picks up the new parameter automatically (Koin resolves `WebCookieJar` from `platformWebModule()`); add the login VM factory to `uiModule`:

```kotlin
    viewModel { params -> com.shiphappens.ui.web.WebLoginViewModel(params.get(), get(), get(), get()) }
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./gradlew :ui:testAndroidHostTest`
Expected: PASS — WebLoginViewModelTest, SettingsViewModelTest (updated), AppModulesTest, and everything else.

- [ ] **Step 7: Commit**

```bash
git add ui/src
git commit -m "[ui] Add carrier web login flow with cookie-backed session and sign-out"
```

---

### Task 9: `ScrapeThrottle` (Phase 2 begins)

**Files:**
- Create: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/ScrapeThrottle.kt`
- Test: `source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/ScrapeThrottleTest.kt`

**Interfaces:**
- Produces: `class ScrapeThrottle(minInterval: Duration = 15.minutes, now: () -> Instant)` with `fun cached(key: String): ScrapeResult?` and `fun record(key: String, result: ScrapeResult)`. Politeness cache for headless scrapes: within `minInterval` of a recorded result for the same key (tracking URL), return the cached result instead of re-scraping — even on forced refresh. Only successful `Payloads` results should be recorded by callers, so failures (e.g. AUTH before the user logs in) retry immediately.

- [ ] **Step 1: Write the failing test**

`source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/ScrapeThrottleTest.kt`:

```kotlin
package com.shiphappens.source.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ScrapeThrottleTest {
    private var nowMs = 0L
    private val throttle = ScrapeThrottle(minInterval = 15.minutes, now = { Instant.fromEpochMilliseconds(nowMs) })
    private val result = ScrapeResult.Payloads(listOf("p"))

    @Test fun empty_throttle_has_no_cached_result() {
        assertNull(throttle.cached("url1"))
    }

    @Test fun within_interval_returns_cached_result() {
        throttle.record("url1", result)
        nowMs = 14 * 60 * 1000
        assertEquals(result, throttle.cached("url1"))
        assertNull(throttle.cached("url2"))  // per-key
    }

    @Test fun after_interval_cache_expires() {
        throttle.record("url1", result)
        nowMs = 15 * 60 * 1000
        assertNull(throttle.cached("url1"))
    }

    @Test fun rerecording_resets_the_window() {
        throttle.record("url1", result)
        nowMs = 10 * 60 * 1000
        throttle.record("url1", result)
        nowMs = 20 * 60 * 1000
        assertEquals(result, throttle.cached("url1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :source:webview:jvmTest --tests '*ScrapeThrottleTest*'`
Expected: FAIL — `ScrapeThrottle` not defined.

- [ ] **Step 3: Write the implementation**

`source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/ScrapeThrottle.kt`:

```kotlin
package com.shiphappens.source.webview

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Politeness cache for headless scrapes (spec §6): at most one real page load per key per
 * [minInterval], even on forced refresh. Callers should record ONLY successful results so
 * failures (login wall before the user signs in, transient network) retry without waiting.
 * Not thread-safe by itself — the headless scraper serializes access behind its mutex.
 */
class ScrapeThrottle(
    private val minInterval: Duration = 15.minutes,
    private val now: () -> Instant,
) {
    private data class Entry(val at: Instant, val result: ScrapeResult)
    private val entries = mutableMapOf<String, Entry>()

    fun cached(key: String): ScrapeResult? =
        entries[key]?.takeIf { now() - it.at < minInterval }?.result

    fun record(key: String, result: ScrapeResult) {
        entries[key] = Entry(now(), result)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :source:webview:jvmTest --tests '*ScrapeThrottleTest*'`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add source/webview
git commit -m "[source] Add ScrapeThrottle politeness cache for headless scrapes"
```

---

### Task 10: `HeadlessWebViewScraper` + Android DI swap

**Files:**
- Create: `source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/HeadlessWebViewScraper.kt`
- Modify: `source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/di/WebModule.android.kt`

**Interfaces:**
- Consumes: `WebSessions.configure`, `BridgePayload`, `ScrapeThrottle`, `PageEvent` (Tasks 3, 6, 9); Android `Context` from Koin (`androidContext()`).
- Produces: `class HeadlessWebViewScraper(context: Context, throttle: ScrapeThrottle) : WebScraper` — `isAvailable = true`. After this task, `UpsWebSource.descriptor.implemented == true` on Android, so the registry routes UPS parcels here during pull-to-refresh / foreground refresh. Behavior is covered by `WebViewBasedSourceTest` (failure mapping) + Task 11 live QA; the class itself is thin orchestration around tested parts.

- [ ] **Step 1: Write the scraper**

`source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/HeadlessWebViewScraper.kt`:

```kotlin
package com.shiphappens.source.webview

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Runs a real page load in an off-screen WebView and returns the bridge payloads it produced.
 *
 * - One scrape at a time (mutex) — spec §6.
 * - WebView must be created and driven on the main thread; it is never attached to a window.
 *   A fresh WebView per scrape keeps callback wiring simple and leak-free; the session itself
 *   (cookies) is app-global via CookieManager, so login state persists across scrapes.
 * - The extraction runner always posts exactly one 'dom' payload after the settle delay
 *   (see BridgeScripts), so its arrival is the end-of-scrape signal.
 * - Politeness: successful results are cached per URL in [throttle]; within the window the
 *   cached result is returned without loading the page at all — even on forced refresh.
 */
class HeadlessWebViewScraper(
    private val context: Context,
    private val throttle: ScrapeThrottle,
) : WebScraper {

    override val isAvailable = true
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun scrape(spec: WebProviderSpec, trackingNumber: String): ScrapeResult = mutex.withLock {
        val url = spec.trackingUrl(trackingNumber)
        throttle.cached(url)?.let { return it }
        val result = withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(SCRAPE_TIMEOUT_MS) { runScrape(spec, url) } ?: ScrapeResult.Timeout
        }
        if (result is ScrapeResult.Payloads) throttle.record(url, result)
        result
    }

    private suspend fun runScrape(spec: WebProviderSpec, url: String): ScrapeResult {
        val webView = WebView(context)
        return try {
            // Give the detached view a plausible viewport so the page lays out and runs scripts.
            webView.layout(0, 0, 1080, 2000)
            val payloads = mutableListOf<String>()
            val done = CompletableDeferred<ScrapeResult>()
            WebSessions.configure(
                webView, spec,
                onPayload = { payload ->
                    val kind = runCatching { json.decodeFromString<BridgePayload>(payload).kind }.getOrNull()
                    synchronized(payloads) { payloads += payload }
                    if (kind == "dom") done.complete(ScrapeResult.Payloads(synchronized(payloads) { payloads.toList() }))
                },
                onEvent = { event ->
                    if (event is PageEvent.LoadFailed) done.complete(ScrapeResult.LoadError(event.message))
                },
            )
            webView.loadUrl(url)
            done.await()
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private companion object {
        const val SCRAPE_TIMEOUT_MS = 25_000L
    }
}
```

- [ ] **Step 2: Swap the Android DI binding**

Replace `source/webview/src/androidMain/kotlin/com/shiphappens/source/webview/di/WebModule.android.kt` contents:

```kotlin
package com.shiphappens.source.webview.di

import com.shiphappens.source.webview.AndroidWebCookieJar
import com.shiphappens.source.webview.HeadlessWebViewScraper
import com.shiphappens.source.webview.ScrapeThrottle
import com.shiphappens.source.webview.WebCookieJar
import com.shiphappens.source.webview.WebScraper
import kotlin.time.Clock
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformWebModule(): Module = module {
    single { ScrapeThrottle(now = { Clock.System.now() }) }
    single<WebScraper> { HeadlessWebViewScraper(androidContext(), get()) }
    single<WebCookieJar> { AndroidWebCookieJar() }
}
```

- [ ] **Step 3: Compile + regression tests**

Run: `./gradlew :source:webview:compileAndroidMain :source:webview:jvmTest :ui:testAndroidHostTest :data:jvmTest :source:ups:jvmTest`
Expected: BUILD SUCCESSFUL / all PASS. (`AppModulesTest` substitutes the web module, so the Context-requiring scraper is never constructed on the host JVM — that substitution was future-proofed in Task 3.)

- [ ] **Step 4: Build the debug APK to prove end-to-end assembly**

Run: `./gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add source/webview
git commit -m "[source] Add HeadlessWebViewScraper and enable UPS web tracking on Android"
```

---

### Task 11: Manual QA checklist + full verification sweep

**Files:**
- Create: `docs/superpowers/qa/2026-07-12-webview-ups-qa.md`

**Interfaces:** none (documentation + verification).

- [ ] **Step 1: Write the QA checklist**

`docs/superpowers/qa/2026-07-12-webview-ups-qa.md`:

```markdown
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
```

- [ ] **Step 2: Full verification sweep**

Run: `./gradlew :domain:jvmTest :source:api:jvmTest :source:webview:jvmTest :source:ups:jvmTest :data:jvmTest :ui:testAndroidHostTest :app-android:assembleDebug :ui:compileKotlinIosSimulatorArm64`
Expected: all PASS / BUILD SUCCESSFUL.

- [ ] **Step 3: Execute the QA checklist's Phase 1 + Phase 2 sections on a device/emulator**

This is the step where live-page assumptions (API endpoint pattern, DOM selectors, login detection) get validated and — if UPS's site has drifted — fixed in `UpsWebSpec`/`UpsApiParser` with fixtures re-recorded. Budget real time for it; it is expected to require JS tweaks.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/qa
git commit -m "[docs] Add manual QA checklist for WebView UPS source"
```

---

## Spec coverage map

| Spec section | Tasks |
|---|---|
| §1 modules/layering | 1, 2, 3 |
| §2 provider spec | 2, 4 |
| §3 extraction pipeline (API capture, DOM fallback; AI hook deferred — see note) | 4, 6 |
| §4 login & cookies | 6, 8 |
| §5 Phase 1 visible + scrape-on-view | 5, 7 |
| §6 Phase 2 headless + throttle | 9, 10 |
| §7 error taxonomy | 3 (mapping), 4 (detection JS) |
| §8 testing strategy | every task + 11 |
| §9 out of scope | honored — no WorkManager, no WKWebView, no My Choice import |

**Note on the AI hook (spec §3 layer 3):** the spec defines `AiPageExtractor` as a no-op **hook**. YAGNI applies until there's an implementation to plug in: the seam where it would sit is `PayloadRouter`/`WebViewBasedSource.track()`'s "no Tracking payload" branch, and adding the interface then is a 20-line, non-breaking change. This plan deliberately does not add the dead interface; the spec's future-phases list (§9) already tracks the real implementation.

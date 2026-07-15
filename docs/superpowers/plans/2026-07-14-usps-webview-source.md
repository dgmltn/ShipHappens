# USPS WebView Tracking Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the USPS stub into a live tracking source using the existing WebView scraping framework, per `docs/superpowers/specs/2026-07-14-usps-webview-source-design.md`.

**Architecture:** Mirror `:source:ups` exactly: a `WebProviderSpec` (`UspsWebSpec`) carrying URLs/JS/patterns, a tolerant JSON parser (`UspsApiParser`) for captured in-page API bodies, and a thin `UspsWebSource : WebViewBasedSource` that only supplies `detectCarrier`. DOM extraction is the reliable layer (recon 2026-07-14: tools.usps.com is behind Akamai, so the in-page API shape is unverified until device QA); the API pattern ships broad and gets tightened during live QA with ScrapeTracer.

**Tech Stack:** Kotlin Multiplatform (android/jvm/iosArm64/iosSimulatorArm64), kotlinx-serialization, kotlinx-datetime, Koin, existing `:source:webview` framework.

## Global Constraints

- KMP library modules use `com.android.kotlin.multiplatform.library` with android config inside `kotlin { android { } }` (AGP 9 shape — do not apply `org.jetbrains.kotlin.android`).
- Unit tests for this module run with `./gradlew :source:usps:jvmTest`; iOS compile check is `./gradlew :source:usps:compileKotlinIosSimulatorArm64`.
- Parser contract (same as `UpsApiParser`): never throw; return `null` for undecodable/foreign bodies; unknown wording degrades to `"UNKNOWN"`; `ScrapedEvent.timestamp` is an ISO-8601 instant string; events chronological ASCENDING.
- Status vocabulary = `TrackingStatus` enum names: `LABEL_CREATED, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, UNKNOWN`.
- Commit messages start with a bracketed one-word module/topic tag (`[source]`, `[docs]`, `[tests]`) and end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- The USPS sample tracking number for tests/QA is `9434636106092288655003`; tracking URL shape `https://tools.usps.com/tracking/<number>`.
- Do not commit `gradle/gradle-daemon-jvm.properties` if the build auto-generates it.

---

### Task 1: Build wiring + UspsApiParser

**Files:**
- Modify: `source/usps/build.gradle.kts`
- Create: `source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsApiParserTest.kt`
- Create: `source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsApiParser.kt`

**Interfaces:**
- Consumes: `ScrapedTracking(status, etaDate, etaTime, location, events)` / `ScrapedEvent(timestamp, description, location, status)` from `:source:webview` (`com.shiphappens.source.webview`).
- Produces: `object UspsApiParser { fun parse(body: String): ScrapedTracking? }` — used by Task 2's `UspsWebSpec.parseApi`.

- [ ] **Step 1: Update build config so the module can see the webview framework + serialization**

Replace the full contents of `source/usps/build.gradle.kts` with (this is `source/ups/build.gradle.kts` with the namespace swapped):

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.shiphappens.source.usps"
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
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
```

- [ ] **Step 2: Write the failing parser test**

Create `source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsApiParserTest.kt`:

```kotlin
package com.shiphappens.source.usps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// PROVISIONAL fixture: tools.usps.com sits behind Akamai, so this shape could not be captured
// off-device (see design spec §Decisions). Vocabulary mirrors USPS's tracking-API field names
// (statusCategory/expectedDeliveryDate/trackingEvents). Live QA (see
// docs/superpowers/qa/2026-07-14-webview-usps-qa.md) replaces this with a recorded body and
// adjusts the DTOs — same workflow that produced UpsApiParserTest's fixture.
private const val FIXTURE = """
{
  "statusCategory": "In Transit",
  "statusSummary": "Your item departed our USPS facility in SAN FRANCISCO CA DISTRIBUTION CENTER on July 14, 2026.",
  "expectedDeliveryDate": "2026-07-16",
  "expectedDeliveryTime": "20:00:00",
  "trackingEvents": [
    {"eventType": "Departed USPS Regional Facility", "eventTimestamp": "2026-07-14T02:18:00", "eventCity": "SAN FRANCISCO", "eventState": "CA"},
    {"eventType": "Arrived at USPS Regional Facility", "eventTimestamp": "2026-07-13T21:04:00", "eventCity": "SAN FRANCISCO", "eventState": "CA"},
    {"eventType": "Accepted at USPS Origin Facility", "eventTimestamp": "2026-07-13T15:47:00", "eventCity": "SANTA ROSA", "eventState": "CA"},
    {"eventType": "Shipping Label Created, USPS Awaiting Item", "eventTimestamp": "2026-07-12T09:12:00", "eventCity": "SANTA ROSA", "eventState": "CA"}
  ]
}
"""

class UspsApiParserTest {

    @Test fun parses_status_eta_location_and_events() {
        val t = assertNotNull(UspsApiParser.parse(FIXTURE))
        assertEquals("IN_TRANSIT", t.status)
        assertEquals("2026-07-16", t.etaDate)
        assertEquals("20:00", t.etaTime)
        assertEquals("SAN FRANCISCO, CA", t.location)  // newest event's city/state
        assertEquals(4, t.events.size)
        // Events chronological ASCENDING (domain expectation); USPS sends newest-first.
        assertTrue(t.events.first().description.startsWith("Shipping Label Created"))
        assertEquals("LABEL_CREATED", t.events.first().status)
        assertEquals("SHIPPED", t.events[1].status)     // "Accepted at USPS Origin Facility"
        assertEquals("IN_TRANSIT", t.events.last().status)
        assertTrue(t.events.all { runCatching { kotlin.time.Instant.parse(it.timestamp) }.isSuccess })
    }

    @Test fun status_wordings_map_to_canonical() {
        fun withCategory(c: String) = """{"statusCategory":"$c"}"""
        assertEquals("LABEL_CREATED", UspsApiParser.parse(withCategory("Pre-Shipment"))!!.status)
        assertEquals("SHIPPED", UspsApiParser.parse(withCategory("Accepted"))!!.status)
        assertEquals("IN_TRANSIT", UspsApiParser.parse(withCategory("Moving Through Network"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", UspsApiParser.parse(withCategory("Out for Delivery"))!!.status)
        assertEquals("DELIVERED", UspsApiParser.parse(withCategory("Delivered to Agent"))!!.status)
        assertEquals("EXCEPTION", UspsApiParser.parse(withCategory("Alert"))!!.status)
        assertEquals("UNKNOWN", UspsApiParser.parse(withCategory("Some New Wording"))!!.status)
    }

    @Test fun overall_status_falls_back_to_newest_event() {
        val t = assertNotNull(UspsApiParser.parse(
            """{"statusCategory":"Something Novel","trackingEvents":[
                 {"eventType":"Delivered, In/At Mailbox","eventTimestamp":"2026-07-14T13:02:00"}]}""",
        ))
        assertEquals("DELIVERED", t.status)
    }

    @Test fun tolerates_alternate_date_time_formats() {
        val t = assertNotNull(UspsApiParser.parse(
            """{"statusCategory":"In Transit","expectedDeliveryDate":"Wednesday, July 16, 2026","expectedDeliveryTime":"8:00pm"}""",
        ))
        assertEquals("2026-07-16", t.etaDate)
        assertEquals("20:00", t.etaTime)
        val slash = assertNotNull(UspsApiParser.parse(
            """{"statusCategory":"In Transit","expectedDeliveryDate":"07/16/2026"}""",
        ))
        assertEquals("2026-07-16", slash.etaDate)
    }

    @Test fun accepts_instant_event_timestamps() {
        val t = assertNotNull(UspsApiParser.parse(
            """{"statusCategory":"In Transit","trackingEvents":[
                 {"eventType":"Arrived at USPS Facility","eventTimestamp":"2026-07-14T02:18:00Z"}]}""",
        ))
        assertEquals("2026-07-14T02:18:00Z", t.events.single().timestamp)
    }

    @Test fun rejects_non_tracking_json() {
        assertNull(UspsApiParser.parse("""{"unrelated": true}"""))
        assertNull(UspsApiParser.parse("""{"trackingEvents": []}"""))
        assertNull(UspsApiParser.parse("not json"))
    }

    @Test fun tolerates_missing_fields() {
        val t = assertNotNull(UspsApiParser.parse("""{"statusCategory":"Delivered"}"""))
        assertEquals("DELIVERED", t.status)
        assertNull(t.etaDate)
        assertNull(t.etaTime)
        assertNull(t.location)
        assertTrue(t.events.isEmpty())
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :source:usps:jvmTest`
Expected: FAIL — compilation error, `Unresolved reference: UspsApiParser`.

- [ ] **Step 4: Implement the parser**

Create `source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsApiParser.kt`:

```kotlin
package com.shiphappens.source.usps

import com.shiphappens.source.webview.ScrapedEvent
import com.shiphappens.source.webview.ScrapedTracking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

// PROVISIONAL DTOs (Akamai blocked off-device capture of the real in-page API — see design
// spec). Field vocabulary follows USPS's tracking-API naming; live QA replaces the recorded
// fixture and adjusts these to the captured body.
@Serializable private data class UspsTrackResponse(
    val statusCategory: String? = null,
    val statusSummary: String? = null,
    val expectedDeliveryDate: String? = null,
    val expectedDeliveryTime: String? = null,
    val trackingEvents: List<UspsEvent>? = null,
)

@Serializable private data class UspsEvent(
    val eventType: String? = null,
    val eventTimestamp: String? = null,
    val eventCity: String? = null,
    val eventState: String? = null,
)

/**
 * Maps tools.usps.com's in-page tracking API JSON to the canonical [ScrapedTracking].
 * Same contract as UpsApiParser: every field optional, unknown wording degrades to UNKNOWN,
 * null for bodies that aren't tracking JSON at all. Event timestamps without a zone are
 * interpreted in the device zone (same documented tradeoff as UPS).
 */
object UspsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): ScrapedTracking? {
        val r = runCatching { json.decodeFromString<UspsTrackResponse>(body) }.getOrNull() ?: return null
        if (r.statusCategory == null && r.trackingEvents.isNullOrEmpty()) return null  // foreign JSON
        val tz = TimeZone.currentSystemDefault()
        val events = r.trackingEvents.orEmpty().mapNotNull { e ->
            val type = e.eventType ?: return@mapNotNull null
            val ts = parseTimestamp(e.eventTimestamp, tz) ?: return@mapNotNull null
            ts to ScrapedEvent(
                timestamp = ts.toString(),
                description = type,
                location = locationOf(e),
                status = classify(type).takeIf { it != "UNKNOWN" },
            )
        }.sortedBy { it.first }.map { it.second }  // USPS is newest-first; domain expects ascending
        val overall = classify(r.statusCategory ?: r.statusSummary ?: "").takeIf { it != "UNKNOWN" }
            ?: events.lastOrNull()?.status ?: "UNKNOWN"
        return ScrapedTracking(
            status = overall,
            etaDate = parseDate(r.expectedDeliveryDate)?.toString(),
            etaTime = parseTime(r.expectedDeliveryTime)?.toString(),
            location = r.trackingEvents.orEmpty().firstOrNull()?.let(::locationOf),
            events = events,
        )
    }

    private fun locationOf(e: UspsEvent): String? =
        listOfNotNull(e.eventCity?.trim()?.takeIf { it.isNotEmpty() }, e.eventState?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(", ").takeIf { it.isNotEmpty() }

    /** ISO instant ("...Z") or zoneless ISO local datetime, device zone. */
    private fun parseTimestamp(raw: String?, tz: TimeZone): Instant? {
        val s = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        runCatching { Instant.parse(s) }.getOrNull()?.let { return it }
        return runCatching { LocalDateTime.parse(s).toInstant(tz) }.getOrNull()
    }

    /** "2026-07-16", "07/16/2026", or "Wednesday, July 16, 2026". */
    private fun parseDate(raw: String?): LocalDate? {
        val s = raw?.trim() ?: return null
        runCatching { LocalDate.parse(s) }.getOrNull()?.let { return it }
        Regex("""(\d{2})/(\d{2})/(\d{4})""").find(s)?.let { m ->
            val (mm, dd, yyyy) = m.destructured
            return runCatching { LocalDate(yyyy.toInt(), mm.toInt(), dd.toInt()) }.getOrNull()
        }
        val m = Regex("""([A-Za-z]+)\s+(\d{1,2}),\s*(\d{4})""").find(s) ?: return null
        val (monthName, dd, yyyy) = m.destructured
        val month = Month.entries.firstOrNull { it.name.equals(monthName, ignoreCase = true) } ?: return null
        return runCatching { LocalDate(yyyy.toInt(), month, dd.toInt()) }.getOrNull()
    }

    /** "20:00:00" (24h) or "8:00pm". */
    private fun parseTime(raw: String?): LocalTime? {
        val s = raw?.trim() ?: return null
        Regex("""^(\d{1,2}):(\d{2})\s*([ap])\.?m\.?$""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            val (h, min, ap) = m.destructured
            val hour24 = (h.toInt() % 12) + if (ap.lowercase() == "p") 12 else 0
            return runCatching { LocalTime(hour24, min.toInt()) }.getOrNull()
        }
        val m = Regex("""^(\d{1,2}):(\d{2})""").find(s) ?: return null
        val (h, min) = m.destructured
        return runCatching { LocalTime(h.toInt(), min.toInt()) }.getOrNull()
    }

    /** Keyword classification shared conceptually with the DOM extractor (spec §2). */
    private fun classify(text: String): String {
        val t = text.lowercase()
        return when {
            "out for delivery" in t -> "OUT_FOR_DELIVERY"
            "delivered" in t -> "DELIVERED"
            "alert" in t || "attempted" in t || "notice left" in t || "return" in t || "held" in t -> "EXCEPTION"
            "label created" in t || "pre-shipment" in t || "awaiting item" in t -> "LABEL_CREATED"
            "accepted" in t || "picked up" in t || "possession" in t -> "SHIPPED"
            "in transit" in t || "departed" in t || "arrived" in t || "moving through" in t || "processed" in t -> "IN_TRANSIT"
            else -> "UNKNOWN"
        }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :source:usps:jvmTest`
Expected: PASS (all `UspsApiParserTest` tests green).

- [ ] **Step 6: Commit**

```bash
git add source/usps/build.gradle.kts source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsApiParser.kt source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsApiParserTest.kt
git commit -m "$(cat <<'EOF'
[source] Add tolerant USPS in-page API parser

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: UspsWebSpec (provider recipe + DOM extractor)

**Files:**
- Create: `source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsWebSpecTest.kt`
- Create: `source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsWebSpec.kt`

**Interfaces:**
- Consumes: `WebProviderSpec` constructor from `:source:webview`; `UspsApiParser.parse(body)` from Task 1; `WellKnownCarriers.USPS` from domain.
- Produces: `val UspsWebSpec: WebProviderSpec` — used by Task 3's `UspsWebSource`.

- [ ] **Step 1: Write the failing spec test**

Create `source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsWebSpecTest.kt`:

```kotlin
package com.shiphappens.source.usps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UspsWebSpecTest {

    @Test fun tracking_url_uses_tools_usps_path() {
        assertEquals(
            "https://tools.usps.com/tracking/9434636106092288655003",
            UspsWebSpec.trackingUrl("9434636106092288655003"),
        )
    }

    @Test fun api_pattern_matches_candidate_tracking_endpoints_only() {
        val re = Regex(UspsWebSpec.apiUrlPatterns.single())
        // Broad by design (design spec §Decisions): both the legacy endpoint and any
        // plausible new one must match; non-tracking usps.com XHRs must not.
        assertTrue(re.matches("https://tools.usps.com/go/TrackConfirmAction?tLabels=9434636106092288655003"))
        assertTrue(re.matches("https://tools.usps.com/api/tracking/v1/9434636106092288655003"))
        assertFalse(re.matches("https://tools.usps.com/go/POLocatorAction"))
        assertFalse(re.matches("https://webapis.ups.com/track/api/Track/GetStatus"))
    }

    @Test fun spec_identity_and_origins() {
        assertEquals("usps", UspsWebSpec.sourceId)
        assertEquals("usps.com", UspsWebSpec.cookieDomain)
        assertEquals(listOf("https://*.usps.com", "https://usps.com"), UspsWebSpec.allowedOriginRules())
        assertTrue(UspsWebSpec.challengeMarkers.isNotEmpty())
    }

    @Test fun parse_api_delegates_to_usps_parser() {
        val t = UspsWebSpec.parseApi(null, """{"statusCategory":"Delivered"}""")
        assertEquals("DELIVERED", t?.status)
        assertEquals(null, UspsWebSpec.parseApi(null, """{"unrelated":true}"""))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :source:usps:jvmTest`
Expected: FAIL — compilation error, `Unresolved reference: UspsWebSpec`.

- [ ] **Step 3: Implement the spec**

Create `source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsWebSpec.kt`:

```kotlin
package com.shiphappens.source.usps

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.webview.WebProviderSpec

// DOM extractor — the RELIABLE layer for USPS (inverse of UPS, where API capture is primary):
// the tools.usps.com page renders the full event history in the DOM. Selector constants are
// validated against the live page during device QA (Akamai blocks off-device inspection);
// the returned JSON structure is what PayloadRouter/canonical-model tests lock down.
private val USPS_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  if (/status not available|could not locate the tracking information|not found/i.test(text)) return {page: 'notFound'};
  if (/access denied|reference #\d|verify you are a human|unusual activity/i.test(text)) return {page: 'challenge'};
  var statusEl = document.querySelector('.tb-status, .delivery_status h2, [class*="current-status"], [class*="tracking-status"]');
  if (!statusEl) return {page: 'empty'};
  function classify(raw) {
    var t = (raw || '').toLowerCase();
    if (t.indexOf('out for delivery') >= 0) return 'OUT_FOR_DELIVERY';
    if (t.indexOf('delivered') >= 0) return 'DELIVERED';
    if (t.indexOf('alert') >= 0 || t.indexOf('attempted') >= 0 || t.indexOf('notice left') >= 0 || t.indexOf('return') >= 0 || t.indexOf('held') >= 0) return 'EXCEPTION';
    if (t.indexOf('label created') >= 0 || t.indexOf('pre-shipment') >= 0 || t.indexOf('awaiting item') >= 0) return 'LABEL_CREATED';
    if (t.indexOf('accepted') >= 0 || t.indexOf('picked up') >= 0 || t.indexOf('possession') >= 0) return 'SHIPPED';
    if (t.indexOf('in transit') >= 0 || t.indexOf('departed') >= 0 || t.indexOf('arrived') >= 0 || t.indexOf('moving through') >= 0 || t.indexOf('processed') >= 0) return 'IN_TRANSIT';
    return 'UNKNOWN';
  }
  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  var events = [];
  var steps = document.querySelectorAll('#trackingHistory .tb-step, .tracking-progress-bar-status-container .tb-step');
  for (var i = 0; i < steps.length; i++) {
    var dateText = clean(steps[i].querySelector('.tb-date'));
    var desc = clean(steps[i].querySelector('.tb-status-detail, .tb-status'));
    if (!dateText || !desc) continue;
    var t = Date.parse(dateText);
    if (isNaN(t)) continue;
    var st = classify(desc);
    events.push({
      timestamp: new Date(t).toISOString(),
      description: desc,
      location: clean(steps[i].querySelector('.tb-location')),
      status: st === 'UNKNOWN' ? null : st
    });
  }
  events.reverse();  // page lists newest first; canonical order is ascending
  var etaDate = null;
  var etaText = clean(document.querySelector('.expected_delivery .date, [class*="expected-delivery"], .eta_info'));
  if (etaText) {
    var d = Date.parse(etaText);
    if (!isNaN(d)) {
      var dd = new Date(d);
      etaDate = dd.getFullYear() + '-' + ('0' + (dd.getMonth() + 1)).slice(-2) + '-' + ('0' + dd.getDate()).slice(-2);
    }
  }
  return {page: 'ok', tracking: {
    status: classify(statusEl.textContent),
    etaDate: etaDate,
    location: events.length ? events[events.length - 1].location : null,
    events: events
  }};
}
""".trimIndent()

// Greeting/sign-out markers on usps.com chrome — validated in live QA like the selectors above.
private val USPS_IS_LOGGED_IN_JS = """
(function() {
  try {
    if (document.querySelector('a[href*="logout"], a[href*="LogOutAction"], [class*="sign-out"]')) return true;
    return /sign out|welcome,/i.test((document.body && document.body.innerText) || '');
  } catch (e) { return false; }
})()
""".trimIndent()

val UspsWebSpec = WebProviderSpec(
    sourceId = "usps",
    carrier = WellKnownCarriers.USPS,
    cookieDomain = "usps.com",
    trackingUrl = { "https://tools.usps.com/tracking/$it" },
    loginUrl = "https://reg.usps.com/entreg/LoginAction_input",
    isLoggedInJs = USPS_IS_LOGGED_IN_JS,
    // Deliberately broad (Akamai blocked off-device endpoint capture); non-tracking captures
    // are rejected by UspsApiParser returning null. Tightened during live QA.
    apiUrlPatterns = listOf(""".*tools\.usps\.com/.*[Tt]rack.*"""),
    challengeMarkers = listOf("Access Denied", "Reference #", "verify you are a human", "unusual activity"),
    extractionJs = USPS_EXTRACTION_JS,
    parseApi = { _, body -> UspsApiParser.parse(body) },
)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :source:usps:jvmTest`
Expected: PASS (`UspsWebSpecTest` + `UspsApiParserTest` green).

- [ ] **Step 5: Commit**

```bash
git add source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsWebSpec.kt source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsWebSpecTest.kt
git commit -m "$(cat <<'EOF'
[source] Add USPS web provider spec with DOM-first extraction

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: UspsWebSource replaces the stub

**Files:**
- Modify: `source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsSource.kt` (full rewrite)
- Create: `source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsSourceTest.kt`

**Interfaces:**
- Consumes: `UspsWebSpec` (Task 2), `WebViewBasedSource(webSpec, scraper)` and `WebScraper`/`NoWebScraper` from `:source:webview`.
- Produces: `class UspsWebSource(scraper: WebScraper)` and `val uspsSourceModule: Module`. The Koin module name `uspsSourceModule` is already imported by `ui/.../di/AppModules.kt` — keep it identical so no UI change is needed.

- [ ] **Step 1: Write the failing source test**

Create `source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsSourceTest.kt`:

```kotlin
package com.shiphappens.source.usps

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.api.*
import com.shiphappens.source.webview.NoWebScraper
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class UspsSourceTest {
    private val src = UspsWebSource(NoWebScraper)

    @Test fun descriptor_is_configless_web_carrier() {
        assertEquals("usps", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertTrue(src.descriptor.configSpec.isEmpty())   // stub's consumerKey/secret fields are gone
        assertFalse(src.descriptor.implemented)           // NoWebScraper => not implemented
    }
    @Test fun detects_domestic_and_international_numbers() {
        assertEquals(WellKnownCarriers.USPS, src.detectCarrier("9434 6361 0609 2288 6550 03"))
        assertEquals(WellKnownCarriers.USPS, src.detectCarrier("EC123456789US"))
        assertNull(src.detectCarrier("1Z999AA10123456784"))
        assertNull(src.detectCarrier("941234"))
    }
    @Test fun track_unavailable_without_scraper_and_test_connection_succeeds() = runTest {
        assertIs<SourceResult.Failure>(src.track("9434636106092288655003", null))
        assertIs<SourceResult.Success<Unit>>(src.testConnection(SourceConfig(enabled = true)))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :source:usps:jvmTest`
Expected: FAIL — compilation error, `Unresolved reference: UspsWebSource`.

- [ ] **Step 3: Rewrite the stub as the web source**

Replace the full contents of `source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsSource.kt` with:

```kotlin
package com.shiphappens.source.usps

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.domain.normalizeTracking
import com.shiphappens.source.api.TrackingSource
import com.shiphappens.source.webview.WebScraper
import com.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** USPS via tools.usps.com in a WebView — see docs/superpowers/specs/2026-07-14-usps-webview-source-design.md. */
class UspsWebSource(scraper: WebScraper) : WebViewBasedSource(UspsWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? {
        val normalized = normalizeTracking(trackingNumber)
        return WellKnownCarriers.USPS.takeIf {
            Regex("^(94|93|92|95|82)\\d{14,24}$").matches(normalized) ||
                Regex("^[A-Z]{2}\\d{9}US$").matches(normalized)
        }
    }
}

val uspsSourceModule: Module = module { single { UspsWebSource(get()) } bind TrackingSource::class }
```

- [ ] **Step 4: Run the module tests to verify they pass**

Run: `./gradlew :source:usps:jvmTest`
Expected: PASS (all three test classes green).

- [ ] **Step 5: Verify the rest of the app still builds and passes**

Run: `./gradlew :source:usps:compileKotlinIosSimulatorArm64 :ui:testAndroidHostTest :data:jvmTest`
Expected: PASS. `:ui:testAndroidHostTest` covers the DI wiring (`uspsSourceModule` import unchanged) and the manual-add carrier options; `:data:jvmTest` covers `SourceRegistry`/`BuiltInCarrierDetection` (untouched). iOS compile proves the source degrades to `implemented = false` there via `NoWebScraper` binding.

- [ ] **Step 6: Commit**

```bash
git add source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsSource.kt source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsSourceTest.kt
git commit -m "$(cat <<'EOF'
[source] Replace USPS stub with webview-based source

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Live-QA checklist

**Files:**
- Create: `docs/superpowers/qa/2026-07-14-webview-usps-qa.md`

**Interfaces:**
- Consumes: nothing from code; documents the ScrapeTracer workflow from `docs/superpowers/qa/2026-07-12-webview-ups-qa.md`.
- Produces: the checklist device QA runs against; its "record the fixture" step feeds back into `UspsApiParserTest`/`UspsWebSpec`.

- [ ] **Step 1: Write the checklist**

Create `docs/superpowers/qa/2026-07-14-webview-usps-qa.md`:

```markdown
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
```

- [ ] **Step 2: Verify checklist references real symbols**

Run: `grep -n "UspsWebSpec\|UspsApiParser\|USPS_EXTRACTION_JS" source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/*.kt`
Expected: each name appears in the implementation files (no dangling references in the doc).

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/qa/2026-07-14-webview-usps-qa.md
git commit -m "$(cat <<'EOF'
[docs] Add USPS webview source manual QA checklist

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

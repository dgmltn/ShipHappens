# Amazon Logistics (AMZL) Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Live tracking for Amazon Logistics TBA numbers via track.amazon.com, per `docs/superpowers/specs/2026-08-11-amzl-source-design.md`.

**Architecture:** Fourth provider on the WebView scraping framework: a `WebProviderSpec` whose `apiUrlPatterns` capture the SPA's anonymous `/api/tracker/{TBA}` XHR, a tolerant Kotlin parser (`AmzlApiParser`) mapping that JSON to the canonical `ScrapedTracking`, and a thin `WebViewBasedSource` subclass. New carrier identity `amzl` ("Amazon Logistics").

**Tech Stack:** Kotlin Multiplatform (android/jvm/ios targets), kotlinx-serialization, kotlinx-datetime, Koin. No new dependencies.

## Global Constraints

- Work happens on the existing branch `feat/amzl-source` (spec already committed there). WIP commits are checkpoints; the branch is squash-merged at the end (that final message is written fresh).
- Commit subjects start with a bracketed tag, `[amzl]` for this work. **No `Co-Authored-By` trailer, no "Generated with Claude Code" footer** — Doug's git preferences override the harness default.
- Never push; never commit to `main` directly.
- Tests live in `src/commonTest/`, run on the JVM target: `./gradlew :source:amzl:jvmTest` (or `:data:jvmTest`).
- Carrier code is `amzl`, display name "Amazon Logistics", accent `#37475A`.
- `cookieDomain` is `track.amazon.com` — NOT `amazon.com` (an AMZL sign-out must never clear the Amazon orders session; see spec Decisions).
- Time values follow Doug's Kotlin preferences: `kotlin.time.Instant` at boundaries, no unit-suffixed primitives.
- QA sample tracking number: `TBA333593378975` (live as of 2026-08-11; expect delivery ~2026-08-13, after which its data changes).

---

### Task 1: Carrier identity + built-in TBA detection

**Files:**
- Modify: `domain/src/commonMain/kotlin/com/dgmltn/shiphappens/domain/Carrier.kt`
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/source/BuiltInCarrierDetection.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/source/BuiltInCarrierDetectionTest.kt`

**Interfaces:**
- Consumes: existing `Carrier`, `WellKnownCarriers`, `normalizeTracking` (uppercases, strips whitespace/hyphens).
- Produces: `WellKnownCarriers.AMAZON_LOGISTICS: Carrier` (code `"amzl"`, display `"Amazon Logistics"`, accent `"#37475A"`), listed in `WellKnownCarriers.all`; `BuiltInCarrierDetection.detect` returns it for `TBA` + 9–15 digits.

- [ ] **Step 1: Write the failing tests**

Append inside `BuiltInCarrierDetectionTest`:

```kotlin
    @Test fun detects_amazon_logistics_tba() {
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, BuiltInCarrierDetection.detect("TBA333593378975"))
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, BuiltInCarrierDetection.detect("tba 3335 9337 8975"))
        // Boundaries: 9–15 digits after the TBA prefix.
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, BuiltInCarrierDetection.detect("TBA123456789"))
        assertNull(BuiltInCarrierDetection.detect("TBA12345678"))          // 8 digits: too short
        assertNull(BuiltInCarrierDetection.detect("TBA1234567890123456"))  // 16 digits: too long
        // Order ids must still detect as the Amazon orders carrier, not AMZL.
        assertEquals(WellKnownCarriers.AMAZON, BuiltInCarrierDetection.detect("113-1234567-1234567"))
    }
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :data:jvmTest --tests "*BuiltInCarrierDetectionTest*"`
Expected: compile error — `AMAZON_LOGISTICS` unresolved.

- [ ] **Step 3: Implement**

In `Carrier.kt`, extend `WellKnownCarriers` (keep the existing comment style):

```kotlin
object WellKnownCarriers {
    val UPS = Carrier("ups", "UPS", "#5A3A22")
    val USPS = Carrier("usps", "USPS", "#1E3A8F")
    val FEDEX = Carrier("fedex", "FedEx", "#5A1B9A")
    // Amazon orange (#FF9900) darkened to sit with the muted brand accents above.
    val AMAZON = Carrier("amazon", "Amazon", "#146EB4")
    // Amazon's squid-ink navy; distinct from the orders source so carrier→webSpec lookups stay 1:1.
    val AMAZON_LOGISTICS = Carrier("amzl", "Amazon Logistics", "#37475A")
    val all = listOf(UPS, USPS, FEDEX, AMAZON, AMAZON_LOGISTICS)
    fun byCode(code: String): Carrier? = all.firstOrNull { it.code == code.trim().lowercase() }
}
```

In `BuiltInCarrierDetection.kt`, add the regex and branch (letters keep it from colliding with the digit-only FedEx/Amazon patterns):

```kotlin
    // Amazon Logistics: TBA + 9-15 digits (e.g. TBA333593378975).
    private val AMZL = Regex("^TBA\\d{9,15}$")
```

and in `detect`'s `when`:

```kotlin
            AMZL.matches(norm) -> WellKnownCarriers.AMAZON_LOGISTICS
```

(placement inside the `when` doesn't matter for correctness — no other pattern admits letters after position 2 — but put it after the `AMAZON` line to match the file's ordering.)

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :data:jvmTest --tests "*BuiltInCarrierDetectionTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add domain/src/commonMain/kotlin/com/dgmltn/shiphappens/domain/Carrier.kt \
        data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/source/BuiltInCarrierDetection.kt \
        data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/source/BuiltInCarrierDetectionTest.kt
git commit -m "[amzl] Add Amazon Logistics carrier and TBA detection"
```

---

### Task 2: `:source:amzl` module scaffold + `AmzlApiParser`

**Files:**
- Modify: `settings.gradle.kts` (add `include(":source:amzl")` after `include(":source:amazon")`)
- Create: `source/amzl/build.gradle.kts`
- Create: `source/amzl/src/commonMain/kotlin/com/dgmltn/shiphappens/source/amzl/AmzlApiParser.kt`
- Test: `source/amzl/src/commonTest/kotlin/com/dgmltn/shiphappens/source/amzl/AmzlApiParserTest.kt`

**Interfaces:**
- Consumes: `ScrapedTracking`/`ScrapedEvent` from `:source:webview` (status strings are `TrackingStatus` enum names; timestamps ISO-8601 instants; `etaDate` ISO local date).
- Produces: `AmzlApiParser.parse(body: String): ScrapedTracking?` — null for undecodable bodies, `TRACKING_ID_NOT_FOUND` errors, or bodies with neither summary nor events.

- [ ] **Step 1: Create the module**

`source/amzl/build.gradle.kts` — identical shape to `:source:usps`:

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.dgmltn.shiphappens.source.amzl"
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

In `settings.gradle.kts`, after `include(":source:amazon")`:

```kotlin
include(":source:amzl")
```

- [ ] **Step 2: Write the failing tests**

`AmzlApiParserTest.kt`. The two fixtures are live captures from `https://track.amazon.com/api/tracker/…` on 2026-08-11 (TBA333593378975 and a bogus TBA000000000001), trimmed to the fields the parser reads but keeping the envelope's double-encoding — `progressTracker`/`eventHistory` are JSON strings, so the `\"` escapes below are part of the fixture, not Kotlin escaping (raw strings pass them through verbatim).

```kotlin
package com.dgmltn.shiphappens.source.amzl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Live capture 2026-08-11, TBA333593378975 (pre-first-scan package: label created, ETA Aug 13).
private const val FIXTURE = """
{"progressTracker": "{\"summary\": {\"status\": \"CreationConfirmed\", \"metadata\": {\"promisedDeliveryDate\": {\"date\": \"Aug 13, 2026, 3:00:00 AM\", \"type\": \"DATE\"}, \"expectedDeliveryDate\": {\"date\": \"Aug 13, 2026, 3:00:00 AM\", \"type\": \"DATE\"}, \"trackingStatus\": {\"stringValue\": \"READY_FOR_RECEIVE\"}, \"lastLegCarrier\": {\"stringValue\": \"Amazon\", \"type\": \"STRING\"}}, \"containerStatusTags\": [\"READY_FOR_RECEIVE\"]}, \"expectedDeliveryDate\": \"Aug 13, 2026, 3:00:00 AM\", \"legType\": \"FORWARD\"}", "eventHistory": "{\"eventHistory\": [{\"eventCode\": \"CreationConfirmed\", \"statusSummary\": {\"localisedStringId\": \"swa_rex_detail_creation_confirmed\"}, \"eventTime\": \"Aug 10, 2026, 10:33:20 PM\", \"location\": {}, \"shipmentType\": \"FORWARD\", \"eventMetadata\": {}}], \"summary\": {\"status\": \"READY_FOR_RECEIVE\"}, \"trackerSource\": \"SWA\"}"}
"""

// Live capture 2026-08-11, unknown TBA: HTTP 200 with an in-band error, eventHistory null.
private const val NOT_FOUND_FIXTURE = """
{"progressTracker": "{\"errors\": [{\"errorCode\": \"TRACKING_ID_NOT_FOUND\", \"errorMessage\": \"INVALID TRACKING_ID\"}], \"summary\": {\"status\": null, \"metadata\": {}, \"proofOfDelivery\": null, \"containerStatusTags\": null, \"valueAddedServices\": null, \"trackingDetailCodes\": null, \"signedStatus\": null}}", "eventHistory": null}
"""

class AmzlApiParserTest {

    /** Builds a minimal double-encoded envelope with the given inner summary fields. */
    private fun envelope(summaryStatus: String?, trackingStatus: String? = null): String {
        val status = summaryStatus?.let { "\\\"$it\\\"" } ?: "null"
        val meta = trackingStatus?.let { """{\"trackingStatus\":{\"stringValue\":\"$it\"}}""" } ?: "{}"
        return """{"progressTracker": "{\"summary\": {\"status\": $status, \"metadata\": $meta}}"}"""
    }

    @Test fun parses_status_eta_and_events_from_live_fixture() {
        val t = assertNotNull(AmzlApiParser.parse(FIXTURE))
        assertEquals("LABEL_CREATED", t.status)
        assertEquals("2026-08-13", t.etaDate)     // date only — the 3:00 AM is not a delivery window
        assertNull(t.etaWindowStart)
        assertNull(t.etaWindowEnd)
        assertEquals(1, t.events.size)
        assertEquals("Label created", t.events.single().description)
        assertEquals("LABEL_CREATED", t.events.single().status)
        // Timestamps are ISO instants (parseable by the canonical layer).
        assertTrue(t.events.all { runCatching { kotlin.time.Instant.parse(it.timestamp) }.isSuccess })
    }

    @Test fun not_found_error_body_returns_null() {
        assertNull(AmzlApiParser.parse(NOT_FOUND_FIXTURE))
    }

    @Test fun summary_status_vocabulary_maps_to_canonical() {
        assertEquals("LABEL_CREATED", AmzlApiParser.parse(envelope("CreationConfirmed"))!!.status)
        assertEquals("SHIPPED", AmzlApiParser.parse(envelope("PickupDone"))!!.status)
        assertEquals("IN_TRANSIT", AmzlApiParser.parse(envelope("InTransit"))!!.status)
        assertEquals("IN_TRANSIT", AmzlApiParser.parse(envelope("ArrivedAtDeliveryCenter"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", AmzlApiParser.parse(envelope("OutForDelivery"))!!.status)
        assertEquals("DELIVERED", AmzlApiParser.parse(envelope("Delivered"))!!.status)
        assertEquals("EXCEPTION", AmzlApiParser.parse(envelope("DeliveryAttempted"))!!.status)
        assertEquals("EXCEPTION", AmzlApiParser.parse(envelope("Undeliverable"))!!.status)
        assertEquals("EXCEPTION", AmzlApiParser.parse(envelope("ReturnedToSeller"))!!.status)
        assertEquals("UNKNOWN", AmzlApiParser.parse(envelope("SomeNewWording"))!!.status)
    }

    @Test fun tracking_status_is_the_fallback_when_summary_status_is_missing() {
        assertEquals("LABEL_CREATED", AmzlApiParser.parse(envelope(null, "READY_FOR_RECEIVE"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", AmzlApiParser.parse(envelope(null, "OUT_FOR_DELIVERY"))!!.status)
        assertEquals("DELIVERED", AmzlApiParser.parse(envelope(null, "DELIVERED"))!!.status)
    }

    @Test fun parses_dates_with_narrow_spaces() {
        // Newer JDK/ICU date formatting inserts U+202F before AM/PM; Amazon may follow.
        val nnbsp = '\u202F'
        val body = """{"progressTracker": "{\"summary\": {\"status\": \"InTransit\", \"metadata\": {\"promisedDeliveryDate\": {\"date\": \"Aug 13, 2026, 3:00:00${nnbsp}AM\"}}}}"}"""
        assertEquals("2026-08-13", AmzlApiParser.parse(body)!!.etaDate)
    }

    @Test fun unknown_event_codes_fall_back_to_split_camel_case() {
        val body = """{"progressTracker": "{\"summary\": {\"status\": \"InTransit\", \"metadata\": {}}}", "eventHistory": "{\"eventHistory\": [{\"eventCode\": \"ArrivedAtDeliveryStation\", \"eventTime\": \"Aug 11, 2026, 4:05:00 AM\", \"location\": {}}]}"}"""
        assertEquals("Arrived at delivery station", AmzlApiParser.parse(body)!!.events.single().description)
    }

    @Test fun rejects_non_tracking_json() {
        assertNull(AmzlApiParser.parse("not json"))
        assertNull(AmzlApiParser.parse("""{"unrelated": true}"""))
        // Envelope decodes but the inner progressTracker string is garbage.
        assertNull(AmzlApiParser.parse("""{"progressTracker": "not json either"}"""))
    }

    @Test fun tolerates_missing_fields() {
        val t = assertNotNull(AmzlApiParser.parse(envelope("Delivered")))
        assertEquals("DELIVERED", t.status)
        assertNull(t.etaDate)
        assertTrue(t.events.isEmpty())
    }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `./gradlew :source:amzl:jvmTest`
Expected: compile error — `AmzlApiParser` unresolved.

- [ ] **Step 4: Implement `AmzlApiParser.kt`**

```kotlin
package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// track.amazon.com's /api/tracker/{id} envelope double-encodes its two interesting fields:
// progressTracker and eventHistory arrive as JSON *strings* inside the outer JSON object.
@Serializable private data class AmzlEnvelope(
    val progressTracker: String? = null,
    val eventHistory: String? = null,
)

@Serializable private data class AmzlProgressTracker(
    val summary: AmzlSummary? = null,
    val errors: List<AmzlError> = emptyList(),
)

@Serializable private data class AmzlError(val errorCode: String? = null)

@Serializable private data class AmzlSummary(
    val status: String? = null,
    val metadata: AmzlMetadata? = null,
)

@Serializable private data class AmzlMetadata(
    val promisedDeliveryDate: AmzlDateValue? = null,
    val expectedDeliveryDate: AmzlDateValue? = null,
    val trackingStatus: AmzlStringValue? = null,
)

@Serializable private data class AmzlDateValue(val date: String? = null)
@Serializable private data class AmzlStringValue(val stringValue: String? = null)

@Serializable private data class AmzlEventHistoryDoc(
    val eventHistory: List<AmzlEvent> = emptyList(),
    val summary: AmzlHistorySummary? = null,
)

@Serializable private data class AmzlHistorySummary(val status: String? = null)

@Serializable private data class AmzlEvent(
    val eventCode: String? = null,
    val eventTime: String? = null,
    val location: AmzlLocation? = null,
)

// Field names are provisional — the recon package's location was {}; live-QA captures refine them.
@Serializable private data class AmzlLocation(
    val city: String? = null,
    val stateProvince: String? = null,
    val countryCode: String? = null,
)

/**
 * Maps track.amazon.com's in-page tracker API JSON to the canonical [ScrapedTracking].
 * Tolerant like [the UPS parser]: every field optional, unknown vocabulary degrades to UNKNOWN,
 * decode failure returns null. The API reports local wall-clock times with no zone; we interpret
 * them in the device zone — the documented UPS trade-off (only ordering and dates surface in UI).
 *
 * Only CreationConfirmed/READY_FOR_RECEIVE were observed live (2026-08-11); the rest of the
 * vocabulary derives from the SPA's milestone string ids (swa_rex_intransit, swa_rex_ofd, …)
 * and is confirmed during device QA across a package's lifecycle.
 */
object AmzlApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): ScrapedTracking? {
        val envelope = runCatching { json.decodeFromString<AmzlEnvelope>(body) }.getOrNull() ?: return null
        val tracker = envelope.progressTracker
            ?.let { runCatching { json.decodeFromString<AmzlProgressTracker>(it) }.getOrNull() }
        // TRACKING_ID_NOT_FOUND arrives as an in-band error on HTTP 200; parseApi can only say
        // tracking-or-null, so null it is — the DOM extractor owns the NotFound outcome.
        if (tracker == null || tracker.errors.isNotEmpty()) return null
        val history = envelope.eventHistory
            ?.let { runCatching { json.decodeFromString<AmzlEventHistoryDoc>(it) }.getOrNull() }
        val summary = tracker.summary
        if (summary == null && history == null) return null

        val tz = TimeZone.currentSystemDefault()
        val events = history?.eventHistory.orEmpty().mapNotNull { e ->
            val code = e.eventCode ?: return@mapNotNull null
            val (date, time) = parseDateTime(e.eventTime) ?: return@mapNotNull null
            ScrapedEvent(
                timestamp = LocalDateTime(date, time).toInstant(tz).toString(),
                description = describe(code),
                location = e.location?.render(),
                status = classify(code).takeIf { it != UNKNOWN },
            )
        }.sortedBy { it.timestamp }

        val eta = (summary?.metadata?.promisedDeliveryDate?.date ?: summary?.metadata?.expectedDeliveryDate?.date)
            ?.let { parseDateTime(it)?.first }

        val status = sequenceOf(
            summary?.status,
            summary?.metadata?.trackingStatus?.stringValue,
            history?.summary?.status,
        ).mapNotNull { s -> classify(s).takeIf { it != UNKNOWN } }.firstOrNull() ?: UNKNOWN

        return ScrapedTracking(
            status = status,
            etaDate = eta?.toString(),
            location = events.lastOrNull()?.location,
            events = events,
        )
    }

    private const val UNKNOWN = "UNKNOWN"

    /**
     * One classifier for both vocabularies — summary.status CamelCase ("CreationConfirmed") and
     * metadata/eventHistory SCREAMING_SNAKE ("READY_FOR_RECEIVE") — by lowercasing and stripping
     * underscores. Exception keywords are checked before "delivered" so "Undeliverable" and
     * "DeliveryAttempted" can't leak into DELIVERED.
     */
    private fun classify(raw: String?): String {
        val t = (raw ?: "").lowercase().replace("_", "")
        return when {
            t.isEmpty() -> UNKNOWN
            t == "creationconfirmed" || t == "readyforreceive" || "labelcreated" in t -> "LABEL_CREATED"
            t == "pickupdone" || t == "pickedup" || t == "shipped" || t == "packagereceived" -> "SHIPPED"
            "outfordelivery" in t || t == "ofd" -> "OUT_FOR_DELIVERY"
            "attempt" in t || "undeliverable" in t || "lost" in t || "damaged" in t ||
                "return" in t || "reject" in t || "delay" in t -> "EXCEPTION"
            "delivered" in t -> "DELIVERED"
            "intransit" in t || "arrived" in t || "departed" in t -> "IN_TRANSIT"
            else -> UNKNOWN
        }
    }

    // The API carries localisation ids (swa_rex_*), not English text — the SPA translates
    // client-side. Known event codes map to short English; the fallback splits CamelCase.
    private val EVENT_DESCRIPTIONS = mapOf(
        "CreationConfirmed" to "Label created",
        "PickupDone" to "Package picked up",
        "OutForDelivery" to "Out for delivery",
        "Delivered" to "Delivered",
    )

    private fun describe(code: String): String =
        EVENT_DESCRIPTIONS[code]
            ?: code.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").lowercase()
                .replaceFirstChar { it.uppercase() }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // "Aug 13, 2026, 3:00:00 AM" (time optional). NBSP/U+202F are normalized to plain spaces
    // first — newer date formatters insert a narrow no-break space before AM/PM.
    private val DATE_TIME = Regex(
        """([A-Za-z]{3,9}) (\d{1,2}), (\d{4})(?:, (\d{1,2}):(\d{2})(?::(\d{2}))? ?([AP])\.?M\.?)?""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseDateTime(raw: String?): Pair<LocalDate, LocalTime>? {
        val cleaned = (raw ?: "").replace('\u00A0', ' ').replace('\u202F', ' ')
        val m = DATE_TIME.find(cleaned) ?: return null
        val month = MONTHS.indexOf(m.groupValues[1].take(3).lowercase()) + 1
        if (month == 0) return null
        val date = runCatching { LocalDate(m.groupValues[3].toInt(), month, m.groupValues[2].toInt()) }
            .getOrNull() ?: return null
        val time = if (m.groupValues[4].isEmpty()) LocalTime(0, 0) else runCatching {
            val hour = m.groupValues[4].toInt() % 12 + if (m.groupValues[7].equals("P", ignoreCase = true)) 12 else 0
            LocalTime(hour, m.groupValues[5].toInt(), m.groupValues[6].toIntOrNull() ?: 0)
        }.getOrNull() ?: LocalTime(0, 0)
        return date to time
    }

    private fun AmzlLocation.render(): String? =
        listOfNotNull(city?.takeIf { it.isNotBlank() }, stateProvince?.takeIf { it.isNotBlank() })
            .joinToString(", ").ifEmpty { null }
}
```

- [ ] **Step 5: Run to verify pass**

Run: `./gradlew :source:amzl:jvmTest`
Expected: all `AmzlApiParserTest` tests PASS.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts source/amzl
git commit -m "[amzl] Scaffold :source:amzl with tolerant tracker-API parser"
```

---

### Task 3: `AmzlWebSpec` + DOM-raw classifier

**Files:**
- Create: `source/amzl/src/commonMain/kotlin/com/dgmltn/shiphappens/source/amzl/AmzlWebSpec.kt`
- Create: `source/amzl/src/commonMain/kotlin/com/dgmltn/shiphappens/source/amzl/AmzlPageLogic.kt`
- Test: `source/amzl/src/commonTest/kotlin/com/dgmltn/shiphappens/source/amzl/AmzlWebSpecTest.kt`

**Interfaces:**
- Consumes: `WebProviderSpec`, `DomRaw`/`DomExtraction` (from `:source:webview`), `WellKnownCarriers.AMAZON_LOGISTICS` (Task 1), `AmzlApiParser.parse` (Task 2), `normalizeTracking` (`:domain`).
- Produces: `val AmzlWebSpec: WebProviderSpec` (sourceId `"amzl"`); `internal fun parseAmzlRaw(raw: DomRaw): DomExtraction?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.source.webview.DomRaw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmzlWebSpecTest {

    @Test fun tracking_url_normalizes_and_uses_amazons_link_shape() {
        assertEquals(
            "https://track.amazon.com/tracking/TBA333593378975?trackingId=TBA333593378975",
            AmzlWebSpec.trackingUrl("tba 3335-9337 8975"),
        )
    }

    @Test fun origin_rules_are_confined_to_the_tracking_subdomain() {
        // cookieDomain must be track.amazon.com: bridge injection and goto hops stay off
        // www.amazon.com, and clearForDomain can never touch the Amazon orders session.
        assertEquals("track.amazon.com", AmzlWebSpec.cookieDomain)
        assertEquals(
            listOf("https://*.track.amazon.com", "https://track.amazon.com"),
            AmzlWebSpec.allowedOriginRules(),
        )
    }

    @Test fun api_pattern_matches_the_tracker_endpoint() {
        val pattern = Regex(AmzlWebSpec.apiUrlPatterns.single())
        assertTrue(pattern.matches("https://track.amazon.com/api/tracker/TBA333593378975"))
        assertTrue(!pattern.matches("https://www.amazon.com/gp/your-account/order-details"))
    }

    @Test fun parse_api_is_wired_to_the_parser() {
        val body = """{"progressTracker": "{\"summary\": {\"status\": \"Delivered\", \"metadata\": {}}}"}"""
        assertEquals("DELIVERED", AmzlWebSpec.parseApi(null, body)?.status)
    }

    @Test fun raw_status_headlines_classify_in_kotlin() {
        fun statusOf(text: String) =
            parseAmzlRaw(DomRaw(kind = "tracker", statusText = text))?.tracking?.status
        assertEquals("OUT_FOR_DELIVERY", statusOf("Out for delivery"))
        assertEquals("DELIVERED", statusOf("Delivered today"))
        assertEquals("IN_TRANSIT", statusOf("Arriving Wednesday"))
        assertEquals("IN_TRANSIT", statusOf("Package is in transit"))
        assertEquals("LABEL_CREATED", statusOf("We have your package details"))
        assertEquals("EXCEPTION", statusOf("Delivery attempted"))
        assertEquals("UNKNOWN", statusOf("Some brand-new wording"))
    }

    @Test fun raw_without_status_text_routes_to_unparsed() {
        assertNull(parseAmzlRaw(DomRaw(kind = "tracker")))
        assertNull(parseAmzlRaw(DomRaw(kind = "cards")))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :source:amzl:jvmTest`
Expected: compile error — `AmzlWebSpec` / `parseAmzlRaw` unresolved.

- [ ] **Step 3: Implement**

`AmzlPageLogic.kt` — status vocabulary in testable Kotlin, per the framework's `parseRaw` design (a JS-blob decision can only be verified by device QA; see the 2026-07-19 Amazon lesson):

```kotlin
package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.source.webview.DomExtraction
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.ScrapedTracking

/**
 * Classifies the tracking page's visible status headline ("Arriving Wednesday", "Out for
 * delivery") — the coarse DOM fallback for when the API capture misses. English page copy,
 * so this vocabulary is separate from [AmzlApiParser]'s API codes.
 */
internal fun parseAmzlRaw(raw: DomRaw): DomExtraction? {
    if (raw.kind != "tracker") return null
    val t = raw.statusText?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val status = when {
        "out for delivery" in t -> "OUT_FOR_DELIVERY"
        "attempt" in t || "undeliverable" in t || "problem" in t || "delayed" in t -> "EXCEPTION"
        "delivered" in t -> "DELIVERED"
        "arriving" in t || "in transit" in t || "on the way" in t || "on its way" in t || "shipped" in t -> "IN_TRANSIT"
        "label" in t || "package details" in t || "preparing" in t -> "LABEL_CREATED"
        else -> "UNKNOWN"
    }
    return DomExtraction(page = "ok", tracking = ScrapedTracking(status = status))
}
```

`AmzlWebSpec.kt`:

```kotlin
package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.webview.WebProviderSpec

// The tracking page is a JS SPA (AmazonShippingRecipientApp); its /api/tracker/ XHR is the
// primary data layer (captured via apiUrlPatterns, parsed in AmzlApiParser). This DOM extractor
// is only the fallback: classify not-found, read the status headline, or report diagnostics.
// Selector constants and not-found wording are validated on-device during QA — off-device recon
// (2026-08-11) only saw the SPA shell, so every bail-out carries why/probe/detail for the tracer.
private val AMZL_EXTRACTION_JS = """
function() {
  var text = (document.body && document.body.innerText) || '';
  var href = location.href;
  function clean(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; }
  if (/couldn.t find|can.t find|unable to find|invalid tracking|no longer available/i.test(text)) return {page: 'notFound'};
  var statusEl = document.querySelector('#primaryStatus')
    || document.querySelector('[class*="pt-status"], [class*="trackingStatus"], [class*="status-main"]')
    || document.querySelector('main h1, h1');
  var statusText = clean(statusEl);
  if (statusText) return {page: 'raw', raw: {kind: 'tracker', statusText: statusText}};
  function probe() {
    var sel = ['#primaryStatus', '[class*="status"]', 'main h1', 'h1', '[data-testid]'];
    var out = {};
    for (var p = 0; p < sel.length; p++) {
      try { out[sel[p]] = document.querySelectorAll(sel[p]).length; } catch (e) { out[sel[p]] = -1; }
    }
    return out;
  }
  return {page: 'empty', why: 'noStatusHeadline', url: href,
          textHead: text.replace(/\s+/g, ' ').slice(0, 300), probe: probe()};
}
""".trimIndent()

// The tracker API is anonymous (accessType ANONYMOUS_PACKAGE_ACCESS); login is never required,
// so login state is a constant false and the loginUrl below is vestigial framework plumbing.
private const val AMZL_IS_LOGGED_IN_JS = "(function() { return false; })()"

val AmzlWebSpec = WebProviderSpec(
    sourceId = "amzl",
    carrier = WellKnownCarriers.AMAZON_LOGISTICS,
    // track.amazon.com, NOT amazon.com: Android's CookieManager is app-global (an existing
    // amazon.com session reaches this subdomain regardless), and scoping the spec here keeps
    // an AMZL sign-out's clearForDomain from expiring the Amazon orders login.
    cookieDomain = "track.amazon.com",
    trackingUrl = { raw ->
        val tba = normalizeTracking(raw)
        "https://track.amazon.com/tracking/$tba?trackingId=$tba"
    },
    loginUrl = "https://www.amazon.com/gp/sign-in.html",
    isLoggedInJs = AMZL_IS_LOGGED_IN_JS,
    apiUrlPatterns = listOf(""".*track\.amazon\.com/api/tracker/.*"""),
    challengeMarkers = listOf(
        "Enter the characters you see",
        "Type the characters you see",
        "not a robot",
        "automated access to Amazon data",
    ),
    extractionJs = AMZL_EXTRACTION_JS,
    parseApi = { _, body -> AmzlApiParser.parse(body) },
    parseRaw = ::parseAmzlRaw,
)
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :source:amzl:jvmTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add source/amzl
git commit -m "[amzl] Add track.amazon.com web spec with DOM fallback classifier"
```

---

### Task 4: `AmzlWebSource` + Koin module

**Files:**
- Create: `source/amzl/src/commonMain/kotlin/com/dgmltn/shiphappens/source/amzl/AmzlSource.kt`
- Test: `source/amzl/src/commonTest/kotlin/com/dgmltn/shiphappens/source/amzl/AmzlSourceTest.kt`

**Interfaces:**
- Consumes: `WebViewBasedSource`, `WebScraper`, `NoWebScraper` (test double), `AmzlWebSpec` (Task 3).
- Produces: `class AmzlWebSource(scraper: WebScraper)`; `val amzlSourceModule: Module` binding it as `TrackingSource` — the symbol Task 5 imports in `AppModules.kt`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.api.SourceResult
import com.dgmltn.shiphappens.source.webview.NoWebScraper
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class AmzlSourceTest {
    private val src = AmzlWebSource(NoWebScraper)

    @Test fun descriptor_id_and_implemented_flag() {
        assertEquals("amzl", src.descriptor.id)
        assertEquals("Amazon Logistics", src.descriptor.displayName)
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }

    @Test fun detects_tba_numbers_only() {
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, src.detectCarrier("TBA333593378975"))
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, src.detectCarrier("tba 3335-9337-8975"))
        assertNull(src.detectCarrier("113-1234567-1234567"))  // Amazon order id
        assertNull(src.detectCarrier("1Z999AA10123456784"))   // UPS
        assertNull(src.detectCarrier("9434636106092288655003"))  // USPS
        assertNull(src.detectCarrier("TBA12345678"))          // too short (8 digits)
    }

    @Test fun track_is_not_implemented_without_a_scraper() = runTest {
        assertIs<SourceResult.Failure>(src.track("TBA333593378975", null))
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :source:amzl:jvmTest`
Expected: compile error — `AmzlWebSource` unresolved.

- [ ] **Step 3: Implement `AmzlSource.kt`**

```kotlin
package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.api.TrackingSource
import com.dgmltn.shiphappens.source.webview.WebScraper
import com.dgmltn.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Amazon Logistics via track.amazon.com — see docs/superpowers/specs/2026-08-11-amzl-source-design.md. */
class AmzlWebSource(scraper: WebScraper) : WebViewBasedSource(AmzlWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? =
        WellKnownCarriers.AMAZON_LOGISTICS.takeIf {
            Regex("^TBA\\d{9,15}$").matches(normalizeTracking(trackingNumber))
        }
}

val amzlSourceModule: Module = module { single { AmzlWebSource(get()) } bind TrackingSource::class }
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :source:amzl:jvmTest`
Expected: PASS (all Task 2–4 tests).

- [ ] **Step 5: Commit**

```bash
git add source/amzl
git commit -m "[amzl] Add AmzlWebSource and Koin module"
```

---

### Task 5: App wiring + full verification

**Files:**
- Modify: `ui/build.gradle.kts` (source module dependencies, ~line 31)
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/di/AppModules.kt`

**Interfaces:**
- Consumes: `amzlSourceModule` (Task 4).
- Produces: the source registered in the app's Koin graph; Settings row ("Amazon Logistics", disabled by default) and carrier→webSpec lookup work with no further UI changes.

- [ ] **Step 1: Wire the module**

`ui/build.gradle.kts`, in `commonMain.dependencies` next to the other sources:

```kotlin
            implementation(projects.source.amzl)
```

`AppModules.kt` — add the import and registry entry:

```kotlin
import com.dgmltn.shiphappens.source.amzl.amzlSourceModule
```

and in `appModules()`, after `amazonSourceModule,`:

```kotlin
    amzlSourceModule,
```

- [ ] **Step 2: Run the full test suite**

Run: `./gradlew :domain:jvmTest :data:jvmTest :source:amzl:jvmTest :source:webview:jvmTest :ui:testAndroidHostTest`
Expected: PASS everywhere. (`:ui:testAndroidHostTest` compiles the DI graph — a missing binding surfaces here.)

- [ ] **Step 3: Build the app**

Run: `./gradlew :app-android:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add ui/build.gradle.kts ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/di/AppModules.kt
git commit -m "[amzl] Register the Amazon Logistics source in the app graph"
```

---

### Task 6: Device QA on the emulator

**Files:**
- Create: `docs/superpowers/qa/2026-08-11-amzl-qa.md` (results as observed — see checklist)
- Possibly modify: `AmzlWebSpec.kt` selectors / `AmzlApiParser.kt` DTOs, driven by ScrapeTracer captures

**Interfaces:**
- Consumes: the built app (Task 5), the project's `verify` skill (builds, launches, and drives the app on an emulator with screenshots), ScrapeTracer (debug builds; see `source/webview` debug package).
- Produces: a QA doc recording live behavior; any selector/vocabulary fixes committed.

- [ ] **Step 1: Launch and enable** — use the `verify` skill: install a debug build, open Settings, enable "Amazon Logistics". Confirm the row shows the new carrier name (no sign-in needed for tracking).

- [ ] **Step 2: Live package** — add `TBA333593378975`. Expected: status/ETA render (as of 2026-08-11: label-created step, ETA Aug 13 — but the package is live; record what the API returns *on QA day*, and validate the parser's status mapping against it). Screenshot list + detail screens.

- [ ] **Step 3: More details page** — from the parcel detail screen, tap "More details on Amazon Logistics". Expected: track.amazon.com renders in-app.

- [ ] **Step 4: Not-found path** — add `TBA000000000001`, refresh. Expected: "Amazon Logistics doesn't recognize this number" *if* the DOM classifier catches the SPA's wording; otherwise record the actual failure message and the page's real not-found copy from ScrapeTracer, fix the `notFound` regex in `AMZL_EXTRACTION_JS`, and re-verify.

- [ ] **Step 5: Tracer review** — with `WebScrapeDebug` enabled, capture one refresh; record in the QA doc: the exact API URL captured (tighten `apiUrlPatterns` if the real URL differs), whether the DOM fallback found a status headline (fix selectors if `noStatusHeadline`), and the not-found copy. If the API carried populated `location`/`predictiveDeliveryWindowDetails` shapes, note them verbatim for the follow-up.

- [ ] **Step 6: Write the QA doc and commit**

```bash
git add docs/superpowers/qa/2026-08-11-amzl-qa.md source/amzl
git commit -m "[amzl] Device QA: validate live scrape, record findings"
```

---

### Task 7: Finish the branch

- [ ] **Step 1:** Use the superpowers:finishing-a-development-branch skill. Per Doug's git preferences: squash-merge `feat/amzl-source` into `main` locally, write the squash message fresh:

```
[amzl] Add Amazon Logistics (track.amazon.com) source for TBA tracking numbers
```

- [ ] **Step 2:** Delete the branch (`git branch -D feat/amzl-source`). **Do not push — ask first.**

---

## Self-Review

- **Spec coverage:** carrier identity (Task 1), detection (Tasks 1, 4), module + parser incl. double-decode/not-found/vocabulary/narrow-space dates/event descriptions (Task 2), spec + cookieDomain + extraction/parseRaw (Task 3), source + DI (Tasks 4–5), tests (each task), device QA + ScrapeTracer + QA doc (Task 6). ETA window fields deliberately stay null (spec: v1) — no task sets them, matching the spec.
- **Placeholders:** none — all code is written out; QA steps record observed values by design.
- **Type consistency:** `AmzlApiParser.parse(String): ScrapedTracking?` matches `parseApi`'s `(String?, String) -> ScrapedTracking?` via the Task 3 lambda; `parseAmzlRaw(DomRaw): DomExtraction?` matches `parseRaw`; `amzlSourceModule` name consistent across Tasks 4–5.

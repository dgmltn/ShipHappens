# Source Consolidation Increment 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the six carriers' copied tracker-page ladder, status chains, and date helpers with shared, typed code in `:source:webview`, so a parsing fix in one carrier lands on all of them.

**Architecture:** Providers now return domain types (`TrackingSnapshot`, `PageOutcome`) instead of the string-typed `ScrapedTracking`. A `StatusVocabulary` value, an `assembleSnapshot` fallback ladder, and a `resolveTrackerPage` resolver driven by `TrackerPageRules` live in `:source:webview`; each carrier module keeps only its own vocabulary extras, page rules, selectors, API DTOs, and any hook its page genuinely needs. Carriers migrate one at a time behind two transitional adapters that the last task deletes.

**Tech Stack:** Kotlin Multiplatform 2.4, kotlinx-serialization, kotlinx-datetime with `kotlin.time.Instant`, kotlin-test in `commonTest`, Gradle `jvmTest` tasks.

**Spec:** `docs/superpowers/specs/2026-09-07-source-consolidation-design.md` (Increment 1 and the Boundary rule).

## Global Constraints

- **Boundary rule (spec):** `:source:webview` holds mechanisms and generic, carrier-neutral English. Every selector, URL, carrier-copy regex, API field name, and choreography hook stays in the carrier's module. Carrier tokens ("creation confirmed", "ready for receive") are carrier extras, never shared seeds.
- **Behavior preserved:** every existing captured-page fixture and API fixture in the carrier tests keeps its expected result. Assertions change from strings to enums and from `DomExtraction` to `PageOutcome`; fixture strings are never edited or trimmed.
- **No Gradle changes** in this increment. Module layout is untouched.
- **Time values:** `Duration` and `kotlin.time.Instant`, never unit-suffixed primitives.
- **Kotlin/Native miscompile guard:** do not add `filterIsInstance`, `any { it is T }`, or `firstNotNullOfOrNull { it as? T }` over a `List<RouteResult>` in `WebViewBasedSource.track`; keep the plain-loop helpers at the bottom of that file.
- **Commits:** subject starts with a bracketed tag (`[webview]`, `[ups]`, `[usps]`, `[fedex]`, `[dhlecs]`, `[amzl]`, `[amazon]`, `[docs]`). No `Co-Authored-By` trailer, no "Generated with Claude Code" footer.
- **Branch:** all work on `refactor/source-consolidation-1`, created in Task 1. Squash-merge locally at the end; never push without asking.

**Test commands:**

```bash
# One module (fast; use after every step)
./gradlew :source:webview:jvmTest --console=plain
# Full suite (end of each task that touches a carrier, and Task 13)
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest :source:ups:jvmTest \
          :source:usps:jvmTest :source:fedex:jvmTest :source:amazon:jvmTest \
          :source:amzl:jvmTest :source:dhlecs:jvmTest :source:webview:jvmTest \
          :ui:testAndroidHostTest --console=plain
# iOS compile + the segfault-guard test (Task 13 only; slow)
./gradlew :source:webview:iosSimulatorArm64Test --console=plain
```

## File map

Created in `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/`:

| File | Responsibility |
|---|---|
| `PageOutcome.kt` | Kotlin-side result of reading a page; `snapshotOrNull()` helper |
| `PageEvents.kt` | `eventAt`, `zoneForAbbreviation`, `DomRaw.today()`, `DomRawEvent.toTrackingEvent` |
| `SnapshotAssembler.kt` | `assembleSnapshot`: the status / location / window fallback ladder |
| `TrackerPageResolver.kt` | `TrackerPageRules`, `resolveTrackerPage`, shared not-found seeds, default delay-note rule |
| `SnapshotBackfill.kt` | `TrackingSnapshot.backfilledFrom` (moved from `ScrapedTracking.kt`) |

Modified there: `StatusVocabulary.kt` (class replaces free functions), `PageDates.kt` (new parsers, NBSP normalization, seconds in 12-hour times), `PayloadRouter.kt` (`PageOutcome` routing, `DomExtraction.tracking` removed at the end), `WebProviderSpec.kt` (typed `parseApi`/`parseRaw`), `WebViewBasedSource.kt` (no `toSnapshot`), and `androidMain/.../HeadlessWebViewScraper.kt` (one field rename).

Deleted at the end: `ScrapedTracking.kt`, `ScrapedTrackingTest.kt`, every carrier `PageLogic.kt` except FedEx's and Amazon's.

Per carrier: vocabulary and page rules move into `XWebSpec.kt` as `internal val`s; `XApiParser.kt` returns `TrackingSnapshot` via `assembleSnapshot`; tests keep their fixtures.

---

### Task 1: Branch, and the date/time helpers every later task uses

**Files:**
- Modify: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/PageDates.kt`
- Test: `source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/PageDatesTest.kt`

**Interfaces:**
- Produces: `parseCompactDate(text: String?): LocalDate?`, `parseAnyDate(text: String?): LocalDate?`, `parsePromiseDate(text: String?, today: LocalDate?): LocalDate?`; `parseTimeOfDay` now accepts seconds ("10:33:20 PM") and NBSP/U+202F before AM/PM; `parseMonthNameDate` tolerates NBSP.

- [ ] **Step 1: Create the branch**

```bash
git switch -c refactor/source-consolidation-1 main
```

- [ ] **Step 2: Write the failing tests**

Append to `PageDatesTest.kt` inside the class:

```kotlin
    @Test fun compact_date_parses_only_the_eight_digit_form() {
        assertEquals(LocalDate(2026, 7, 14), parseCompactDate("20260714"))
        assertEquals(LocalDate(2026, 7, 14), parseCompactDate(" 20260714 "))
        assertNull(parseCompactDate("2026-07-14"))
        assertNull(parseCompactDate("20261314"))
        assertNull(parseCompactDate(null))
    }

    @Test fun any_date_accepts_iso_numeric_and_month_name_forms() {
        assertEquals(LocalDate(2026, 7, 16), parseAnyDate("2026-07-16"))
        assertEquals(LocalDate(2026, 7, 16), parseAnyDate("2026-07-16T00:00:00"))
        assertEquals(LocalDate(2026, 7, 16), parseAnyDate("07/16/2026"))
        assertEquals(LocalDate(2026, 7, 16), parseAnyDate("Wednesday, July 16, 2026"))
        assertNull(parseAnyDate("pending"))
        assertNull(parseAnyDate(null))
    }

    @Test fun promise_date_tries_explicit_forms_before_relative_and_weekday() {
        val today = LocalDate(2026, 8, 18)
        assertEquals(LocalDate(2026, 8, 20), parsePromiseDate("Thursday8/20/2026 Between 10:10 AM - 2:10 PM", today))
        assertEquals(LocalDate(2026, 8, 22), parsePromiseDate("Saturday, August 22, 2026", today))
        assertEquals(LocalDate(2026, 8, 19), parsePromiseDate("Arriving tomorrow by 8 AM", today))
        assertEquals(LocalDate(2026, 8, 22), parsePromiseDate("Saturday, August 22", today))
        assertEquals(LocalDate(2026, 8, 20), parsePromiseDate("Thursday Between 10:10 AM - 2:10 PM", today))
        assertNull(parsePromiseDate("Estimated delivery Pending", today))
        // Without the page date, only the yearful forms can resolve.
        assertNull(parsePromiseDate("Arriving tomorrow", null))
        assertNull(parsePromiseDate("Thursday Between 10:10 AM - 2:10 PM", null))
        assertEquals(LocalDate(2026, 8, 20), parsePromiseDate("8/20/2026", null))
    }

    @Test fun time_of_day_accepts_seconds_and_narrow_spaces() {
        assertEquals(LocalTime(22, 33), parseTimeOfDay("Aug 10, 2026, 10:33:20 PM"))
        assertEquals(LocalTime(3, 0), parseTimeOfDay("Aug 13, 2026, 3:00:00 AM"))
        assertEquals(LocalTime(3, 0), parseTimeOfDay("3:00 AM"))
        assertEquals(LocalTime(14, 30), parseTimeOfDay("14:30:00"))
    }

    @Test fun month_name_date_tolerates_narrow_spaces() {
        assertEquals(LocalDate(2026, 8, 13), parseMonthNameDate("Aug 13, 2026"))
    }
```

Add `import kotlinx.datetime.LocalTime` if the file lacks it.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :source:webview:jvmTest --console=plain --tests '*PageDatesTest*'`
Expected: compilation FAILS with unresolved `parseCompactDate`, `parseAnyDate`, `parsePromiseDate`.

- [ ] **Step 4: Implement**

In `PageDates.kt`:

Add a normalizer and apply it at the top of every public parser that reads free text:

```kotlin
/** Newer date formatters insert U+202F (narrow no-break space) before AM/PM and NBSP inside
 *  dates; every parser here reads plain spaces. */
private fun String.plainSpaces(): String = replace('\u00A0', ' ').replace('\u202F', ' ')
```

Change `TIME_12H` to accept optional seconds:

```kotlin
private val TIME_12H = Regex("""(\d{1,2}):(\d{2})(?::\d{2})?\s*([ap])\.?m\.?""", RegexOption.IGNORE_CASE)
```

In `parseTimeOfDay`, replace `val s = text ?: return null` with `val s = text?.plainSpaces() ?: return null`.
In `parseMonthNameDate`, `parseDayWithoutYear`, `parseNumericMdyDate`, and `parseRelativeDay`, apply `.plainSpaces()` to the text before matching (for the two that early-return on `isNullOrBlank()`, call it after that check).

Add the three new parsers:

```kotlin
private val COMPACT_YMD = Regex("""(\d{4})(\d{2})(\d{2})""")

/** "20260714" — an eight-digit year-month-day with no separators (UPS's `sdd` field). */
fun parseCompactDate(text: String?): LocalDate? {
    val m = COMPACT_YMD.matchEntire(text?.trim() ?: return null) ?: return null
    val (y, mo, d) = m.destructured
    return runCatching { LocalDate(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
}

/** An API date field in any of its common shapes: ISO ("2026-07-16", with or without a time
 *  suffix), numeric M/D/YYYY, or a month-name date with a year. */
fun parseAnyDate(text: String?): LocalDate? {
    val s = text?.plainSpaces()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { LocalDate.parse(s.take(10)) }.getOrNull()
        ?: parseNumericMdyDate(s)
        ?: parseMonthNameDate(s)
}

/**
 * The default chain for a page's delivery-promise element: explicit dates first (numeric,
 * month-name with year), then the forms that need [today] (relative words, a year-less
 * month+day, a bare weekday). Safe only on text that IS the promise — [DomRaw.etaText] by
 * contract — because [parseWeekdayName] resolves any weekday forward. A null [today] disables
 * the relative, year-less, and weekday forms rather than guessing.
 */
fun parsePromiseDate(text: String?, today: LocalDate?): LocalDate? =
    parseNumericMdyDate(text)
        ?: parseMonthNameDate(text)
        ?: parseRelativeDay(text, today)
        ?: parseDayWithoutYear(text, today)
        ?: parseWeekdayName(text, today)
```

- [ ] **Step 5: Run the module tests**

Run: `./gradlew :source:webview:jvmTest --console=plain`
Expected: PASS, including the pre-existing `PageDatesTest` and `EtaWindowParserTest` cases.

- [ ] **Step 6: Commit**

```bash
git add source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/PageDates.kt \
        source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/PageDatesTest.kt
git commit -m "[webview] Add compact, any-form, and promise date parsers; accept seconds and narrow spaces in times"
```

---

### Task 2: `StatusVocabulary` as a value, with the negated-delivered guard and token humanizing

**Files:**
- Modify: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/StatusVocabulary.kt`
- Test: `source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/StatusVocabularyTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  enum class BaseKeywords { Carrier, None }
  class StatusVocabulary(extras: StatusKeywords = StatusKeywords(), base: BaseKeywords = BaseKeywords.Carrier) {
      fun classify(text: String?): TrackingStatus?
      fun isDelayed(text: String?): Boolean
      fun isMultiStage(text: String?): Boolean
      fun classifyToken(token: String?): TrackingStatus?
      fun isDelayedToken(token: String?): Boolean
  }
  fun humanizeToken(token: String?): String
  ```
- Keeps (transitional, deleted in Task 13): `classifyStatusWording`, `isMultiStageWording`, `isDelayedWording` as delegates to `StatusVocabulary(extras)`, so unmigrated carriers still compile.

- [ ] **Step 1: Rewrite the test file for the class**

Replace `StatusVocabularyTest.kt` with a version that keeps every existing case but calls the class. Mechanically: `classifyStatusWording(x)` becomes `SHARED.classify(x)`, `classifyStatusWording(x, extras)` becomes `StatusVocabulary(extras).classify(x)`, likewise `isDelayedWording` to `isDelayed` and `isMultiStageWording` to `isMultiStage`. Add at the top of the class:

```kotlin
    private val SHARED = StatusVocabulary()
```

Then add these new cases:

```kotlin
    // -- negated delivery: checked on every vocabulary, ahead of the DELIVERED lane --

    @Test fun undelivered_wording_is_an_exception_not_a_delivery() {
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Undelivered - Processes for Local Disposal"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Package not delivered: address unknown"))
        assertFalse(SHARED.isMultiStage("Undelivered - Processes for Local Disposal"))
    }

    @Test fun negation_guard_applies_without_the_carrier_base_too() {
        val bare = StatusVocabulary(StatusKeywords(delivered = listOf("delivered")), base = BaseKeywords.None)
        assertEquals(TrackingStatus.DELIVERED, bare.classify("Delivered today"))
        assertEquals(TrackingStatus.EXCEPTION, bare.classify("Undelivered"))
    }

    // -- BaseKeywords.None: only the carrier's own phrases --

    @Test fun a_none_base_vocabulary_knows_nothing_it_was_not_told() {
        val strict = StatusVocabulary(StatusKeywords(inTransit = listOf("on the way")), base = BaseKeywords.None)
        assertEquals(TrackingStatus.IN_TRANSIT, strict.classify("On the way"))
        assertNull(strict.classify("Out for delivery"))
        assertNull(strict.classify("We've received your return"))
        assertFalse(strict.isDelayed("Delayed"))
    }

    // -- API tokens --

    @Test fun tokens_are_humanized_before_classification() {
        assertEquals("out for delivery", humanizeToken("OutForDelivery"))
        assertEquals("ready for receive", humanizeToken("READY_FOR_RECEIVE"))
        assertEquals("in transit delayed", humanizeToken("InTransitDelayed"))
        assertEquals("", humanizeToken(null))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, SHARED.classifyToken("OutForDelivery"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classifyToken("DeliveryAttempted"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classifyToken("ReturnedToSeller"))
        assertEquals(TrackingStatus.IN_TRANSIT, SHARED.classifyToken("InTransitDelayed"))
        assertTrue(SHARED.isDelayedToken("InTransitDelayed"))
        assertNull(SHARED.classifyToken("Delayed"))
        assertNull(SHARED.classifyToken("SomeNewWording"))
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :source:webview:jvmTest --console=plain --tests '*StatusVocabularyTest*'`
Expected: compilation FAILS on `StatusVocabulary(`, `BaseKeywords`, `humanizeToken`.

- [ ] **Step 3: Rewrite `StatusVocabulary.kt`**

Keep the file's existing header comment and the `StatusKeywords` data class unchanged. Replace everything below `StatusKeywords` with:

```kotlin
/** Which carrier-neutral phrases a vocabulary starts from. */
enum class BaseKeywords {
    /** Tracker-page English every carrier prints ("out for delivery", "picked up", "in transit"…). */
    Carrier,
    /**
     * Nothing shared: only the carrier's own phrases. For pages that mix shipment copy with
     * unrelated copy — Amazon's order pages carry RMA/returns cards, where the base's bare
     * "return" and "attempt" misclassified a completed replacement as an exception (QA 2026-07-19).
     */
    None,
}

private fun normalizeWording(raw: String?): String =
    raw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

private val CARRIER_BASE = StatusKeywords(
    outForDelivery = listOf("out for delivery"),
    delivered = listOf("delivered"),
    exception = listOf(
        "exception", "attempt", "action required", "notice left", "held",
        "unable to deliver", "return",
    ),
    labelCreated = listOf("label created"),
    shipped = listOf("picked up"),
    inTransit = listOf("in transit", "on the way", "on its way", "departed", "arrived"),
    delayed = listOf("delay"),
)

/**
 * Wordings that contain "delivered" and negate it. Evaluated on every vocabulary, whatever
 * its base: they suppress the DELIVERED lane and classify EXCEPTION. Before this lane existed,
 * DHL eCommerce guarded "Undelivered - Processes for Local Disposal" from outside the chain and
 * AMZL reordered its own copy of the chain for the same reason.
 */
private val NOT_DELIVERED = listOf("undelivered", "not delivered")

private operator fun StatusKeywords.plus(o: StatusKeywords) = StatusKeywords(
    outForDelivery = outForDelivery + o.outForDelivery,
    delivered = delivered + o.delivered,
    exception = exception + o.exception,
    labelCreated = labelCreated + o.labelCreated,
    shipped = shipped + o.shipped,
    inTransit = inTransit + o.inTransit,
    delayed = delayed + o.delayed,
)

/**
 * A carrier's status vocabulary: the shared carrier-neutral phrases (or none) plus the
 * carrier's own, evaluated by one precedence chain. Never compose vocabularies as
 * `local() ?: shared()` — a late-stage local keyword ("processed") would shadow an earlier
 * shared match ("out for delivery") in the same text; merging the keyword lists and running
 * one chain is what keeps precedence right.
 */
class StatusVocabulary(
    extras: StatusKeywords = StatusKeywords(),
    base: BaseKeywords = BaseKeywords.Carrier,
) {
    private val words: StatusKeywords = when (base) {
        BaseKeywords.Carrier -> CARRIER_BASE + extras
        BaseKeywords.None -> extras
    }

    /**
     * Every stage the text names, in precedence order. Chain order is load-bearing: exception
     * wordings ("delivery exception", "delivery attempted") contain delivery-ish substrings, so
     * OUT_FOR_DELIVERY and DELIVERED match on their exact phrases first. There is deliberately
     * no delay lane — a delay is a modifier, not a stage (see [isDelayed]).
     */
    private fun stages(t: String): List<TrackingStatus> {
        fun hit(phrases: List<String>) = phrases.any { it in t }
        val negated = hit(NOT_DELIVERED)
        return buildList {
            if (hit(words.outForDelivery)) add(TrackingStatus.OUT_FOR_DELIVERY)
            if (!negated && hit(words.delivered)) add(TrackingStatus.DELIVERED)
            if (negated || hit(words.exception)) add(TrackingStatus.EXCEPTION)
            if (hit(words.labelCreated)) add(TrackingStatus.LABEL_CREATED)
            if (hit(words.shipped)) add(TrackingStatus.SHIPPED)
            if (hit(words.inTransit)) add(TrackingStatus.IN_TRANSIT)
        }
    }

    /** The stage a wording names, or null when it isn't recognizable — the caller's cue to fall
     *  back (newest classifiable event, coarse page state), never a guess. */
    fun classify(text: String?): TrackingStatus? {
        val t = normalizeWording(text)
        if (t.isEmpty()) return null
        return stages(t).firstOrNull()
    }

    /** Whether a wording reports a delay, asked independently of [classify] because a delay is
     *  orthogonal to the stage: in transit and late, out for delivery and late, an exception and late. */
    fun isDelayed(text: String?): Boolean {
        val t = normalizeWording(text)
        return t.isNotEmpty() && words.delayed.any { it in t }
    }

    /**
     * True when a wording names two or more DIFFERENT stages — the signature of a scraped
     * progress rail, whose step labels are all in the DOM regardless of the package's state
     * (dhlecs live QA 2026-09-03 classified a label-only package DELIVERED off "Notified En Route
     * Delivered"). No genuine single-status sentence names two stages, so a DOM reader should
     * refuse such text instead of letting the chain pick whichever stage matches first.
     */
    fun isMultiStage(text: String?): Boolean {
        val t = normalizeWording(text)
        return t.isNotEmpty() && stages(t).size >= 2
    }

    /** An API code ("OutForDelivery", "READY_FOR_RECEIVE") classified by the same chain after [humanizeToken]. */
    fun classifyToken(token: String?): TrackingStatus? = classify(humanizeToken(token))

    fun isDelayedToken(token: String?): Boolean = isDelayed(humanizeToken(token))
}

/** "OutForDelivery" → "out for delivery"; "READY_FOR_RECEIVE" → "ready for receive". */
fun humanizeToken(token: String?): String =
    (token ?: "")
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .replace('_', ' ')
        .lowercase()
        .trim()

// ---- Transitional delegates. Deleted once every carrier holds a StatusVocabulary (Task 13). ----

fun classifyStatusWording(raw: String?, extras: StatusKeywords = StatusKeywords()): TrackingStatus? =
    StatusVocabulary(extras).classify(raw)

fun isMultiStageWording(raw: String?, extras: StatusKeywords = StatusKeywords()): Boolean =
    StatusVocabulary(extras).isMultiStage(raw)

fun isDelayedWording(raw: String?, extras: StatusKeywords = StatusKeywords()): Boolean =
    StatusVocabulary(extras).isDelayed(raw)
```

- [ ] **Step 4: Run the whole suite**

Run the full suite command from Global Constraints.
Expected: PASS everywhere. The carriers still call the delegates. If a carrier test now fails, it is because a wording containing "undelivered" or "not delivered" used to classify DELIVERED; report it rather than weakening the lane.

- [ ] **Step 5: Commit**

```bash
git add source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/StatusVocabulary.kt \
        source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/StatusVocabularyTest.kt
git commit -m "[webview] StatusVocabulary as a value: negated-delivered lane, None base, token humanizing"
```

---

### Task 3: Event helpers

**Files:**
- Create: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/PageEvents.kt`
- Test: `source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/PageEventsTest.kt`

**Interfaces:**
- Consumes: `StatusVocabulary` (Task 2), `parseNumericMdyDate`, `parseMonthNameDate`, `parseRelativeDay`, `parseDayWithoutYear`, `parseTimeOfDay` (Task 1).
- Produces:
  ```kotlin
  fun eventAt(date: LocalDate, time: LocalTime?, zone: TimeZone): Instant
  fun zoneForAbbreviation(abbreviation: String?): TimeZone?
  fun DomRaw.today(): LocalDate?
  fun DomRawEvent.toTrackingEvent(vocabulary: StatusVocabulary, zone: TimeZone, today: LocalDate? = null): TrackingEvent?
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class PageEventsTest {
    private val utc = TimeZone.UTC
    private val vocabulary = StatusVocabulary()

    @Test fun event_at_pins_a_missing_time_to_midnight() {
        assertEquals(Instant.parse("2026-08-18T14:33:00Z"), eventAt(LocalDate(2026, 8, 18), LocalTime(14, 33), utc))
        assertEquals(Instant.parse("2026-08-18T00:00:00Z"), eventAt(LocalDate(2026, 8, 18), null, utc))
    }

    @Test fun us_zone_abbreviations_resolve_to_iana_zones() {
        assertEquals(TimeZone.of("America/New_York"), zoneForAbbreviation("ET"))
        assertEquals(TimeZone.of("America/Chicago"), zoneForAbbreviation(" cst "))
        assertEquals(TimeZone.of("America/Puerto_Rico"), zoneForAbbreviation("AST"))
        assertEquals(TimeZone.UTC, zoneForAbbreviation("Z"))
        assertNull(zoneForAbbreviation("XYZ"))
        assertNull(zoneForAbbreviation(null))
    }

    @Test fun today_reads_the_page_date_and_ignores_junk() {
        assertEquals(LocalDate(2026, 8, 18), DomRaw(kind = "tracker", todayIso = "2026-08-18").today())
        assertNull(DomRaw(kind = "tracker", todayIso = "yesterday").today())
        assertNull(DomRaw(kind = "tracker").today())
    }

    @Test fun iso_timestamp_wins_and_the_description_is_classified() {
        val e = assertNotNull(DomRawEvent("2026-08-18T14:33:00Z", "Departed FedEx location", "MEMPHIS, TN").toTrackingEvent(vocabulary, utc))
        assertEquals(Instant.parse("2026-08-18T14:33:00Z"), e.timestamp)
        assertEquals("Departed FedEx location", e.description)
        assertEquals("MEMPHIS, TN", e.location)
        assertEquals(TrackingStatus.IN_TRANSIT, e.status)
    }

    @Test fun when_text_builds_the_timestamp_from_date_and_clock() {
        val e = assertNotNull(DomRawEvent(description = "Picked up", whenText = "Tuesday, 8/18/26 4:16 PM").toTrackingEvent(vocabulary, utc))
        assertEquals(Instant.parse("2026-08-18T16:16:00Z"), e.timestamp)
        assertEquals(TrackingStatus.SHIPPED, e.status)
        val midnight = assertNotNull(DomRawEvent(description = "Picked up", whenText = "Tuesday, 8/18/26").toTrackingEvent(vocabulary, utc))
        assertEquals(Instant.parse("2026-08-18T00:00:00Z"), midnight.timestamp)
    }

    @Test fun relative_and_yearless_when_text_resolve_against_today() {
        val today = LocalDate(2026, 8, 18)
        val y = assertNotNull(DomRawEvent(description = "Shipped", whenText = "Yesterday 9:05 AM").toTrackingEvent(vocabulary, utc, today))
        assertEquals(Instant.parse("2026-08-17T09:05:00Z"), y.timestamp)
        val d = assertNotNull(DomRawEvent(description = "Shipped", whenText = "Tuesday, July 15 3:40 PM").toTrackingEvent(vocabulary, utc, today))
        assertEquals(Instant.parse("2026-07-15T15:40:00Z"), d.timestamp)
        assertNull(DomRawEvent(description = "Shipped", whenText = "Yesterday 9:05 AM").toTrackingEvent(vocabulary, utc, today = null))
    }

    @Test fun a_row_with_no_resolvable_date_is_dropped() {
        assertNull(DomRawEvent(description = "Picked up", whenText = "sometime").toTrackingEvent(vocabulary, utc))
        assertNull(DomRawEvent(description = "Picked up").toTrackingEvent(vocabulary, utc))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :source:webview:jvmTest --console=plain --tests '*PageEventsTest*'`
Expected: compilation FAILS on the four unresolved functions.

- [ ] **Step 3: Implement `PageEvents.kt`**

```kotlin
package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** A scan's wall-clock date and time in [zone]; a missing time pins to midnight. */
fun eventAt(date: LocalDate, time: LocalTime?, zone: TimeZone): Instant =
    LocalDateTime(date, time ?: LocalTime(0, 0)).toInstant(zone)

// Carrier APIs stamp events with a US zone abbreviation ("ET"), not an offset; IANA zones keep
// the DST arithmetic right. Arizona summer scans stamped "MST" read an hour off via
// America/Denver — accepted, the same class of skew as a device-zone fallback.
private val ZONES = mapOf(
    "ET" to "America/New_York", "EST" to "America/New_York", "EDT" to "America/New_York",
    "CT" to "America/Chicago", "CST" to "America/Chicago", "CDT" to "America/Chicago",
    "MT" to "America/Denver", "MST" to "America/Denver", "MDT" to "America/Denver",
    "PT" to "America/Los_Angeles", "PST" to "America/Los_Angeles", "PDT" to "America/Los_Angeles",
    "AKT" to "America/Anchorage", "AKST" to "America/Anchorage", "AKDT" to "America/Anchorage",
    "HT" to "Pacific/Honolulu", "HST" to "Pacific/Honolulu",
    "AT" to "America/Puerto_Rico", "AST" to "America/Puerto_Rico",
    "UTC" to "UTC", "GMT" to "UTC", "Z" to "UTC",
)

/** The IANA zone for a US abbreviation, or null for one we don't know (callers fall back to the device zone). */
fun zoneForAbbreviation(abbreviation: String?): TimeZone? =
    ZONES[abbreviation?.trim()?.uppercase()]?.let { TimeZone.of(it) }

/** The device-local date the page was read on, or null when the extractor never reported one. */
fun DomRaw.today(): LocalDate? = todayIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * One event row as a domain event. An ISO [DomRawEvent.timestamp] wins; otherwise the verbatim
 * [DomRawEvent.whenText] is read as a date (numeric, month-name, relative to [today], or a
 * year-less month+day) plus a clock time. A row whose date can't be read is dropped (null) —
 * an event with a guessed time would sort wrongly, which is worse than a missing row.
 */
fun DomRawEvent.toTrackingEvent(vocabulary: StatusVocabulary, zone: TimeZone, today: LocalDate? = null): TrackingEvent? {
    val at = instant(zone, today) ?: return null
    return TrackingEvent(
        timestamp = at,
        description = description,
        location = location,
        status = vocabulary.classify(description),
    )
}

private fun DomRawEvent.instant(zone: TimeZone, today: LocalDate?): Instant? {
    runCatching { Instant.parse(timestamp) }.getOrNull()?.let { return it }
    val w = whenText ?: return null
    val date = parseNumericMdyDate(w)
        ?: parseMonthNameDate(w)
        ?: parseRelativeDay(w, today)
        ?: parseDayWithoutYear(w, today)
        ?: return null
    return eventAt(date, parseTimeOfDay(w), zone)
}
```

- [ ] **Step 4: Run the module tests**

Run: `./gradlew :source:webview:jvmTest --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/PageEvents.kt \
        source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/PageEventsTest.kt
git commit -m "[webview] Shared event helpers: eventAt, zone abbreviations, DomRaw.today, row-to-event"
```

---

### Task 4: `assembleSnapshot`, the shared fallback ladder

**Files:**
- Create: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/SnapshotAssembler.kt`
- Test: `source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/SnapshotAssemblerTest.kt`

**Interfaces:**
- Consumes: `StatusVocabulary`, `EtaWindow`, `parseEtaWindow`.
- Produces:
  ```kotlin
  fun assembleSnapshot(
      vocabulary: StatusVocabulary,
      headline: String?,
      events: List<TrackingEvent> = emptyList(),
      etaDate: LocalDate? = null,
      etaWindow: EtaWindow? = null,
      etaWindowText: String? = null,
      location: String? = null,
      delayNote: String? = null,
      statusFallback: TrackingStatus? = null,
  ): TrackingSnapshot
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SnapshotAssemblerTest {
    private val v = StatusVocabulary()
    private fun at(iso: String) = Instant.parse(iso)
    private val older = TrackingEvent(at("2026-08-17T09:00:00Z"), "Picked up", "SANTA CLARA, CA", TrackingStatus.SHIPPED)
    private val newer = TrackingEvent(at("2026-08-18T14:33:00Z"), "Departed FedEx location", "MEMPHIS, TN", TrackingStatus.IN_TRANSIT)
    private val unclassified = TrackingEvent(at("2026-08-19T08:00:00Z"), "Delivery date pending", null, null)

    @Test fun headline_wins_over_events_and_events_sort_ascending() {
        val s = assembleSnapshot(v, "Out for delivery", events = listOf(newer, older))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, s.status)
        assertEquals(listOf(older, newer), s.events)
    }

    @Test fun unreadable_headline_falls_back_to_the_newest_classifiable_event() {
        val s = assembleSnapshot(v, "Your order", events = listOf(older, newer, unclassified))
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
    }

    @Test fun status_fallback_is_used_only_when_nothing_classifies() {
        assertEquals(TrackingStatus.LABEL_CREATED, assembleSnapshot(v, "x", statusFallback = TrackingStatus.LABEL_CREATED).status)
        assertEquals(TrackingStatus.IN_TRANSIT, assembleSnapshot(v, "x", events = listOf(newer), statusFallback = TrackingStatus.LABEL_CREATED).status)
        assertEquals(TrackingStatus.UNKNOWN, assembleSnapshot(v, "x").status)
        assertEquals(TrackingStatus.UNKNOWN, assembleSnapshot(v, null).status)
    }

    @Test fun location_prefers_the_banner_then_the_newest_located_event() {
        assertEquals("Sacramento, CA", assembleSnapshot(v, "In transit", events = listOf(older, newer), location = "Sacramento, CA").latestLocation)
        assertEquals("MEMPHIS, TN", assembleSnapshot(v, "In transit", events = listOf(newer, older, unclassified)).latestLocation)
        assertNull(assembleSnapshot(v, "In transit").latestLocation)
    }

    @Test fun explicit_window_wins_and_text_is_the_fallback() {
        val explicit = assembleSnapshot(v, "In transit", etaWindow = EtaWindow(null, LocalTime(21, 0)), etaWindowText = "3:00 PM - 5:00 PM")
        assertNull(explicit.etaWindowStart)
        assertEquals(LocalTime(21, 0), explicit.etaWindowEnd)
        val text = assembleSnapshot(v, "In transit", etaWindowText = "Arriving today 3:00 PM - 5:00 PM")
        assertEquals(LocalTime(15, 0), text.etaWindowStart)
        assertEquals(LocalTime(17, 0), text.etaWindowEnd)
        val empty = assembleSnapshot(v, "In transit", etaWindow = EtaWindow(null, null), etaWindowText = "by 10 PM")
        assertEquals(LocalTime(22, 0), empty.etaWindowEnd)  // an all-null window is no window
        assertNull(assembleSnapshot(v, "In transit", etaWindowText = "sometime tomorrow").etaWindowEnd)
    }

    @Test fun eta_date_and_delay_note_pass_through() {
        val s = assembleSnapshot(v, "On the way: Delayed", etaDate = LocalDate(2026, 8, 20), delayNote = "Due to weather, delayed one day")
        assertEquals(LocalDate(2026, 8, 20), s.etaDate)
        assertEquals("Due to weather, delayed one day", s.delayNote)
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :source:webview:jvmTest --console=plain --tests '*SnapshotAssemblerTest*'`
Expected: compilation FAILS on `assembleSnapshot`.

- [ ] **Step 3: Implement `SnapshotAssembler.kt`**

```kotlin
package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate

/**
 * The fallback ladder every carrier used to copy, run once.
 *
 * Status: the [headline] as [vocabulary] reads it, else the newest event that classified, else
 * the carrier's [statusFallback] (UPS's type code), else UNKNOWN. Location: the explicit
 * [location] banner, else the newest event that names a place. Window: explicit bounds in
 * [etaWindow] (ignored when both are null), else [etaWindowText] parsed all-or-nothing.
 * [events] may arrive in any order; the snapshot's are ascending.
 */
fun assembleSnapshot(
    vocabulary: StatusVocabulary,
    headline: String?,
    events: List<TrackingEvent> = emptyList(),
    etaDate: LocalDate? = null,
    etaWindow: EtaWindow? = null,
    etaWindowText: String? = null,
    location: String? = null,
    delayNote: String? = null,
    statusFallback: TrackingStatus? = null,
): TrackingSnapshot {
    val ordered = events.sortedBy { it.timestamp }
    val window = etaWindow?.takeIf { it.start != null || it.end != null } ?: parseEtaWindow(etaWindowText)
    return TrackingSnapshot(
        status = vocabulary.classify(headline)
            ?: ordered.lastOrNull { it.status != null }?.status
            ?: statusFallback
            ?: TrackingStatus.UNKNOWN,
        events = ordered,
        etaDate = etaDate,
        etaWindowStart = window?.start,
        etaWindowEnd = window?.end,
        latestLocation = location ?: ordered.lastOrNull { it.location != null }?.location,
        delayNote = delayNote,
    )
}
```

- [ ] **Step 4: Run the module tests**

Run: `./gradlew :source:webview:jvmTest --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/SnapshotAssembler.kt \
        source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/SnapshotAssemblerTest.kt
git commit -m "[webview] assembleSnapshot: one status/location/window fallback ladder"
```

---

### Task 5: Typed spec outputs, `PageOutcome`, router, and transitional adapters

This is the one task that touches every module at once, mechanically. After it, every carrier still uses its own `ScrapedTracking`-producing code, wrapped by two adapters; Tasks 7 to 12 remove the wrapping carrier by carrier; Task 13 deletes the adapters.

**Files:**
- Create: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/PageOutcome.kt`
- Create: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/SnapshotBackfill.kt`
- Modify: `.../webview/WebProviderSpec.kt`, `.../webview/PayloadRouter.kt`, `.../webview/WebViewBasedSource.kt`, `.../webview/ScrapedTracking.kt`
- Modify: `source/webview/src/androidMain/kotlin/com/dgmltn/shiphappens/source/webview/HeadlessWebViewScraper.kt:61`
- Modify: each carrier's `XWebSpec.kt` (`parseApi`/`parseRaw` lines only)
- Test: `.../webview/PayloadRouterTest.kt`, `.../webview/WebViewBasedSourceTest.kt`, `.../webview/ScrapedTrackingTest.kt`, plus each carrier's `XWebSpecTest.kt` parser-wiring cases.

**Interfaces:**
- Produces:
  ```kotlin
  sealed interface PageOutcome {
      data class Tracking(val snapshot: TrackingSnapshot) : PageOutcome
      data class Goto(val url: String, val coarse: TrackingSnapshot?) : PageOutcome
      data object NotFound : PageOutcome
      data object LoginWall : PageOutcome
      data object Challenge : PageOutcome
      data object Empty : PageOutcome
  }
  fun PageOutcome?.snapshotOrNull(): TrackingSnapshot?
  // WebProviderSpec
  val parseApi: (url: String?, body: String) -> TrackingSnapshot? = { _, _ -> null }
  val parseRaw: (DomRaw) -> PageOutcome? = { null }
  // RouteResult
  data class Tracking(val snapshot: TrackingSnapshot) : RouteResult
  data class Goto(val url: String, val coarse: TrackingSnapshot?) : RouteResult
  internal fun TrackingSnapshot.backfilledFrom(coarse: TrackingSnapshot?): TrackingSnapshot
  // Transitional (deleted in Task 13):
  fun DomExtraction.toOutcome(): PageOutcome?
  ```

- [ ] **Step 1: Create `PageOutcome.kt`**

```kotlin
package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot

/**
 * What a carrier's Kotlin decided about a page. Produced by [WebProviderSpec.parseRaw] from a
 * `page:'raw'` extraction and never serialized — the JS bridge only ever carries [DomRaw].
 * [PayloadRouter] turns it into a [RouteResult], which is where the hop-URL rule is enforced;
 * a provider cannot bypass that by constructing a [Goto].
 */
sealed interface PageOutcome {
    data class Tracking(val snapshot: TrackingSnapshot) : PageOutcome
    /** One-hop navigation, optionally carrying a coarse snapshot read from the page that asked for it. */
    data class Goto(val url: String, val coarse: TrackingSnapshot?) : PageOutcome
    data object NotFound : PageOutcome
    data object LoginWall : PageOutcome
    data object Challenge : PageOutcome
    data object Empty : PageOutcome
}

fun PageOutcome?.snapshotOrNull(): TrackingSnapshot? = (this as? PageOutcome.Tracking)?.snapshot
```

- [ ] **Step 2: Create `SnapshotBackfill.kt`** (moved from `ScrapedTracking.kt`, typed)

```kotlin
package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus

/**
 * Fills the fields a rich hop result is missing from the [coarse] order-page fallback, so an
 * impoverished ship-track page (UNKNOWN status, no ETA — tracker-selector drift, or a shipment
 * the tracker hasn't caught up on) can't shadow an ETA the order page already knew and blank the
 * card to "--". Rich still wins for every field it carries; only nulls (and an UNKNOWN status)
 * are backfilled. No-op when there is no coarse fallback.
 */
internal fun TrackingSnapshot.backfilledFrom(coarse: TrackingSnapshot?): TrackingSnapshot {
    if (coarse == null) return this
    return copy(
        status = if (status == TrackingStatus.UNKNOWN) coarse.status else status,
        etaDate = etaDate ?: coarse.etaDate,
        etaWindowStart = etaWindowStart ?: coarse.etaWindowStart,
        etaWindowEnd = etaWindowEnd ?: coarse.etaWindowEnd,
        delayNote = delayNote ?: coarse.delayNote,
    )
}
```

Delete the old `backfilledFrom` from `ScrapedTracking.kt`, and append the transitional adapter there:

```kotlin
/** Transitional: lets an unmigrated carrier's DomExtraction feed the typed router. Removed in Task 13. */
fun DomExtraction.toOutcome(): PageOutcome? = when (page) {
    "ok" -> tracking?.let { PageOutcome.Tracking(it.toSnapshot()) }
    "goto" -> url?.let { PageOutcome.Goto(it, tracking?.toSnapshot()) }
    "notFound" -> PageOutcome.NotFound
    "loginWall" -> PageOutcome.LoginWall
    "challenge" -> PageOutcome.Challenge
    "empty" -> PageOutcome.Empty
    else -> null
}
```

- [ ] **Step 3: Type the spec**

In `WebProviderSpec.kt` change the two fields (and update the KDoc's `extractionJs` bullet to say `page: 'raw'|'goto'|'notFound'|'loginWall'|'challenge'|'empty'` and drop the `'ok'` mention):

```kotlin
    val parseApi: (url: String?, body: String) -> TrackingSnapshot? = { _, _ -> null },
    ...
    val parseRaw: (DomRaw) -> PageOutcome? = { null },
```

Add `import com.dgmltn.shiphappens.domain.TrackingSnapshot`. Every caller passes these by name, so the added default on `parseApi` changes no call site.

- [ ] **Step 4: Route `PageOutcome`**

In `PayloadRouter.kt`:

`RouteResult` becomes:

```kotlin
sealed interface RouteResult {
    data class Tracking(val snapshot: TrackingSnapshot) : RouteResult
    /** A validated one-hop navigation request, optionally carrying a coarse snapshot from the
     *  page that requested the hop (design spec §1). */
    data class Goto(val url: String, val coarse: TrackingSnapshot?) : RouteResult
    data object NotFound : RouteResult
    data object LoginWall : RouteResult
    data object Challenge : RouteResult
    data object Unparsed : RouteResult
}
```

`route` and `routeDom` become:

```kotlin
    fun route(payloadJson: String): RouteResult {
        val payload = runCatching { json.decodeFromString<BridgePayload>(payloadJson) }.getOrNull()
            ?: return RouteResult.Unparsed
        return when (payload.kind) {
            "api" -> spec.parseApi(payload.url, payload.body)
                ?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
            "dom" -> {
                val dom = runCatching { json.decodeFromString<DomExtraction>(payload.body) }.getOrNull()
                    ?: return RouteResult.Unparsed
                routeDom(dom)
            }
            else -> RouteResult.Unparsed
        }
    }

    private fun routeDom(dom: DomExtraction): RouteResult = when (dom.page) {
        // The extractor may still ask for a bare hop; the coarse snapshot only ever comes from Kotlin.
        "goto" -> dom.url?.takeIf { isAllowedHopUrl(it, spec.cookieDomain) }
            ?.let { RouteResult.Goto(it, null) } ?: RouteResult.Unparsed
        // Provider Kotlin decides the outcome; the hop-URL check applies to its Goto the same way.
        "raw" -> dom.raw?.let { spec.parseRaw(it) }?.let { routeOutcome(it) } ?: RouteResult.Unparsed
        "notFound" -> RouteResult.NotFound
        "loginWall" -> RouteResult.LoginWall
        "challenge" -> RouteResult.Challenge
        else -> RouteResult.Unparsed
    }

    private fun routeOutcome(outcome: PageOutcome): RouteResult = when (outcome) {
        is PageOutcome.Tracking -> RouteResult.Tracking(outcome.snapshot)
        is PageOutcome.Goto ->
            if (isAllowedHopUrl(outcome.url, spec.cookieDomain)) {
                RouteResult.Goto(outcome.url, outcome.coarse)
            } else {
                // Bad hop target: salvage the coarse snapshot if the provider sent one.
                outcome.coarse?.let { RouteResult.Tracking(it) } ?: RouteResult.Unparsed
            }
        PageOutcome.NotFound -> RouteResult.NotFound
        PageOutcome.LoginWall -> RouteResult.LoginWall
        PageOutcome.Challenge -> RouteResult.Challenge
        PageOutcome.Empty -> RouteResult.Unparsed
    }
```

Leave `DomExtraction.tracking` in place for now (Task 13 removes it); nothing reads it after this step except `toOutcome()`.

In `WebViewBasedSource.kt`: `routed.firstRichTracking()?.let { return SourceResult.Success(it.backfilledFrom(coarse).toSnapshot()) }` becomes `...Success(it.backfilledFrom(coarse))`, and `coarse?.let { return SourceResult.Success(it.toSnapshot()) }` becomes `coarse?.let { return SourceResult.Success(it) }`. The two helper loops change their return type to `TrackingSnapshot?` and read `r.snapshot` / `r.coarse`. Keep them as plain `for` loops.

In `HeadlessWebViewScraper.kt` line 61: `(it is RouteResult.Goto && it.tracking != null)` becomes `(it is RouteResult.Goto && it.coarse != null)`.

- [ ] **Step 5: Wrap every carrier spec**

In each `XWebSpec.kt`, change only the two lines:

| Carrier | `parseApi` | `parseRaw` |
|---|---|---|
| UPS | `{ _, body -> UpsApiParser.parse(body)?.toSnapshot() }` | `{ parseUpsRaw(it)?.toOutcome() }` |
| USPS | `{ _, body -> UspsApiParser.parse(body)?.toSnapshot() }` | `{ parseUspsRaw(it)?.toOutcome() }` |
| FedEx | delete the line (default) | `{ parseFedexRaw(it)?.toOutcome() }` |
| DHL | `{ _, body -> DhlEcsApiParser.parse(body)?.toSnapshot() }` | `{ parseDhlEcsRaw(it)?.toOutcome() }` |
| AMZL | `{ _, body -> AmzlApiParser.parse(body)?.toSnapshot() }` | `{ parseAmzlRaw(it)?.toOutcome() }` |
| Amazon | delete the line (default) | `{ parseAmazonRaw(it)?.toOutcome() }` |

Add `import com.dgmltn.shiphappens.source.webview.toOutcome` and (where used) `import com.dgmltn.shiphappens.source.webview.toSnapshot` to each.

- [ ] **Step 6: Rewrite the webview tests**

`PayloadRouterTest.kt`: change `testSpec` to

```kotlin
internal fun testSpec(
    parseApi: (String?, String) -> TrackingSnapshot? = { _, _ -> null },
    parseRaw: (DomRaw) -> PageOutcome? = { null },
) = WebProviderSpec(
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
    parseRaw = parseRaw,
)
```

and rewrite the cases (imports: `TrackingSnapshot`, `TrackingStatus`, `LocalDate`):

```kotlin
class PayloadRouterTest {
    private val json = Json
    private fun domPayload(body: String) = """{"kind":"dom","body":${json.encodeToString(JsonPrimitive(body))}}"""
    private fun rawPayload() = domPayload("""{"page":"raw","raw":{"kind":"tracker","statusText":"x"}}""")
    private fun snapshot(status: TrackingStatus) = TrackingSnapshot(status = status)

    @Test fun api_payload_routes_through_parseApi() {
        val router = PayloadRouter(testSpec(parseApi = { url, body ->
            if (url?.contains("/api/track") == true && body == "{...}") snapshot(TrackingStatus.IN_TRANSIT) else null
        }))
        val result = router.route("""{"kind":"api","url":"https://www.example.com/api/track?x=1","body":"{...}"}""")
        assertEquals(TrackingStatus.IN_TRANSIT, assertIs<RouteResult.Tracking>(result).snapshot.status)
    }

    @Test fun api_payload_parse_failure_is_unparsed() {
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("""{"kind":"api","url":"u","body":"junk"}"""))
    }

    @Test fun raw_payload_routes_through_parseRaw() {
        val router = PayloadRouter(testSpec(parseRaw = { raw ->
            if (raw.kind == "tracker" && raw.statusText == "x") PageOutcome.Tracking(snapshot(TrackingStatus.DELIVERED)) else null
        }))
        assertEquals(TrackingStatus.DELIVERED, assertIs<RouteResult.Tracking>(router.route(rawPayload())).snapshot.status)
    }

    @Test fun raw_outcomes_map_to_route_signals() {
        fun routeOf(outcome: PageOutcome) = PayloadRouter(testSpec(parseRaw = { outcome })).route(rawPayload())
        assertIs<RouteResult.NotFound>(routeOf(PageOutcome.NotFound))
        assertIs<RouteResult.LoginWall>(routeOf(PageOutcome.LoginWall))
        assertIs<RouteResult.Challenge>(routeOf(PageOutcome.Challenge))
        assertIs<RouteResult.Unparsed>(routeOf(PageOutcome.Empty))
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec(parseRaw = { null })).route(rawPayload()))
    }

    @Test fun dom_page_states_route_to_signals() {
        val router = PayloadRouter(testSpec())
        fun dom(page: String) = """{"kind":"dom","body":"{\"page\":\"$page\"}"}"""
        assertIs<RouteResult.NotFound>(router.route(dom("notFound")))
        assertIs<RouteResult.LoginWall>(router.route(dom("loginWall")))
        assertIs<RouteResult.Challenge>(router.route(dom("challenge")))
        assertIs<RouteResult.Unparsed>(router.route(dom("empty")))
        assertIs<RouteResult.Unparsed>(router.route(dom("ok")))   // no JS emits 'ok' any more; it carries nothing
    }

    @Test fun garbage_payload_is_unparsed() {
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("not json at all"))
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("""{"kind":"mystery","body":""}"""))
    }

    @Test fun kotlin_goto_routes_with_its_coarse_snapshot() {
        val outcome = PageOutcome.Goto("https://www.example.com/track/2", snapshot(TrackingStatus.IN_TRANSIT))
        val goto = assertIs<RouteResult.Goto>(PayloadRouter(testSpec(parseRaw = { outcome })).route(rawPayload()))
        assertEquals("https://www.example.com/track/2", goto.url)
        assertEquals(TrackingStatus.IN_TRANSIT, goto.coarse?.status)
    }

    @Test fun js_goto_routes_without_a_snapshot() {
        val goto = assertIs<RouteResult.Goto>(PayloadRouter(testSpec()).route(domPayload("""{"page":"goto","url":"https://example.com/track/2"}""")))
        assertNull(goto.coarse)
    }

    @Test fun goto_with_disallowed_url_degrades_to_the_coarse_snapshot() {
        val badUrls = listOf(
            "http://www.example.com/track/2",        // not https
            "https://evil.com/track/2",              // foreign host
            "https://evilexample.com/track/2",       // suffix trick — not a subdomain
            "https://example.com@evil.com/track/2",  // userinfo smuggling
        )
        for (bad in badUrls) {
            val outcome = PageOutcome.Goto(bad, snapshot(TrackingStatus.SHIPPED))
            val result = PayloadRouter(testSpec(parseRaw = { outcome })).route(rawPayload())
            assertEquals(TrackingStatus.SHIPPED, assertIs<RouteResult.Tracking>(result).snapshot.status, "url: $bad")
        }
    }

    @Test fun goto_with_disallowed_or_missing_url_and_no_snapshot_is_unparsed() {
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec(parseRaw = { PageOutcome.Goto("https://evil.com/x", null) })).route(rawPayload()))
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route(domPayload("""{"page":"goto"}""")))
    }

    @Test fun hop_url_allowlist_semantics() {
        assertTrue(isAllowedHopUrl("https://example.com/a", "example.com"))
        assertTrue(isAllowedHopUrl("https://www.example.com:443/a?b#c", "example.com"))
        assertFalse(isAllowedHopUrl("https://evilexample.com/a", "example.com"))
        assertFalse(isAllowedHopUrl("http://example.com/a", "example.com"))
        assertFalse(isAllowedHopUrl("https://example.com@evil.com/a", "example.com"))
    }
}
```

`WebViewBasedSourceTest.kt`: replace the JSON-embedded `ok`/`goto` tracking payloads with `parseRaw` outcomes. Replace `gotoDom` and the `rich` strings with a spec whose `parseRaw` answers by `statusText`:

```kotlin
private fun raw(statusText: String) =
    domBody("""{"page":"raw","raw":{"kind":"tracker","statusText":"$statusText"}}""")

/** A spec whose Kotlin answers by headline: "goto" hops with a coarse IN_TRANSIT+ETA snapshot, anything else is a rich snapshot of that status name. */
private fun outcomeSpec() = testSpec(parseRaw = { r ->
    when (r.statusText) {
        "goto" -> PageOutcome.Goto("https://www.example.com/t/2", TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 21)))
        "gotoBare" -> PageOutcome.Goto("https://www.example.com/t/2", null)
        "empty" -> PageOutcome.Empty
        else -> TrackingStatus.entries.firstOrNull { it.name == r.statusText }?.let { PageOutcome.Tracking(TrackingSnapshot(it, etaDate = if (it == TrackingStatus.OUT_FOR_DELIVERY) LocalDate(2026, 7, 20) else null)) }
    }
})
```

Then each existing case keeps its name and assertion; only the payload construction changes:

- `tracking_payload_becomes_success`: `parseApi = { _, _ -> TrackingSnapshot(TrackingStatus.IN_TRANSIT, latestLocation = "Louisville, KY") }`.
- `first_tracking_payload_wins`: payloads `listOf(dom("loginWall"), raw("DELIVERED"))` with `TestWebSource(FakeScraper(...), outcomeSpec())`.
- `goto_coarse_tracking_is_the_fallback_result`: `listOf(raw("goto"), raw("empty"))`.
- `rich_tracking_beats_goto_coarse`: `listOf(raw("goto"), raw("DELIVERED"))`.
- `goto_coarse_beats_error_signals`: `listOf(raw("goto"), dom("loginWall"))`.
- `rich_result_backfills_missing_eta_and_status_from_coarse`: `listOf(raw("goto"), raw("UNKNOWN"))`; assert IN_TRANSIT and `LocalDate(2026, 7, 21)`.
- `rich_result_keeps_its_own_eta_and_status_over_coarse`: `listOf(raw("goto"), raw("OUT_FOR_DELIVERY"))`; assert OUT_FOR_DELIVERY and `LocalDate(2026, 7, 20)`.
- `goto_without_tracking_alone_is_unknown_failure`: `listOf(raw("gotoBare"))`.

`ScrapedTrackingTest.kt`: delete the `backfilledFrom`-free cases? None exist there; leave the file untouched (it still compiles against the transitional type; Task 13 deletes it). Add a new `SnapshotBackfillTest.kt`:

```kotlin
package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnapshotBackfillTest {
    private val coarse = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 21), etaWindowEnd = LocalTime(20, 0), delayNote = "Running late")

    @Test fun unknown_status_and_nulls_are_filled_from_coarse() {
        val filled = TrackingSnapshot(TrackingStatus.UNKNOWN).backfilledFrom(coarse)
        assertEquals(TrackingStatus.IN_TRANSIT, filled.status)
        assertEquals(LocalDate(2026, 7, 21), filled.etaDate)
        assertEquals(LocalTime(20, 0), filled.etaWindowEnd)
        assertEquals("Running late", filled.delayNote)
    }

    @Test fun rich_fields_win() {
        val rich = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, etaDate = LocalDate(2026, 7, 20)).backfilledFrom(coarse)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, rich.status)
        assertEquals(LocalDate(2026, 7, 20), rich.etaDate)
    }

    @Test fun no_coarse_is_a_no_op() {
        val rich = TrackingSnapshot(TrackingStatus.UNKNOWN)
        assertEquals(rich, rich.backfilledFrom(null))
        assertNull(rich.etaDate)
    }
}
```

- [ ] **Step 7: Update the carrier spec tests' wiring cases**

Each is a one-line assertion change:

- `UspsWebSpecTest.parse_api_delegates_to_usps_parser`: `assertEquals(TrackingStatus.DELIVERED, t?.status)`.
- `UspsWebSpecTest.parse_raw_delegates_to_usps_page_logic`: `assertEquals(TrackingStatus.IN_TRANSIT, result.snapshotOrNull()?.status)` and drop the `"ok"` line.
- `FedexWebSpecTest.parse_raw_delegates_to_fedex_page_logic`: same shape.
- `DhlEcsWebSpecTest.parse_api_is_wired_to_the_parser`: `assertEquals(TrackingStatus.DELIVERED, DhlEcsWebSpec.parseApi(null, body)?.status)`.
- `AmzlWebSpecTest.parse_api_is_wired_to_the_parser`: same shape.
- `AmazonWebSpecTest.raw_extractions_are_routed_through_kotlin`: `assertIs<PageOutcome.Goto>(AmazonWebSpec.parseRaw(cards))`.

Add the `TrackingStatus` / `PageOutcome` / `snapshotOrNull` imports each needs.

- [ ] **Step 8: Run the full suite**

Run the full suite command.
Expected: PASS. The carrier page-logic and API-parser tests are untouched and still exercise the `ScrapedTracking`-producing functions directly.

- [ ] **Step 9: Commit**

```bash
git add -A source/
git commit -m "[webview] Typed parser outputs: PageOutcome and TrackingSnapshot replace the string round-trip at the spec boundary"
```

---

### Task 6: `TrackerPageRules` and `resolveTrackerPage`

**Files:**
- Create: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/TrackerPageResolver.kt`
- Test: `source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/TrackerPageResolverTest.kt`

**Interfaces:**
- Consumes: `StatusVocabulary`, `assembleSnapshot`, `DomRawEvent.toTrackingEvent`, `DomRaw.today()`, `parsePromiseDate`, `findEtaWindowText`, `PageOutcome`.
- Produces:
  ```kotlin
  class TrackerPageRules(
      val vocabulary: StatusVocabulary,
      val notFound: List<String> = emptyList(),
      val etaDate: (text: String?, today: LocalDate?) -> LocalDate? = ::parsePromiseDate,
      val location: (DomRaw) -> String? = { it.locationText },
      val delayNote: (headline: String?, events: List<TrackingEvent>) -> String? = { h, e -> headlineThenNewestEvent(vocabulary, h, e) },
  )
  fun resolveTrackerPage(raw: DomRaw, rules: TrackerPageRules, zone: TimeZone = TimeZone.currentSystemDefault()): PageOutcome?
  fun headlineThenNewestEvent(vocabulary: StatusVocabulary, headline: String?, events: List<TrackingEvent>): String?
  ```

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class TrackerPageResolverTest {
    private val rules = TrackerPageRules(
        vocabulary = StatusVocabulary(StatusKeywords(inTransit = listOf("moving through"))),
        notFound = listOf("""status not available"""),
    )
    private fun resolve(raw: DomRaw) = resolveTrackerPage(raw, rules, TimeZone.UTC)
    private fun tracker(
        statusText: String? = null, pageText: String? = null, etaText: String? = null,
        locationText: String? = null, todayIso: String? = null, events: List<DomRawEvent> = emptyList(),
    ) = DomRaw(kind = "tracker", statusText = statusText, pageText = pageText, etaText = etaText,
               locationText = locationText, todayIso = todayIso, events = events)

    @Test fun a_foreign_kind_is_refused() {
        assertNull(resolve(DomRaw(kind = "cards", statusText = "On the way")))
    }

    @Test fun shared_and_carrier_not_found_wordings_route_not_found() {
        assertIs<PageOutcome.NotFound>(resolve(tracker(pageText = "We couldn't find this tracking number")))
        assertIs<PageOutcome.NotFound>(resolve(tracker(pageText = "Status Not Available for this item")))
        assertIs<PageOutcome.NotFound>(resolve(tracker(statusText = "On the way", pageText = "invalid tracking number")))
    }

    @Test fun ordinary_page_text_does_not_trip_not_found() {
        val out = resolve(tracker(statusText = "On the way", pageText = "Track your package: on the way, expected Thursday"))
        assertEquals(TrackingStatus.IN_TRANSIT, out.snapshotOrNull()?.status)
    }

    @Test fun nothing_readable_is_empty() {
        assertIs<PageOutcome.Empty>(resolve(tracker()))
        assertIs<PageOutcome.Empty>(resolve(tracker(statusText = "   ", pageText = "Track a package")))
    }

    @Test fun a_novel_headline_is_still_a_result_with_unknown_status() {
        assertEquals(TrackingStatus.UNKNOWN, resolve(tracker(statusText = "Some new wording")).snapshotOrNull()?.status)
    }

    @Test fun a_promise_alone_is_a_result() {
        val out = resolve(tracker(etaText = "Estimated delivery Tuesday 8/19/2026 by 8:00 PM"))
        val s = out.snapshotOrNull()!!
        assertEquals(TrackingStatus.UNKNOWN, s.status)
        assertEquals(LocalDate(2026, 8, 19), s.etaDate)
        assertEquals(LocalTime(20, 0), s.etaWindowEnd)
    }

    @Test fun multi_stage_headline_is_refused_not_classified() {
        // A progress rail's step labels, all present in the DOM regardless of state.
        assertNull(resolve(tracker(statusText = "Picked up In transit Delivered")))
    }

    @Test fun headline_classifies_with_the_carrier_vocabulary_and_events_back_it_up() {
        val events = listOf(
            DomRawEvent("2026-08-17T09:00:00Z", "Picked up", "SANTA CLARA, CA"),
            DomRawEvent("2026-08-18T14:33:00Z", "Departed facility", "MEMPHIS, TN"),
        )
        val ok = resolve(tracker(statusText = "Moving through network", events = events)).snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, ok.status)
        assertEquals("MEMPHIS, TN", ok.latestLocation)
        assertEquals(TrackingStatus.SHIPPED, ok.events.first().status)
        val fallback = resolve(tracker(statusText = "Delivery date pending", events = events)).snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, fallback.status)
    }

    @Test fun location_hook_and_banner_win_over_events() {
        val stripping = TrackerPageRules(rules.vocabulary, location = { it.locationText?.removePrefix("Currently in ") })
        val out = resolveTrackerPage(tracker(statusText = "In transit", locationText = "Currently in Sacramento, CA"), stripping, TimeZone.UTC)
        assertEquals("Sacramento, CA", out.snapshotOrNull()?.latestLocation)
    }

    @Test fun eta_resolves_against_the_page_date_by_default() {
        val out = resolve(tracker(statusText = "In transit", etaText = "Arriving tomorrow", todayIso = "2026-08-18"))
        assertEquals(LocalDate(2026, 8, 19), out.snapshotOrNull()?.etaDate)
        assertNull(resolve(tracker(statusText = "In transit", etaText = "Arriving tomorrow")).snapshotOrNull()?.etaDate)
    }

    @Test fun delay_note_is_the_headline_or_else_the_newest_event_only() {
        val v = rules.vocabulary
        assertEquals("On the way: Delayed", headlineThenNewestEvent(v, "On the way: Delayed", emptyList()))
        val delayedNewest = listOf(
            TrackingEvent(Instant.parse("2026-08-18T05:00:00Z"), "Package left the facility"),
            TrackingEvent(Instant.parse("2026-08-18T07:00:00Z"), "Package delayed in transit"),
        )
        assertEquals("Package delayed in transit", headlineThenNewestEvent(v, "Arriving tomorrow", delayedNewest))
        // Delay is orthogonal to stage: a recognized headline never hides a delayed newest event.
        assertEquals("Package delayed in transit", headlineThenNewestEvent(v, "In transit", delayedNewest))
        // A delay event older than the newest scan doesn't flag a package that moved on.
        val movedOn = delayedNewest + TrackingEvent(Instant.parse("2026-08-19T10:00:00Z"), "Delivered")
        assertNull(headlineThenNewestEvent(v, "Delivered", movedOn))
        assertNull(headlineThenNewestEvent(v, "On the way", emptyList()))
    }

    @Test fun delayed_headline_carries_the_note_through_the_resolver() {
        val s = resolve(tracker(statusText = "On the way: Delayed")).snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
        assertEquals("On the way: Delayed", s.delayNote)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :source:webview:jvmTest --console=plain --tests '*TrackerPageResolverTest*'`
Expected: compilation FAILS.

- [ ] **Step 3: Implement `TrackerPageResolver.kt`**

```kotlin
package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Not-found wordings any carrier's tracker page might print, per the boundary rule: generic
 * English, each first validated live on some carrier. Carrier-specific copy ("problem finding
 * this order") stays in that carrier's [TrackerPageRules.notFound].
 */
private val SHARED_NOT_FOUND = listOf(
    """can.t find (that|this) tracking number""",
    """couldn.t find""",
    """unable to find""",
    """invalid tracking""",
    """no record of this tracking""",
    """could not locate the tracking""",
)

/**
 * Everything one carrier declares about reading its tracker page. Everything else — the
 * not-found check, the empty check, the multi-stage refusal, event conversion, the fallback
 * ladder — is [resolveTrackerPage]'s, so a fix there reaches every carrier.
 */
class TrackerPageRules(
    val vocabulary: StatusVocabulary,
    /** Regex sources (case-insensitive) matched against [DomRaw.pageText], merged with the shared list. */
    val notFound: List<String> = emptyList(),
    /** Reads the delivery promise out of [DomRaw.etaText]. Override only when the page needs a gate first. */
    val etaDate: (text: String?, today: LocalDate?) -> LocalDate? = ::parsePromiseDate,
    /** The current-location banner; override to strip page phrasing ("Currently in"). */
    val location: (DomRaw) -> String? = { it.locationText },
    val delayNote: (headline: String?, events: List<TrackingEvent>) -> String? =
        { headline, events -> headlineThenNewestEvent(vocabulary, headline, events) },
) {
    internal val notFoundPatterns: List<Regex> = (SHARED_NOT_FOUND + notFound).map { Regex(it, RegexOption.IGNORE_CASE) }
}

/**
 * The delay note for a page: the headline when it reports the delay, else the NEWEST event
 * when that does. Only the newest, because delay events stay in the history for the life of the
 * shipment and non-null delayNote IS the delay flag — scanning older rows would keep a
 * delivered package flagged forever.
 */
fun headlineThenNewestEvent(vocabulary: StatusVocabulary, headline: String?, events: List<TrackingEvent>): String? =
    headline?.takeIf { vocabulary.isDelayed(it) }
        ?: events.maxByOrNull { it.timestamp }?.description?.takeIf { vocabulary.isDelayed(it) }

/**
 * Reads a tracker page's [DomRaw] under [rules]. Null for a foreign [DomRaw.kind] or a
 * multi-stage headline (routes to Unparsed, so nothing is persisted and the API capture on the
 * same page stays the only writer); [PageOutcome.NotFound] when any not-found pattern matches;
 * [PageOutcome.Empty] when there is no headline, no event, and no promise; otherwise a
 * [PageOutcome.Tracking] assembled by the shared ladder.
 */
fun resolveTrackerPage(
    raw: DomRaw,
    rules: TrackerPageRules,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): PageOutcome? {
    if (raw.kind != "tracker") return null
    val pageText = raw.pageText
    if (pageText != null && rules.notFoundPatterns.any { it.containsMatchIn(pageText) }) return PageOutcome.NotFound
    val headline = raw.statusText?.takeIf { it.isNotBlank() }
    if (headline != null && rules.vocabulary.isMultiStage(headline)) return null
    val today = raw.today()
    val events = raw.events.mapNotNull { it.toTrackingEvent(rules.vocabulary, zone, today) }
    val etaDate = rules.etaDate(raw.etaText, today) ?: raw.etaDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val etaWindowText = raw.etaWindowText ?: findEtaWindowText(raw.etaText)
    if (headline == null && events.isEmpty() && etaDate == null && etaWindowText == null) return PageOutcome.Empty
    return PageOutcome.Tracking(
        assembleSnapshot(
            vocabulary = rules.vocabulary,
            headline = headline,
            events = events,
            etaDate = etaDate,
            etaWindowText = etaWindowText,
            location = rules.location(raw),
            delayNote = rules.delayNote(headline, events),
        ),
    )
}
```

- [ ] **Step 4: Run the module tests**

Run: `./gradlew :source:webview:jvmTest --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/TrackerPageResolver.kt \
        source/webview/src/commonTest/kotlin/com/dgmltn/shiphappens/source/webview/TrackerPageResolverTest.kt
git commit -m "[webview] TrackerPageRules + resolveTrackerPage: the shared tracker-page ladder"
```

---

### Task 7: Migrate UPS

**Files:**
- Modify: `source/ups/src/commonMain/kotlin/com/dgmltn/shiphappens/source/ups/UpsWebSpec.kt`
- Modify: `source/ups/src/commonMain/kotlin/com/dgmltn/shiphappens/source/ups/UpsApiParser.kt`
- Delete: `source/ups/src/commonMain/kotlin/com/dgmltn/shiphappens/source/ups/UpsPageLogic.kt`
- Test: rename `UpsPageLogicTest.kt` to `UpsVocabularyTest.kt`; modify `UpsApiParserTest.kt`

**Interfaces:**
- Consumes: `StatusVocabulary`, `StatusKeywords`, `TrackerPageRules`, `resolveTrackerPage`, `assembleSnapshot`, `eventAt`, `parseNumericMdyDate`, `parseCompactDate`, `parseTimeOfDay`, `EtaWindow`.
- Produces: `internal val UPS_VOCABULARY: StatusVocabulary`, `internal val UPS_PAGE: TrackerPageRules`, `UpsApiParser.parse(body): TrackingSnapshot?`.

- [ ] **Step 1: Rewrite the tests first**

Rename `UpsPageLogicTest.kt` to `UpsVocabularyTest.kt`, class `UpsVocabularyTest`. Replace `classifyUpsStatus(x)` with `UPS_VOCABULARY.classify(x)` throughout. Replace the page cases:

```kotlin
    private fun page(raw: DomRaw) = resolveTrackerPage(raw, UPS_PAGE)

    @Test fun tracker_page_classifies_the_status_headline() {
        assertEquals(TrackingStatus.IN_TRANSIT, page(DomRaw(kind = "tracker", statusText = "On the Way")).snapshotOrNull()?.status)
    }

    @Test fun unrecognized_headline_is_a_result_with_unknown_status() {
        // The DOM layer is UPS's coarse fallback; UNKNOWN routes further fallbacks downstream.
        assertEquals(TrackingStatus.UNKNOWN, page(DomRaw(kind = "tracker", statusText = "Novel wording")).snapshotOrNull()?.status)
    }

    @Test fun not_found_wording_routes_not_found_from_page_text() {
        assertIs<PageOutcome.NotFound>(page(DomRaw(kind = "tracker", pageText = "The tracking number you entered is invalid")))
        assertIs<PageOutcome.NotFound>(page(DomRaw(kind = "tracker", pageText = "Sorry, this tracking number was not found in our records")))
        assertIs<PageOutcome.Tracking>(page(DomRaw(kind = "tracker", statusText = "On the Way", pageText = "UPS tracking detail")))
    }

    @Test fun a_delayed_headline_reports_in_transit_and_carries_the_delay() {
        val s = page(DomRaw(kind = "tracker", statusText = "On the Way: Delayed")).snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
        assertEquals("On the Way: Delayed", s.delayNote)
    }

    @Test fun an_undelayed_headline_has_no_delay_note() {
        assertNull(page(DomRaw(kind = "tracker", statusText = "On the Way")).snapshotOrNull()?.delayNote)
    }

    @Test fun blank_page_is_empty_and_foreign_kind_is_null() {
        assertIs<PageOutcome.Empty>(page(DomRaw(kind = "tracker")))
        assertNull(page(DomRaw(kind = "cards", statusText = "On the Way")))
    }
```

In `UpsApiParserTest.kt`: every `"IN_TRANSIT"`-style string assertion on `.status` becomes the `TrackingStatus` enum (`assertEquals(TrackingStatus.IN_TRANSIT, t.status)`); `etaDate` assertions become `LocalDate(...)`; `etaWindowStart`/`etaWindowEnd` become `LocalTime(...)`; `location` becomes `latestLocation`; any assertion that a timestamp string parses as an instant is deleted (it is an `Instant` now); event `.status` string assertions become enum or `null`. Fixture JSON is untouched.

- [ ] **Step 2: Run to verify the module fails to compile**

Run: `./gradlew :source:ups:jvmTest --console=plain`
Expected: FAILS on `UPS_VOCABULARY`, `UPS_PAGE`, and the typed assertions.

- [ ] **Step 3: Move vocabulary and rules into `UpsWebSpec.kt`, delete `UpsPageLogic.kt`**

Add to `UpsWebSpec.kt` (above the JS constants), with imports for `StatusKeywords`, `StatusVocabulary`, `TrackerPageRules`, `resolveTrackerPage`:

```kotlin
// UPS wordings on top of the shared vocabulary. "action" is deliberately bare — the old JS
// matched it bare ("Action Needed"), the API says "action required"; bare covers both.
// "label"/"not received" are the API's label-stage phrasing, "order processed" the DOM banner's
// ("Order Processed: Ready for UPS" — UPS-local because on USPS pages bare "processed" is a
// transit scan). Shared by the API parser and the DOM fallback: one vocabulary, tested once.
internal val UPS_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        exception = listOf("action"),
        labelCreated = listOf("label", "not received", "order processed"),
        shipped = listOf("origin scan", "pickup"),
    ),
)

// Tracker page: the DOM layer is UPS's coarse fallback (API capture is primary). The not-found
// wording is verbatim from the JS blob's original decision (moved to Kotlin 2026-08-20).
internal val UPS_PAGE = TrackerPageRules(
    vocabulary = UPS_VOCABULARY,
    notFound = listOf("""tracking number.{0,40}(invalid|not found|couldn.t locate)"""),
)
```

Change the spec's two lines to `parseApi = { _, body -> UpsApiParser.parse(body) }` and `parseRaw = { resolveTrackerPage(it, UPS_PAGE) }`; drop the `toSnapshot`/`toOutcome` imports. Delete `UpsPageLogic.kt`.

- [ ] **Step 4: Rewrite `UpsApiParser`**

Keep the DTOs. Replace the object:

```kotlin
/**
 * Maps ups.com's in-page tracking API JSON to a [TrackingSnapshot]. Field vocabulary is
 * tolerant: every field optional, unknown wording degrades to UNKNOWN. UPS reports local
 * wall-clock times with no zone; we interpret them in the device zone — imperfect for
 * cross-zone shipments, but only event ordering and dates surface in the UI.
 */
object UpsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): TrackingSnapshot? {
        val detail = runCatching { json.decodeFromString<UpsTrackResponse>(body) }
            .getOrNull()?.trackDetails?.firstOrNull() ?: return null
        val activities = detail.shipmentProgressActivities.orEmpty()
        val zone = TimeZone.currentSystemDefault()
        val events = activities.mapNotNull { a ->
            val date = parseNumericMdyDate(a.date) ?: return@mapNotNull null
            val scan = a.activityScan ?: return@mapNotNull null
            TrackingEvent(
                timestamp = eventAt(date, parseTimeOfDay(a.time), zone),
                description = scan,
                location = a.location,
                status = UPS_VOCABULARY.classify(scan),
            )
        }
        return assembleSnapshot(
            vocabulary = UPS_VOCABULARY,
            // Status text takes precedence: live ups.com keeps packageStatusType "I" (a coarse
            // in-transit bucket) even when the package is out for delivery — only the text is
            // specific. The type code is the fallback for unrecognized or reworded statuses.
            headline = detail.packageStatus,
            events = events,
            etaDate = parseCompactDate(detail.sdd) ?: parseNumericMdyDate(detail.scheduledDeliveryDate),
            etaWindow = EtaWindow(parseTimeOfDay(detail.sdst), parseTimeOfDay(detail.sdt)),
            location = activities.firstOrNull()?.location,  // UPS lists newest first
            // Delay rides alongside the stage rather than replacing it: "On the Way: Delayed" is
            // genuinely in transit, and genuinely late. Prefer UPS's reason sentence; the
            // headline itself is the fallback when there isn't one.
            delayNote = detail.packageStatus
                ?.takeIf { UPS_VOCABULARY.isDelayed(it) }
                ?.let { detail.simplifiedText?.takeIf(String::isNotBlank) ?: it },
            statusFallback = typeCodeStatus(detail.packageStatusType),
        )
    }

    private fun typeCodeStatus(code: String?): TrackingStatus? = when (code?.uppercase()) {
        "M" -> TrackingStatus.LABEL_CREATED
        "P" -> TrackingStatus.SHIPPED
        "I" -> TrackingStatus.IN_TRANSIT
        "O" -> TrackingStatus.OUT_FOR_DELIVERY
        "D" -> TrackingStatus.DELIVERED
        "X" -> TrackingStatus.EXCEPTION
        else -> null
    }
}
```

Imports: `TrackingEvent`, `TrackingSnapshot`, `TrackingStatus` from domain; `EtaWindow`, `assembleSnapshot`, `eventAt`, `parseCompactDate`, `parseNumericMdyDate`, `parseTimeOfDay` from webview; `TimeZone` from kotlinx.datetime. Remove the four private date/time regex helpers and the `LocalDate`/`LocalDateTime`/`LocalTime`/`toInstant` imports.

- [ ] **Step 5: Run the UPS tests**

Run: `./gradlew :source:ups:jvmTest --console=plain`
Expected: PASS. One expected behavioral refinement: with the API's status text unrecognized, the newest classifiable event is now consulted before the type code (the assembler's ladder). `status_type_codes_map_to_canonical` passes because its fixtures carry no events.

- [ ] **Step 6: Run the full suite, then commit**

```bash
git add -A source/ups
git commit -m "[ups] Migrate to the shared vocabulary, resolver, and assembler; delete UpsPageLogic"
```

---

### Task 8: Migrate USPS

**Files:**
- Modify: `source/usps/.../UspsWebSpec.kt`, `source/usps/.../UspsApiParser.kt`
- Delete: `source/usps/.../UspsPageLogic.kt`
- Test: rename `UspsPageLogicTest.kt` to `UspsVocabularyTest.kt`; modify `UspsApiParserTest.kt`

**Interfaces:**
- Produces: `internal val USPS_VOCABULARY`, `internal val USPS_PAGE`, `UspsApiParser.parse(body): TrackingSnapshot?`.

- [ ] **Step 1: Rewrite the tests**

Rename to `UspsVocabularyTest`. `classifyUspsStatus(x)` becomes `USPS_VOCABULARY.classify(x)`. `parseUspsEtaDate(x)` becomes `USPS_PAGE.etaDate(x, null)`. `parseUspsRaw(raw)` becomes `resolveTrackerPage(raw, USPS_PAGE, TimeZone.UTC)`, with assertions converted: `?.page == "ok"` to `assertIs<PageOutcome.Tracking>`, `?.tracking?.status == "IN_TRANSIT"` to `.snapshotOrNull()?.status == TrackingStatus.IN_TRANSIT`, `etaDate` strings to `LocalDate`, `etaWindowText` assertions to `etaWindowEnd == LocalTime(21, 0)` (the resolver now parses the window), `location` to `latestLocation`, `"empty"` to `assertIs<PageOutcome.Empty>`, `"notFound"` to `assertIs<PageOutcome.NotFound>`. The `liveRaw` fixture and every banner string stay verbatim.

In `UspsApiParserTest.kt` apply the same string-to-type conversion as Task 7 Step 1.

- [ ] **Step 2: Run to verify the module fails to compile**

Run: `./gradlew :source:usps:jvmTest --console=plain`

- [ ] **Step 3: Move vocabulary and rules into `UspsWebSpec.kt`, delete `UspsPageLogic.kt`**

```kotlin
// USPS wordings on top of the shared vocabulary: tracker-banner phrasing like "moving through
// our network" and acceptance-scan wording. Bare "processed" ("Processed Through Facility") is
// safe as a late-stage keyword because the merged vocabulary runs in one pass — an earlier
// "out for delivery" in the same text still wins. Shared by the API parser and the DOM reader.
internal val USPS_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        exception = listOf("alert"),
        labelCreated = listOf("pre-shipment", "awaiting item"),
        shipped = listOf("accepted", "possession"),
        inTransit = listOf("moving through", "processed"),
    ),
)

// Tracker page: the DOM reader is USPS's RELIABLE layer (inverse of UPS). The ETA banner's
// textContent interleaves tooltip copy with the date ("Tuesday 28 July 2026" split across
// spans, or "Monday, July 28, 2026"); the shared promise chain's month-name parsing is safe
// against that junk because neither tooltip contains a month-name-adjacent number. Not-found
// wording is verbatim from the JS blob's original decision (moved to Kotlin 2026-08-20).
internal val USPS_PAGE = TrackerPageRules(
    vocabulary = USPS_VOCABULARY,
    notFound = listOf("""status not available|could not locate the tracking information"""),
)
```

Spec lines: `parseApi = { _, body -> UspsApiParser.parse(body) }`, `parseRaw = { resolveTrackerPage(it, USPS_PAGE) }`.

- [ ] **Step 4: Rewrite `UspsApiParser`**

Keep the DTOs and their PROVISIONAL comment. Replace the object body:

```kotlin
object UspsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): TrackingSnapshot? {
        val r = runCatching { json.decodeFromString<UspsTrackResponse>(body) }.getOrNull() ?: return null
        if (r.statusCategory == null && r.trackingEvents.isNullOrEmpty()) return null  // foreign JSON
        val zone = TimeZone.currentSystemDefault()
        val events = r.trackingEvents.orEmpty().mapNotNull { e ->
            val type = e.eventType ?: return@mapNotNull null
            val at = parseTimestamp(e.eventTimestamp, zone) ?: return@mapNotNull null
            TrackingEvent(timestamp = at, description = type, location = locationOf(e), status = USPS_VOCABULARY.classify(type))
        }
        return assembleSnapshot(
            vocabulary = USPS_VOCABULARY,
            headline = r.statusCategory ?: r.statusSummary,
            events = events,
            etaDate = parseAnyDate(r.expectedDeliveryDate),
            etaWindow = EtaWindow(null, parseTimeOfDay(r.expectedDeliveryTime)),
        )
    }

    private fun locationOf(e: UspsEvent): String? =
        listOfNotNull(e.eventCity?.trim()?.takeIf { it.isNotEmpty() }, e.eventState?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(", ").takeIf { it.isNotEmpty() }

    /** ISO instant ("...Z") or zoneless ISO local datetime, device zone. */
    private fun parseTimestamp(raw: String?, zone: TimeZone): Instant? {
        val s = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        runCatching { Instant.parse(s) }.getOrNull()?.let { return it }
        return runCatching { LocalDateTime.parse(s).toInstant(zone) }.getOrNull()
    }
}
```

Note the headline rule: the old code classified `statusCategory ?: statusSummary ?: ""` and fell back to the newest event; `assembleSnapshot` does the same with `headline = r.statusCategory ?: r.statusSummary`. `parseTimeOfDay` covers both "20:00:00" and "8:00pm".

- [ ] **Step 5: Run USPS tests, then the full suite, then commit**

```bash
./gradlew :source:usps:jvmTest --console=plain
git add -A source/usps
git commit -m "[usps] Migrate to the shared vocabulary, resolver, and assembler; delete UspsPageLogic"
```

---

### Task 9: Migrate DHL eCommerce

**Files:**
- Modify: `source/dhlecs/.../DhlEcsWebSpec.kt`, `source/dhlecs/.../DhlEcsApiParser.kt`
- Delete: `source/dhlecs/.../DhlEcsPageLogic.kt`
- Test: rename `DhlEcsPageLogicTest.kt` to `DhlEcsVocabularyTest.kt`; modify `DhlEcsApiParserTest.kt`

**Interfaces:**
- Produces: `internal val DHLECS_VOCABULARY`, `internal val DHLECS_PAGE`, `DhlEcsApiParser.parse(body): TrackingSnapshot?`.

- [ ] **Step 1: Rewrite the tests**

`DhlEcsVocabularyTest`: `statusOf(text)` becomes `resolveTrackerPage(DomRaw(kind = "tracker", statusText = text), DHLECS_PAGE).snapshotOrNull()?.status` asserting enums; `trackingOf` likewise returns the snapshot; `progress_rail_text_is_refused_not_classified` asserts `assertNull(resolveTrackerPage(...))`; not-found asserts `assertIs<PageOutcome.NotFound>`; `raw_without_status_text_routes_to_unparsed` becomes `assertIs<PageOutcome.Empty>(resolveTrackerPage(DomRaw(kind = "tracker"), DHLECS_PAGE))` and `assertNull(resolveTrackerPage(DomRaw(kind = "cards"), DHLECS_PAGE))`. Add:

```kotlin
    @Test fun undelivered_headline_is_an_exception_via_the_shared_lane() {
        assertEquals(TrackingStatus.EXCEPTION, DHLECS_VOCABULARY.classify("Undelivered - Processes for Local Disposal"))
    }
```

`DhlEcsApiParserTest.kt`: string-to-type conversion as in Task 7; zone tests compare `Instant.parse("...")` values instead of ISO strings.

Behavior note for the executor: the old DOM fallback returned null (Unparsed) for a blank headline; the shared resolver returns `Empty`, which the router also maps to Unparsed. Same route, different name; the test asserts the new name.

- [ ] **Step 2: Run to verify the module fails to compile**

Run: `./gradlew :source:dhlecs:jvmTest --console=plain`

- [ ] **Step 3: Move vocabulary and rules into `DhlEcsWebSpec.kt`, delete `DhlEcsPageLogic.kt`**

```kotlin
// The wordings come from webtrack's en-US locale file (recon 2026-09-03), which enumerates
// the full event vocabulary (`id_99`…`id_803`); the API's SCREAMING primaryEventDescription
// values are the same strings uppercased, so one vocabulary serves both layers. The
// "Undelivered" negation (event 636) is handled by the shared vocabulary's negated-delivered lane.
internal val DHLECS_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        labelCreated = listOf("electronic notification"),
        shipped = listOf("pick up", "accepted", "received by carrier"),
        inTransit = listOf(
            "arrival", "en route", "processed", "departure", "forwarded", "sorted",
            "tendered", "manifested", "transport", "customs clearance", "cleared customs",
        ),
        exception = listOf(
            "refused", "undeliverable", "damage", "missent", "mis-shipped", "dead letter",
            "no such number", "insufficient", "unclaimed", "vacant", "addressee unknown",
            "not possible", "recalled",
        ),
    ),
)

// Coarse DOM fallback for when the API capture misses. A container grab that swallowed the
// progress rail names every stage at once — the shared resolver refuses such a headline
// (Unparsed, nothing persisted) rather than classify it; live QA 2026-09-03 saw the rail
// overwrite a label-only package as DELIVERED through the scrape-on-view path. Not-found copy is
// webtrack's en-US no_records string (locale recon 2026-09-03).
internal val DHLECS_PAGE = TrackerPageRules(
    vocabulary = DHLECS_VOCABULARY,
    notFound = listOf("""no results? found|confirm the accuracy of your tracking number"""),
)
```

Spec lines: `parseApi = { _, body -> DhlEcsApiParser.parse(body) }`, `parseRaw = { resolveTrackerPage(it, DHLECS_PAGE) }`.

- [ ] **Step 4: Rewrite `DhlEcsApiParser`**

Keep the DTOs, the KDoc, and `describe`. Replace `parse`, and delete the private `ZONES` map and `toIsoInstant`:

```kotlin
    fun parse(body: String): TrackingSnapshot? {
        val response = runCatching { json.decodeFromString<DhlEcsResponse>(body) }.getOrNull() ?: return null
        val pkg = response.packages.firstOrNull() ?: return null
        val device = TimeZone.currentSystemDefault()
        val events = pkg.events.mapNotNull { e ->
            val rawDescription = e.primaryEventDescription?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val date = e.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val time = e.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            TrackingEvent(
                timestamp = eventAt(date, time, zoneForAbbreviation(e.timeZone) ?: device),
                description = describe(rawDescription),
                location = e.location?.takeIf { it.isNotBlank() },
                status = DHLECS_VOCABULARY.classify(rawDescription),
            )
        }
        return assembleSnapshot(
            vocabulary = DHLECS_VOCABULARY,
            headline = pkg.status,
            events = events,
            etaDate = parseAnyDate(pkg.estimatedDeliveryDate),
            // Only the live status or the NEWEST event may assert a delay — the shared rule.
            delayNote = headlineThenNewestEvent(DHLECS_VOCABULARY, pkg.status, events),
        )
    }
```

Imports: `TrackingEvent`, `TrackingSnapshot`; `assembleSnapshot`, `eventAt`, `headlineThenNewestEvent`, `parseAnyDate`, `zoneForAbbreviation`; `LocalDate`, `LocalTime`, `TimeZone`. Remove `isDelayedWording`, `TrackingStatus`, `LocalDateTime`, `toInstant` imports.

Note: the old event fallback for the delay note used `events.lastOrNull()` on a list sorted by ISO string; `headlineThenNewestEvent` uses `maxByOrNull { timestamp }`, which is the same event. The old `describe()` was applied before the delay check on events and the fixture asserts the described form ("Possible delivery delay - adverse weather"); `TrackingEvent.description` is the described form, so the assertion holds.

- [ ] **Step 5: Run DHL tests, then the full suite, then commit**

```bash
./gradlew :source:dhlecs:jvmTest --console=plain
git add -A source/dhlecs
git commit -m "[dhlecs] Migrate to the shared vocabulary, resolver, and zone table; delete DhlEcsPageLogic"
```

---

### Task 10: Migrate Amazon Logistics

**Files:**
- Modify: `source/amzl/.../AmzlWebSpec.kt`, `source/amzl/.../AmzlApiParser.kt`
- Delete: `source/amzl/.../AmzlPageLogic.kt`
- Test: modify `AmzlWebSpecTest.kt`, `AmzlApiParserTest.kt`

**Interfaces:**
- Produces: `internal val AMZL_VOCABULARY`, `internal val AMZL_PAGE`, `AmzlApiParser.parse(body): TrackingSnapshot?`.

- [ ] **Step 1: Rewrite the tests**

`AmzlWebSpecTest.kt`: `parseAmzlRaw(raw)` becomes `resolveTrackerPage(raw, AMZL_PAGE)`; `?.tracking?.status` becomes `.snapshotOrNull()?.status` with enums; `?.page == "notFound"` becomes `assertIs<PageOutcome.NotFound>`; `assertNull(parseAmzlRaw(DomRaw(kind = "tracker")))` becomes `assertIs<PageOutcome.Empty>(...)`. Add one case:

```kotlin
    @Test fun shipped_headline_now_names_its_own_stage() {
        // The old DOM chain had no SHIPPED lane and read "Shipped" as IN_TRANSIT; the shared
        // vocabulary has one.
        assertEquals(TrackingStatus.SHIPPED, resolveTrackerPage(DomRaw(kind = "tracker", statusText = "Shipped"), AMZL_PAGE).snapshotOrNull()?.status)
    }
```

`AmzlApiParserTest.kt`: string-to-type conversion as in Task 7. Every existing token expectation stays (`CreationConfirmed`, `PickupDone`, `InTransit`, `ArrivedAtDeliveryCenter`, `OutForDelivery`, `Delivered`, `DeliveryAttempted`, `Undeliverable`, `ReturnedToSeller`, `SomeNewWording`, `InTransitDelayed`, `Delayed`, `DeliveryDelayed`, `READY_FOR_RECEIVE`, `OUT_FOR_DELIVERY`, `DELIVERED`).

- [ ] **Step 2: Run to verify the module fails to compile**

Run: `./gradlew :source:amzl:jvmTest --console=plain`

- [ ] **Step 3: Move vocabulary and rules into `AmzlWebSpec.kt`, delete `AmzlPageLogic.kt`**

```kotlin
// One AMZL vocabulary for both layers. The tracker page's headline is English ("Arriving
// Wednesday", "We have your package details"); the API's codes are CamelCase or
// SCREAMING_SNAKE ("CreationConfirmed", "READY_FOR_RECEIVE") and reach the same chain through
// classifyToken, which reads them as words. Only CreationConfirmed/READY_FOR_RECEIVE were
// observed live (2026-08-11); the rest derives from the SPA's milestone string ids
// (swa_rex_intransit, swa_rex_ofd, …) and is confirmed during device QA.
internal val AMZL_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        outForDelivery = listOf("ofd"),
        exception = listOf("undeliverable", "problem", "lost", "damaged", "reject"),
        labelCreated = listOf("package details", "preparing", "creation confirmed", "ready for receive"),
        shipped = listOf("pickup", "package received", "shipped"),
        inTransit = listOf("arriving"),
    ),
)

// Coarse DOM fallback for when the API capture misses. Not-found wording is verbatim from the
// JS blob's original decision (moved to Kotlin 2026-08-20).
internal val AMZL_PAGE = TrackerPageRules(
    vocabulary = AMZL_VOCABULARY,
    notFound = listOf("""no longer available"""),
)
```

(The blob's other not-found phrases — "couldn't find", "can't find", "unable to find", "invalid tracking" — are now the shared seeds.)

Spec lines: `parseApi = { _, body -> AmzlApiParser.parse(body) }`, `parseRaw = { resolveTrackerPage(it, AMZL_PAGE) }`.

- [ ] **Step 4: Rewrite `AmzlApiParser`**

Keep the DTOs, the KDoc, `EVENT_DESCRIPTIONS`, and `render()`. Replace `parse`, `classify`, `isDelayed`, `describe`, `MONTHS`, `DATE_TIME`, `parseDateTime`:

```kotlin
    fun parse(body: String): TrackingSnapshot? {
        val envelope = runCatching { json.decodeFromString<AmzlEnvelope>(body) }.getOrNull() ?: return null
        val tracker = envelope.progressTracker
            ?.let { runCatching { json.decodeFromString<AmzlProgressTracker>(it) }.getOrNull() }
        // TRACKING_ID_NOT_FOUND arrives as an in-band error on HTTP 200; parseApi can only say
        // snapshot-or-null, so null it is — the DOM fallback owns the NotFound outcome.
        if (tracker == null || tracker.errors.isNotEmpty()) return null
        val history = envelope.eventHistory
            ?.let { runCatching { json.decodeFromString<AmzlEventHistoryDoc>(it) }.getOrNull() }
        val summary = tracker.summary
        if (summary == null && history == null) return null

        val zone = TimeZone.currentSystemDefault()
        val events = history?.eventHistory.orEmpty().mapNotNull { e ->
            val code = e.eventCode ?: return@mapNotNull null
            val date = parseMonthNameDate(e.eventTime) ?: return@mapNotNull null
            TrackingEvent(
                timestamp = eventAt(date, parseTimeOfDay(e.eventTime), zone),
                description = describe(code),
                location = e.location?.render(),
                status = AMZL_VOCABULARY.classifyToken(code),
            )
        }
        val statusTokens = listOfNotNull(
            summary?.status,
            summary?.metadata?.trackingStatus?.stringValue,
            history?.summary?.status,
        )
        return assembleSnapshot(
            vocabulary = AMZL_VOCABULARY,
            // The first token that names a stage; humanized so the chain reads words.
            headline = statusTokens.firstOrNull { AMZL_VOCABULARY.classifyToken(it) != null }?.let { humanizeToken(it) },
            events = events,
            etaDate = (summary?.metadata?.promisedDeliveryDate?.date ?: summary?.metadata?.expectedDeliveryDate?.date)
                ?.let { parseMonthNameDate(it) },
            // The API carries codes, not prose ("DeliveryDelayed"), so the note reuses the same
            // CamelCase-splitting rendering the event descriptions get.
            delayNote = statusTokens.firstOrNull { AMZL_VOCABULARY.isDelayedToken(it) }?.let { describe(it) },
        )
    }

    private fun describe(code: String): String =
        EVENT_DESCRIPTIONS[code] ?: humanizeToken(code).replaceFirstChar { it.uppercase() }
```

Imports: `TrackingEvent`, `TrackingSnapshot`; `assembleSnapshot`, `eventAt`, `humanizeToken`, `parseMonthNameDate`, `parseTimeOfDay`; `TimeZone`. Remove `LocalDate`, `LocalDateTime`, `LocalTime`, `toInstant`.

Behavior notes: the old code took `events.lastOrNull()?.location` (newest event even if its location was null); the assembler takes the newest event that has one. The old code never consulted events for the overall status (unclassifiable tokens meant UNKNOWN); the assembler falls back to the newest classified event first, as every other carrier does. No existing fixture pairs unclassifiable summary tokens with classifiable events, so every assertion holds. The old event timestamp parser required the exact "Mon D, YYYY, h:mm:ss AM" shape; the shared parsers accept it (Task 1 added seconds and narrow-space handling) and are looser about the rest.

- [ ] **Step 5: Run AMZL tests, then the full suite, then commit**

```bash
./gradlew :source:amzl:jvmTest --console=plain
git add -A source/amzl
git commit -m "[amzl] One vocabulary for API tokens and page headlines; migrate to the shared resolver"
```

---

### Task 11: Migrate FedEx

**Files:**
- Modify: `source/fedex/.../FedexWebSpec.kt`, `source/fedex/.../FedexPageLogic.kt`
- Test: modify `FedexPageLogicTest.kt`

**Interfaces:**
- Produces: `internal val FEDEX_VOCABULARY`, `internal val FEDEX_PAGE` (in `FedexPageLogic.kt`, which keeps the two hooks), `internal fun fedexLocation(text: String?): String?`.

- [ ] **Step 1: Rewrite the tests**

In `FedexPageLogicTest.kt`: `classifyFedexStatus(x)` becomes `FEDEX_VOCABULARY.classify(x)`; `parseFedexEtaDate(x, today)` becomes `FEDEX_PAGE.etaDate(x, today)`; `parseFedexRaw(raw)` becomes `resolveTrackerPage(raw, FEDEX_PAGE, TimeZone.UTC)` (the file already imports `TimeZone`) with the `DomExtraction`-to-`PageOutcome` assertion conversions used in Tasks 7 to 10; `etaWindowText` assertions become `etaWindowEnd`/`etaWindowStart` `LocalTime` assertions; the `events_build_timestamps_from_when_text_and_sort_ascending` case compares `Instant` values built with `LocalDateTime(...).toInstant(TimeZone.UTC)` (the test already has those imports). `fedexLocation` cases stay as they are. The live-capture fixtures stay verbatim.

- [ ] **Step 2: Run to verify the module fails to compile**

Run: `./gradlew :source:fedex:jvmTest --console=plain`

- [ ] **Step 3: Rewrite `FedexPageLogic.kt`**

```kotlin
package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.source.webview.StatusKeywords
import com.dgmltn.shiphappens.source.webview.StatusVocabulary
import com.dgmltn.shiphappens.source.webview.TrackerPageRules

/**
 * What the FedEx scrape *decides* beyond the shared tracker-page resolver: its vocabulary, its
 * not-found copy, and one hook for the location banner's phrasing. Everything else — the
 * promise date chain, event rows built from verbatim date-group headers plus times, the
 * fallback ladder — is the shared resolver's.
 */

// FedEx wordings on top of the shared vocabulary. "Held at FedEx location" may be a
// customer-requested hold, but it still needs the user's attention (someone must go pick it up)
// — the shared "held" keyword already rides the EXCEPTION lane.
internal val FEDEX_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        outForDelivery = listOf("on fedex vehicle"),
        exception = listOf("incorrect address"),
        labelCreated = listOf("shipment information sent"),
        // "In FedEx possession" is the travel history's pickup-adjacent scan (live capture 2026-08-21).
        shipped = listOf("we have your package", "possession"),
        inTransit = listOf(
            "at local fedex facility", "at destination sort", "left fedex origin",
            "international shipment release",
        ),
    ),
)

/** "Currently in Sacramento, CA" → "Sacramento, CA"; any other phrasing passes through verbatim. */
internal fun fedexLocation(text: String?): String? {
    val t = text?.replace(Regex("\\s+"), " ")?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    return t.replace(Regex("""^currently\s+in\s+""", RegexOption.IGNORE_CASE), "")
}

// The three live not-found wordings (QA 2026-08-19/20): /fedextrack/no-results-found ("The
// tracking number you entered can't be found right now"), the system-error page ("We can't find
// that tracking number. Please check with the shipper"), and the generic no-record phrasing.
// The "can't find that/this tracking number" and "no record" forms are shared seeds now; the
// FedEx-specific phrasings stay here.
internal val FEDEX_PAGE = TrackerPageRules(
    vocabulary = FEDEX_VOCABULARY,
    notFound = listOf("""tracking number.{0,80}can.t be found|please check (the number )?with the shipper"""),
    location = { fedexLocation(it.locationText) },
)
```

In `FedexWebSpec.kt`: `parseRaw = { resolveTrackerPage(it, FEDEX_PAGE) }`; drop the `toOutcome` import; keep everything else, including the empty `apiUrlPatterns` and the 12-second settle.

- [ ] **Step 4: Run FedEx tests, then the full suite, then commit**

Expected: PASS. If `pending_banner_has_no_date` or the weekday case fails, the promise chain order in Task 1 is wrong; fix it there, not by re-adding a FedEx chain.

```bash
./gradlew :source:fedex:jvmTest --console=plain
git add -A source/fedex
git commit -m "[fedex] Migrate to the shared resolver; keep only the vocabulary, not-found copy, and location hook"
```

---

### Task 12: Migrate Amazon

**Files:**
- Modify: `source/amazon/.../AmazonPageLogic.kt`, `source/amazon/.../AmazonWebSpec.kt`
- Test: modify `AmazonPageLogicTest.kt`

**Interfaces:**
- Produces: `internal val AMAZON_VOCABULARY: StatusVocabulary` (base `None`), `internal fun parseAmazonDay`, `internal fun amazonEtaFromStatus`, `internal fun pickShipmentCard`, `internal fun resolveAmazonCards(raw): PageOutcome`, `internal fun resolveAmazonTracker(raw): PageOutcome`, `internal fun parseAmazonRaw(raw): PageOutcome?`.

- [ ] **Step 1: Rewrite the tests**

In `AmazonPageLogicTest.kt`: `classifyAmazonStatus(x)` becomes `AMAZON_VOCABULARY.classify(x)`; `isAmazonDelayed(x)` becomes `AMAZON_VOCABULARY.isDelayed(x)`; `resolveAmazonTracker(raw).tracking?.status` becomes `resolveAmazonTracker(raw).snapshotOrNull()?.status` with enums; `.page == "goto"` becomes `assertIs<PageOutcome.Goto>`, and `.url` / `.tracking?.etaDate` on a goto become `goto.url` / `goto.coarse?.etaDate` with `LocalDate`; `.page == "empty"` becomes `assertIs<PageOutcome.Empty>`; `.page == "notFound"` becomes `assertIs<PageOutcome.NotFound>`; the `etaWindowText == "by 8 AM"` assertion becomes `etaWindowEnd == LocalTime(8, 0)` (the assembler now parses the window); `location` becomes `latestLocation`. All card and tracker fixture strings stay verbatim.

- [ ] **Step 2: Run to verify the module fails to compile**

Run: `./gradlew :source:amazon:jvmTest --console=plain`

- [ ] **Step 3: Rewrite `AmazonPageLogic.kt`**

Replace the phrase lists, `classifyAmazonStatus`, `isAmazonDelayed`, and `delayNoteFor` with:

```kotlin
/**
 * Amazon's vocabulary starts from NO shared phrases (BaseKeywords.None): an order page mixes
 * shipment cards with RMA/returns copy, so the carrier base's bare "return" and "attempt"
 * would misclassify — "Replacement complete — We've received your return" was promoted to
 * EXCEPTION on 2026-07-19. Return phrasing is therefore matched only as a delivery outcome.
 *
 * "Arriving <day>" is deliberately NOT an in-transit phrase: Amazon shows it as the delivery
 * promise the moment an order is placed, so it says nothing about the shipment's state — it
 * only carries the ETA (see [amazonEtaFromStatus]). Real movement is signaled by the tracker
 * page's events and status line.
 *
 * Delay phrases include the revised-promise wording ("Now expected tomorrow by 8 AM"), used only
 * when the original promise slipped (captured 2026-08-18). They are modifiers, never stages:
 * "Package delayed in transit" reports the IN_TRANSIT its own wording names, while a stage-less
 * "Now expected tomorrow" answers null rather than guessing — a slipped promise is a valid state
 * for an order that has not shipped yet.
 */
internal val AMAZON_VOCABULARY = StatusVocabulary(
    StatusKeywords(
        outForDelivery = listOf("out for delivery"),
        delivered = listOf("delivered"),
        // Longest-intent-first: a delivered order still shows returns copy and an open return window.
        exception = listOf(
            "undeliverable", "delivery attempted", "returned to sender", "return to sender",
            "being returned", "package was lost", "lost in transit",
        ),
        labelCreated = listOf("not yet shipped", "not shipped", "ordered", "order placed", "preparing for shipment"),
        shipped = listOf("shipped", "dispatched", "picked up"),
        inTransit = listOf("in transit", "on the way", "on its way", "at carrier"),
        delayed = listOf("delayed", "running late", "now expected"),
    ),
    base = BaseKeywords.None,
)
```

Keep `ETA_PHRASES`, `parseAmazonDay`, `amazonEtaFromStatus`, `pickShipmentCard`, and `AMAZON_NOT_FOUND` as they are, with `classifyAmazonStatus(...)` calls inside them replaced by `AMAZON_VOCABULARY.classify(...)`. Delete the private `DomRaw.today()` (the shared extension replaces it). Rewrite the two resolvers and the dispatcher:

```kotlin
/** Order-details page: choose a shipment and either hop to its tracker or report its coarse state. */
internal fun resolveAmazonCards(raw: DomRaw): PageOutcome {
    if (raw.pageText?.let { AMAZON_NOT_FOUND.containsMatchIn(it) } == true) return PageOutcome.NotFound
    val pick = pickShipmentCard(raw.cards) ?: return PageOutcome.Empty
    val eta = amazonEtaFromStatus(pick.head, raw.today())
    // The headline's "Arriving <day>" carries the ETA but not a transit state, so a card can
    // have a delivery date with no classifiable status. Keep the ETA regardless — a shipment
    // with no tracker link still yields a countdown — and leave status UNKNOWN until a real
    // signal (the tracker hop, or delivered/shipped/exception phrasing) supplies one.
    val status = AMAZON_VOCABULARY.classify(pick.head)
    val coarse = if (status != null || eta != null) {
        TrackingSnapshot(
            status = status ?: TrackingStatus.UNKNOWN,
            etaDate = eta,
            delayNote = pick.head.takeIf { AMAZON_VOCABULARY.isDelayed(it) },
        )
    } else null
    return when {
        pick.href != null -> PageOutcome.Goto(pick.href, coarse)
        coarse != null -> PageOutcome.Tracking(coarse)
        else -> PageOutcome.Empty
    }
}

/** Tracker page: the shared ladder, with Amazon's promise-phrase gate on the status line. */
internal fun resolveAmazonTracker(raw: DomRaw, zone: TimeZone = TimeZone.currentSystemDefault()): PageOutcome {
    val today = raw.today()
    val events = raw.events.mapNotNull { it.toTrackingEvent(AMAZON_VOCABULARY, zone, today) }
    val headline = raw.statusText?.takeIf { it.isNotBlank() }
    if (headline == null && events.isEmpty()) return PageOutcome.Empty
    return PageOutcome.Tracking(
        assembleSnapshot(
            vocabulary = AMAZON_VOCABULARY,
            headline = headline,
            events = events,
            etaDate = parseAmazonDay(raw.etaText, today)
                ?: amazonEtaFromStatus(headline, today)
                ?: raw.etaDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            etaWindowText = raw.etaWindowText,
            delayNote = headlineThenNewestEvent(AMAZON_VOCABULARY, headline, events),
        ),
    )
}

/** Dispatches a raw extraction by the page that produced it. */
internal fun parseAmazonRaw(raw: DomRaw): PageOutcome? = when (raw.kind) {
    "cards" -> resolveAmazonCards(raw)
    "tracker" -> resolveAmazonTracker(raw)
    else -> null
}
```

Imports: `TrackingSnapshot`, `TrackingStatus`; `BaseKeywords`, `PageOutcome`, `StatusKeywords`, `StatusVocabulary`, `assembleSnapshot`, `headlineThenNewestEvent`, `today`, `toTrackingEvent`; `TimeZone`. Remove `ScrapedEvent`, `ScrapedTracking`, `DomExtraction`.

Why the tracker keeps its own function instead of `TrackerPageRules`: its ETA reads the status line through a promise-phrase gate, which the resolver's `etaDate` hook cannot express (the hook only sees `etaText`). The ladder itself is still the shared assembler.

In `AmazonWebSpec.kt`: `parseRaw = ::parseAmazonRaw`; drop the `toOutcome` import.

- [ ] **Step 4: Run Amazon tests, then the full suite, then commit**

```bash
./gradlew :source:amazon:jvmTest --console=plain
git add -A source/amazon
git commit -m "[amazon] Amazon vocabulary on the shared engine (no carrier base); tracker page through the shared assembler"
```

---

### Task 13: Delete the transitional layer, verify on iOS, update docs

**Files:**
- Delete: `source/webview/.../ScrapedTracking.kt`, `source/webview/src/commonTest/.../ScrapedTrackingTest.kt`
- Modify: `.../webview/PayloadRouter.kt` (remove `DomExtraction.tracking`), `.../webview/StatusVocabulary.kt` (remove the three delegates), `README.md`

- [ ] **Step 1: Delete the adapters and the string types**

Delete `ScrapedTracking.kt` and `ScrapedTrackingTest.kt`. In `PayloadRouter.kt` remove `val tracking: ScrapedTracking? = null` from `DomExtraction`. In `StatusVocabulary.kt` remove the three transitional delegate functions and the comment above them.

- [ ] **Step 2: Confirm nothing references them**

```bash
grep -rn "ScrapedTracking\|ScrapedEvent\|toSnapshot()\|toOutcome()\|classifyStatusWording\|isDelayedWording\|isMultiStageWording" --include='*.kt' source ui data
```

Expected: no output. Fix any hit before continuing.

- [ ] **Step 3: Run the full suite and the iOS simulator test**

```bash
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest :source:ups:jvmTest \
          :source:usps:jvmTest :source:fedex:jvmTest :source:amazon:jvmTest \
          :source:amzl:jvmTest :source:dhlecs:jvmTest :source:webview:jvmTest \
          :ui:testAndroidHostTest --console=plain
./gradlew :source:webview:iosSimulatorArm64Test --console=plain
```

Expected: both PASS. The iOS run is the guard for the Kotlin/Native miscompile; `failure_taxonomy_mapping` in `WebViewBasedSourceTest` must pass there, not just on the JVM.

- [ ] **Step 4: Update the README test count and the source description**

In `README.md`, the "Running tests" paragraph states the suite total and per-module counts; recount from the Gradle output (`grep -c "@Test"` per module is a quick cross-check) and update the numbers. In the paragraph that describes what a source module contains (around line 127, "makes every scrape decision in testable Kotlin, an API parser for captured XHR JSON, and a…"), reword to: a `WebProviderSpec` with the carrier's `StatusVocabulary` and `TrackerPageRules`, an optional API parser returning `TrackingSnapshot`, and the extraction JS; the shared resolver, assembler, and date helpers live in `:source:webview`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "[webview] Remove ScrapedTracking and the transitional adapters; README reflects the shared resolver"
```

---

### Task 14: Finish the branch

- [ ] **Step 1: Review the diff against `main` once**

```bash
git diff main --stat
git log --oneline main..HEAD
```

Confirm no `build.gradle.kts`, `settings.gradle.kts`, or `gradle/gradle-daemon-jvm.properties` changes crept in (the last one sometimes auto-generates; do not commit it).

- [ ] **Step 2: Squash-merge locally, delete the branch, do not push**

```bash
git switch main
git merge --squash refactor/source-consolidation-1
git commit -m "[architecture] Source consolidation increment 1: typed outputs, shared vocabulary, resolver, and assembler

Six carriers copied the same tracker-page ladder, status chain, and date helpers.
ScrapedTracking's string round-trip is gone: parseApi returns TrackingSnapshot and
parseRaw returns PageOutcome. StatusVocabulary (with a negated-delivered lane, a None
base for Amazon, and token humanizing for AMZL), assembleSnapshot, TrackerPageRules /
resolveTrackerPage, and the event and date helpers live in :source:webview; each carrier
keeps its own vocabulary extras, not-found copy, selectors, DTOs, and hooks. Every
captured-page fixture keeps its expected result. Spec:
docs/superpowers/specs/2026-09-07-source-consolidation-design.md"
git branch -D refactor/source-consolidation-1
```

Then stop and report that `main` is ready to push. Do not push.

---

## Self-review against the spec

- **1.1 typed outputs:** Task 5 (spec, router, source, adapters); Task 13 deletes `ScrapedTracking`. `DomExtraction` keeps `page`, `url`, `raw`. Hop-URL check preserved in `routeOutcome`. `backfilledFrom` moved (Task 5).
- **1.2 one engine:** Task 2. Negated lane, `None` base, `classifyToken`. `isMultiStage` applied to every carrier by Task 6's resolver.
- **1.3 assembler and resolver:** Tasks 4 and 6. Shared not-found seeds are the generic phrases from the UPS/USPS/FedEx/AMZL/DHL/Amazon regexes; each carrier's remaining copy is in its `notFound` extras (Tasks 7 to 12). `parsePromiseDate` default; Amazon keeps its gate (Task 12), FedEx its location hook (Task 11).
- **1.4 events and dates:** Tasks 1 and 3. DHL zone table shared (Task 3), consumed in Task 9. NBSP normalization shared (Task 1), consumed by AMZL (Task 10). `parseCompactDate` (Task 1) consumed by UPS (Task 7).
- **1.5 tests:** every carrier task keeps its fixtures and converts assertions; new shared tests in Tasks 1 to 4 and 6.
- **Boundary rule:** no carrier string moves into `:source:webview` except the generic not-found seeds and the negation words; AMZL's tokens, Amazon's phrases, and FedEx's not-found copy stay in their modules.
- **Type consistency:** `PageOutcome.Goto(url, coarse)`, `RouteResult.Goto(url, coarse)`, `RouteResult.Tracking(snapshot)`, `snapshotOrNull()`, `resolveTrackerPage(raw, rules, zone)`, `headlineThenNewestEvent(vocabulary, headline, events)`, `assembleSnapshot(vocabulary, headline, events, etaDate, etaWindow, etaWindowText, location, delayNote, statusFallback)` are used with the same names and parameter order in every task.

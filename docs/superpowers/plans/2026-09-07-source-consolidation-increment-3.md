# Source Consolidation Increment 3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Part B tasks end in a device check that only the controller and Doug can perform; a subagent implements, commits, and stops.**

**Goal:** Every extraction blob shrinks to its selectors and choreography, every carrier gets the page date and page text the shared Kotlin needs, and the last two decisions still made in JavaScript (USPS and Amazon event dates, Amazon's window text) move into tested Kotlin.

**Architecture:** Two parts on two branches. **Part A** is pure Kotlin, verified on the JVM, merged first: the ETA hook widens to take the whole `DomRaw` so Amazon's tracker page joins `resolveTrackerPage`, the resolver looks for a delivery window in the status line before the promise banner, and two small tidy-ups from the increment 2 review land. **Part B** changes the extraction runner that all six carriers share: it injects a `page` helper object, and each blob is migrated one carrier at a time with a device check between tasks, because `commonTest` has no JavaScript engine and a runner bug breaks six carriers at once.

**Tech Stack:** Kotlin Multiplatform 2.4.0, kotlin-test in `commonTest`, Android WebView via androidx.webkit, the `ShipScrape` logcat tracer, a Pixel 9 over USB.

**Spec:** `docs/superpowers/specs/2026-09-07-source-consolidation-design.md` (Boundary rule and Increment 3). The etaDate widening and the Settings ordering come from the increment 2 final review, recorded in the increment 2 ledger rulings.

## Global Constraints

- **Boundary rule (spec):** `source/webview` holds mechanisms and carrier-neutral English. Every selector, URL, carrier regex, and carrier phrase stays in its carrier module. The `page` helpers are mechanism.
- **JS reads, Kotlin decides.** After Part B no blob contains a status vocabulary, date arithmetic, a not-found regex, or a window regex. Login-wall detection stays in the blobs where it depends on selectors (Amazon's sign-in form, UPS's title check).
- **Behavior preserved:** every existing fixture keeps its expected result. On the device, each migrated carrier must produce the same status, ETA, event count, and location as its baseline capture for the same package.
- **Kotlin/Native guard:** the plain-`for`-loop helpers in `WebSource.kt` stay plain loops.
- **Real tracking numbers never enter the repo.** Device logs live in the session scratchpad; QA notes describe outcomes without numbers; fixtures use fabricated numbers of the right shape.
- **Commits:** subject starts with a bracketed tag (`[webview]`, `[amazon]`, `[usps]`, `[fedex]`, `[ups]`, `[dhlecs]`, `[amzl]`, `[ui]`, `[docs]`). No `Co-Authored-By` trailer, no "Generated with Claude Code" footer.
- **Branches:** Part A on `refactor/source-consolidation-3a`, Part B on `refactor/source-consolidation-3b`, each squash-merged locally when done; never push without asking. Both created by the controller.
- **Device:** Pixel 9, serial `44271FDAQ0007J`, fingerprint-locked. The controller never tries to unlock it; Doug drives the screen, the controller reads the tracer. Uninstall nothing; at the end reinstall a clean build of `main`.
- Never stage `gradle/gradle-daemon-jvm.properties`.

**Test commands:**

```bash
# Full suite (end of every task)
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest :source:ups:jvmTest \
          :source:usps:jvmTest :source:fedex:jvmTest :source:amazon:jvmTest \
          :source:amzl:jvmTest :source:dhlecs:jvmTest :source:webview:jvmTest \
          :ui:testAndroidHostTest --console=plain
# Device (Part B, controller-run)
./gradlew :app-android:installDebug --console=plain
adb -s 44271FDAQ0007J logcat -c
adb -s 44271FDAQ0007J logcat -s ShipScrape
```

## File map

| Task | Creates | Modifies | Deletes |
|---|---|---|---|
| A1 | — | `TrackerPageResolver.kt`, `TrackerPageResolverTest.kt`, `AmazonPageLogic.kt`, `AmazonPageLogicTest.kt`, `FedexPageLogic.kt` (hook signature), `FedexPageLogicTest.kt`, `UspsVocabularyTest.kt` | — |
| A2 | — | `ui/.../settings/SettingsViewModel.kt`, `SettingsViewModelTest.kt`, `source/webview/.../PayloadRouterTest.kt` | — |
| B0 | scratchpad baseline logs | — | — |
| B1 | — | `BridgeScripts.kt`, `BridgeScriptsTest.kt`, `FedexWebSpec.kt` | — |
| B2 | — | `UspsWebSpec.kt` | — |
| B3 | — | `AmazonWebSpec.kt`, `AmazonWebSpecTest.kt` | — |
| B4 | — | `UpsWebSpec.kt`, `DhlEcsWebSpec.kt`, `AmzlWebSpec.kt` | — |
| B5 | `docs/superpowers/qa/2026-09-<dd>-increment-3-qa.md` | `SOURCE.md`, `README.md` | — |

---

## Part A: Kotlin, verified on the JVM

### Task A1: The ETA hook sees the whole page; Amazon's tracker joins the shared resolver

**Files:**
- Modify: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/TrackerPageResolver.kt`
- Modify: `source/webview/src/commonTest/.../TrackerPageResolverTest.kt`
- Modify: `source/amazon/src/commonMain/.../AmazonPageLogic.kt`, `source/amazon/src/commonTest/.../AmazonPageLogicTest.kt`
- Modify: `source/fedex/src/commonMain/.../FedexPageLogic.kt` (no code change unless it named the hook type), `source/fedex/src/commonTest/.../FedexPageLogicTest.kt`, `source/usps/src/commonTest/.../UspsVocabularyTest.kt` (call sites of `X_PAGE.etaDate`)

**Interfaces:**
- Produces: `TrackerPageRules.etaDate: (raw: DomRaw, today: LocalDate?) -> LocalDate?` with default `{ raw, today -> parsePromiseDate(raw.etaText, today) }`; `resolveTrackerPage` window text = `raw.etaWindowText ?: findEtaWindowText(raw.statusText) ?: findEtaWindowText(raw.etaText)`; `internal val AMAZON_PAGE: TrackerPageRules`; `parseAmazonRaw` dispatches `"tracker"` to `resolveTrackerPage(raw, AMAZON_PAGE)`; `resolveAmazonTracker` is deleted.

- [ ] **Step 1: Write the failing tests**

`TrackerPageResolverTest.kt`, add:

```kotlin
    @Test fun eta_hook_sees_the_whole_raw_page() {
        val fromHeadline = TrackerPageRules(rules.vocabulary, etaDate = { raw, today -> parseRelativeDay(raw.statusText, today) })
        val out = resolveTrackerPage(tracker(statusText = "Arriving tomorrow", todayIso = "2026-08-18"), fromHeadline, TimeZone.UTC)
        assertEquals(LocalDate(2026, 8, 19), out.snapshotOrNull()?.etaDate)
    }

    @Test fun window_is_read_from_the_status_line_before_the_promise_banner() {
        val out = resolve(tracker(statusText = "Arriving today by 10 PM", etaText = "Between 8 AM and 12 PM tomorrow"))
        assertEquals(LocalTime(22, 0), out.snapshotOrNull()?.etaWindowEnd)
        assertNull(out.snapshotOrNull()?.etaWindowStart)
        val bannerOnly = resolve(tracker(statusText = "In transit", etaText = "Between 8 AM and 12 PM"))
        assertEquals(LocalTime(8, 0), bannerOnly.snapshotOrNull()?.etaWindowStart)
    }
```

`AmazonPageLogicTest.kt`: every `resolveAmazonTracker(raw)` becomes `resolveTrackerPage(raw, AMAZON_PAGE, TimeZone.UTC)` (imports `resolveTrackerPage`, `TimeZone`). Assertions are unchanged: `.snapshotOrNull()?.status` etc. work on the nullable result; `assertIs<PageOutcome.Empty>(resolveTrackerPage(DomRaw(kind = "tracker"), AMAZON_PAGE, TimeZone.UTC))` for the empty case. Add one case that only the shared ladder provides:

```kotlin
    @Test fun a_tracker_page_with_only_a_promise_still_reports_its_eta() {
        val out = resolveTrackerPage(DomRaw(kind = "tracker", etaText = "Arriving tomorrow", todayIso = "2026-08-18"), AMAZON_PAGE, TimeZone.UTC)
        assertEquals(TrackingStatus.UNKNOWN, out.snapshotOrNull()?.status)
        assertEquals(LocalDate(2026, 8, 19), out.snapshotOrNull()?.etaDate)
    }
```

`FedexPageLogicTest.kt` and `UspsVocabularyTest.kt`: every `X_PAGE.etaDate(text, today)` becomes `X_PAGE.etaDate(DomRaw(kind = "tracker", etaText = text), today)`. Twelve call sites; add a private helper in each file if it reads better:

```kotlin
    private fun eta(text: String?, today: LocalDate? = null) = FEDEX_PAGE.etaDate(DomRaw(kind = "tracker", etaText = text), today)
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :source:webview:jvmTest --console=plain --tests '*TrackerPageResolverTest*'`
Expected: compilation FAILS on the hook's parameter types.

- [ ] **Step 3: Implement**

`TrackerPageResolver.kt`:

```kotlin
    /** Reads the delivery promise out of the page. The default reads [DomRaw.etaText]; a carrier
     *  whose promise lives in the status line (Amazon) reads that instead. */
    val etaDate: (raw: DomRaw, today: LocalDate?) -> LocalDate? = { raw, today -> parsePromiseDate(raw.etaText, today) },
```

and in `resolveTrackerPage`:

```kotlin
    val etaDate = rules.etaDate(raw, today) ?: raw.etaDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    // The status line first (Amazon quotes the window there), the promise banner as the fallback.
    val etaWindowText = raw.etaWindowText ?: findEtaWindowText(raw.statusText) ?: findEtaWindowText(raw.etaText)
```

`AmazonPageLogic.kt`: delete `resolveAmazonTracker`; add

```kotlin
/**
 * Tracker page: the shared ladder, with the one thing it cannot know — Amazon writes the promise
 * in the status headline ("Now expected tomorrow by 8 AM"), gated by a promise phrase so a
 * "Delivered June 25" date is never read as an arrival. The promise element, when present,
 * still wins.
 */
internal val AMAZON_PAGE = TrackerPageRules(
    vocabulary = AMAZON_VOCABULARY,
    etaDate = { raw, today -> parseAmazonDay(raw.etaText, today) ?: amazonEtaFromStatus(raw.statusText, today) },
)

internal fun parseAmazonRaw(raw: DomRaw): PageOutcome? = when (raw.kind) {
    "cards" -> resolveAmazonCards(raw)
    "tracker" -> resolveTrackerPage(raw, AMAZON_PAGE)
    else -> null
}
```

Drop the imports `resolveAmazonTracker` needed (`assembleSnapshot`, `headlineThenNewestEvent`, `toTrackingEvent`, `today`, `TimeZone`) if nothing else uses them. `resolveAmazonCards` is unchanged.

- [ ] **Step 4: Run the full suite**

Expected: PASS. Every Amazon tracker fixture must keep its result under the shared ladder. If one fails, report it with the failing output; do not weaken the shared resolver or re-add an Amazon copy of the ladder.

- [ ] **Step 5: Commit**

```bash
git add -A source
git commit -m "[webview] ETA hook sees the whole page; window read from the status line first; Amazon's tracker joins resolveTrackerPage"
```

---

### Task A2: Settings card order follows the carrier list; no carrier in the router test

**Files:**
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/settings/SettingsViewModel.kt`, `ui/src/androidHostTest/.../settings/SettingsViewModelTest.kt`
- Modify: `source/webview/src/commonTest/.../PayloadRouterTest.kt`

- [ ] **Step 1: Write the failing test**

`SettingsViewModelTest.kt`, add a case that builds the registry in reverse order and asserts the cards come out in `WellKnownCarriers.all` order (reuse the file's existing `vm()` construction pattern with its own registry list):

```kotlin
    @Test fun carrier_cards_follow_the_well_known_order_not_registration_order() = runTest {
        val vm = vm(sources = listOf(WebSource(AmazonWebSpec, NoWebScraper), WebSource(UspsWebSpec, NoWebScraper), WebSource(UpsWebSpec, NoWebScraper)))
        val s = awaitState { it.carriers.size == 3 }
        assertEquals(listOf("ups", "usps", "amazon"), s.carriers.map { it.id })
    }
```

If `vm()` takes no parameters today, add an optional `sources: List<TrackingSource>` parameter to it with the current three as the default.

`PayloadRouterTest.spec_defaults_derive_from_the_carrier_and_the_shared_markers`: replace `WellKnownCarriers.UPS` with `Carrier("test", "Test")` (a second synthetic carrier is fine) and `"Pardon Our Interruption"` with `"synthetic marker"`; assert `sourceId == "test"`. Drop the `WellKnownCarriers` import if unused.

- [ ] **Step 2: Implement**

`SettingsViewModel.kt`, where cards are built from `registry.all()`:

```kotlin
        val order = WellKnownCarriers.all.map { it.code }
        val cards = registry.all()
            .sortedBy { order.indexOf(it.descriptor.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .map { src -> ... }
```

- [ ] **Step 3: Run `./gradlew :ui:testAndroidHostTest :source:webview:jvmTest --console=plain`, then the full suite, then commit**

```bash
git add -A ui source/webview
git commit -m "[ui] Settings cards follow WellKnownCarriers order; router test uses a synthetic carrier"
```

---

### Task A3: Finish Part A

- [ ] Full suite green; `./gradlew :source:webview:iosSimulatorArm64Test --console=plain` green.
- [ ] Squash-merge `refactor/source-consolidation-3a` into `main` with subject `[architecture] Source consolidation increment 3a: page-wide ETA hook, Amazon tracker on the shared resolver, Settings order`; delete the branch; do not push.

---

## Part B: the runner, one carrier at a time, against the device

### The `page` helper contract

The runner builds this object before calling the extractor and passes it as the first argument. Extractors are `function(page)` or, for choreography, `function(page, finish)`.

```js
page.bodyText            // document.body.innerText, read once
page.pageText            // first 400 chars of bodyText, whitespace collapsed
page.todayIso            // device-local date, YYYY-MM-DD
page.clean(el)           // trimmed, whitespace-collapsed textContent, or null
page.text(sel, sel, …)   // clean text of the FIRST selector that yields non-empty text, in
                         // argument order — never a comma list, which is document order
page.count(sel)          // querySelectorAll(sel).length, or -1 for a bad selector
page.probe(sel, sel, …)  // {selector: count} for tracer diagnostics
page.raw(kind, fields)   // {page:'raw', raw:{kind, pageText, todayIso, …fields}}, undefined
                         // fields omitted; add why/probe/url on the returned object for the tracer
```

### The device check (controller and Doug; same steps every time)

1. `./gradlew :app-android:installDebug --console=plain`
2. `adb -s 44271FDAQ0007J logcat -c`, then `adb -s 44271FDAQ0007J logcat -s ShipScrape > <scratchpad>/inc3-<task>-<carrier>.log &`
3. Ask Doug to refresh the carrier's package on the phone (pull to refresh on the list, or open More details). Wait for his reply.
4. Read the log. Pass criteria: the `dom` payload routes to `Tracking` (or to the same non-tracking outcome the baseline had); status, ETA date, ETA window, event count, and location match the baseline capture for the same package; no `extractorThrew`, no `asyncTimeout`, no unexpected `challenge`.
5. If a field differs, the log's `raw` payload shows what the blob read; fix the blob (a subagent fix round) and repeat. If the raw fields match the baseline but the Kotlin result differs, the bug is in Part A or increment 1 Kotlin; fix there with a fixture built from the logged strings.
6. Record the outcome in the ledger with no tracking numbers.

Logs may contain tracking numbers; they stay in the scratchpad and are deleted with it.

---

### Task B0: Baseline capture (controller and Doug, no code)

- [ ] Install current `main` (after Part A merged). Run the device check for all six carriers and keep the six logs as the baselines. Note which stage each package is at; a carrier with no package in flight gets a note, not a baseline, and its Part B task is verified against whatever page state it has.

---

### Task B1: The runner injects `page`; FedEx migrates first

FedEx goes first because it is the one extractor declared `function(finish)`, which the new calling convention `(page, finish)` would break; every other blob is `function()` and ignores arguments, so it keeps working untouched until its own task.

**Files:**
- Modify: `source/webview/src/commonMain/.../BridgeScripts.kt`, `source/webview/src/commonTest/.../BridgeScriptsTest.kt`
- Modify: `source/fedex/src/commonMain/.../FedexWebSpec.kt`

- [ ] **Step 1: Write the failing tests**

In `BridgeScriptsTest.kt`, replace `extraction_runner_supports_async_extractors` and add a helper-surface test:

```kotlin
    @Test fun extraction_runner_passes_page_helpers_then_finish() {
        val js = BridgeScripts.extractionRunner(testSpec())
        assertContains(js, "var page = {")
        assertContains(js, "extractor(page, finish)")
        assertContains(js, "if (finished) return")   // post-once guard
        assertContains(js, "asyncTimeout")           // backstop outcome is diagnosable in traces
        assertContains(js, "!== undefined")          // sync return path preserved
    }

    @Test fun page_helpers_expose_the_documented_surface() {
        val js = BridgeScripts.extractionRunner(testSpec())
        for (member in listOf("bodyText:", "pageText:", "todayIso:", "clean: function", "text: function", "count: function", "probe: function", "raw: function")) {
            assertContains(js, member)
        }
        assertContains(js, "slice(0, 400)")                 // pageText length is the shared contract
        assertContains(js, "raw = {kind: kind, pageText: page.pageText, todayIso: page.todayIso}")
    }
```

- [ ] **Step 2: Implement the runner**

In `extractionRunner`, between the `finish` definition and the `try`, add the helper object, and change the call:

```kotlin
  var bodyText = (document.body && document.body.innerText) || '';
  var page = {
    bodyText: bodyText,
    pageText: bodyText.replace(/\s+/g, ' ').slice(0, 400),
    todayIso: (function() {
      var d = new Date();
      return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
    })(),
    clean: function(el) { return el ? el.textContent.replace(/\s+/g, ' ').trim() : null; },
    text: function() {
      for (var i = 0; i < arguments.length; i++) {
        var el = null;
        try { el = document.querySelector(arguments[i]); } catch (e) {}
        var t = page.clean(el);
        if (t) return t;
      }
      return null;
    },
    count: function(sel) { try { return document.querySelectorAll(sel).length; } catch (e) { return -1; } },
    probe: function() {
      var out = {};
      for (var i = 0; i < arguments.length; i++) out[arguments[i]] = page.count(arguments[i]);
      return out;
    },
    raw: function(kind, fields) {
      var raw = {kind: kind, pageText: page.pageText, todayIso: page.todayIso};
      for (var k in fields) if (fields[k] !== undefined) raw[k] = fields[k];
      return {page: 'raw', raw: raw};
    }
  };
```

The challenge-marker loop reads `bodyText.toLowerCase()` instead of re-reading the body, and the extractor call becomes `var r = extractor(page, finish);`. Update the KDoc: the extractor receives `page` then `finish`; a plain `function(page)` finishes synchronously by returning; `function(page, finish)` may return undefined and call `finish` later.

- [ ] **Step 3: Migrate the FedEx blob**

Replace `FEDEX_EXTRACTION_JS` with (selectors unchanged; the header comment above it stays, with "read the summary, click the control…" wording intact):

```js
function(page, finish) {
  var statusText = page.text('.phase3-progress-bar__active-label', '[class*="progress-bar__active-label"]', '[data-test-id="delivery-date-header"]');
  var etaText = page.text('[data-test-id="delivery-date-text"]', '.phase3-view__delivery-date-embed', '[class*="delivery-date-embed"]');
  var locationText = page.text('.phase3-view__current-location', '[class*="current-location"]');
  function readTravelHistory() {
    var events = [];
    var rows = document.querySelectorAll('tr.travel-history-table__row');
    for (var r = 0; r < rows.length; r++) {
      var dateText = page.clean(rows[r].querySelector('td'));
      var evs = rows[r].querySelectorAll('.travel-history__scan-event');
      for (var i = 0; i < evs.length; i++) {
        var kids = evs[i].children;
        var desc = kids[1] ? page.clean(kids[1]) : null;
        if (!desc) continue;
        events.push({whenText: (dateText || '') + ' ' + (kids[0] ? page.clean(kids[0]) : ''),
                     description: desc,
                     location: (kids[2] && page.clean(kids[2])) || null});
      }
    }
    return events;
  }
  function result(events) {
    return page.raw('tracker', {statusText: statusText, etaText: etaText, locationText: locationText, events: events});
  }
  var direct = readTravelHistory();
  if (direct.length) return result(direct);
  var control = null;
  var all = document.body.getElementsByTagName('*');
  for (var p = 0; p < all.length; p++) {
    var t = (all[p].textContent || '').replace(/\s+/g, ' ').trim();
    if (t === 'View more details' && all[p].children.length === 0) { control = all[p]; break; }
  }
  if (!control) {
    var res = result([]);
    if (!statusText && !etaText) {
      res.why = 'noStatusHeadline';
      res.probe = page.probe('.phase3-view', '[class*="progress-bar" i]', '[data-test-id]', 'tr.travel-history-table__row', 'h1');
    }
    return res;
  }
  control.click();
  setTimeout(function() { finish(result(readTravelHistory())); }, 2000);
  return undefined;
}
```

- [ ] **Step 4: Full suite, commit, stop for the device check**

```bash
git add -A source/webview source/fedex
git commit -m "[webview] Extraction runner injects page helpers; FedEx blob reads through them"
```

- [ ] **Device check (controller + Doug): FedEx.** Also confirm one unmigrated carrier (UPS) still scrapes, proving the `(page, finish)` convention did not disturb a zero-argument extractor.

---

### Task B2: USPS reads dates as text

**Files:**
- Modify: `source/usps/src/commonMain/.../UspsWebSpec.kt`

- [ ] **Step 1: Replace `USPS_EXTRACTION_JS`** (selectors unchanged; keep the header comment's selector notes, drop its `Date.parse` paragraph):

```js
function(page) {
  var statusText = page.text('.tb-status', '.delivery_status h2', '.statusSummaryText');
  var events = [];
  var steps = document.querySelectorAll('#trackingHistory .tb-step, .tracking-progress-bar-status-container .tb-step');
  for (var i = 0; i < steps.length; i++) {
    var dateText = page.clean(steps[i].querySelector('.tb-date'));
    var desc = page.clean(steps[i].querySelector('.tb-status-detail')) || page.clean(steps[i].querySelector('.tb-status'));
    if (!dateText || !desc) continue;
    events.push({whenText: dateText, description: desc, location: page.clean(steps[i].querySelector('.tb-location'))});
  }
  return page.raw('tracker', {
    statusText: statusText,
    etaText: page.text('.expected_delivery', '[class*="expected-delivery"]', '.eta_info'),
    events: events
  });
}
```

`whenText` is resolved by the shared `toTrackingEvent`: month-name date plus a clock time in the device zone, which is what `Date.parse` produced before. Ordering is the assembler's.

- [ ] **Step 2: Full suite, commit, stop for the device check**

```bash
git add -A source/usps
git commit -m "[usps] Blob reads through the page helpers; event dates resolve in Kotlin"
```

- [ ] **Device check: USPS.** The baseline's event count and timestamps must match. If the `.tb-date` text has a shape `toTrackingEvent` cannot read, the log shows it verbatim; add that shape to `PageDates` with a test built from the logged string (carrier-neutral date text is a shared mechanism) and re-check.

---

### Task B3: Amazon reads dates and windows as text

**Files:**
- Modify: `source/amazon/src/commonMain/.../AmazonWebSpec.kt`, `source/amazon/src/commonTest/.../AmazonWebSpecTest.kt`

- [ ] **Step 1: Update the spec tests first**

In `AmazonWebSpecTest.kt`: delete `extraction_js_treats_overnight_as_a_relative_day_word` (the word now lives in `parseRelativeDay`, covered by `PageDatesTest`) and `extraction_js_emits_raw_eta_window_text` (the window is found by the shared resolver). Keep `extraction_js_decides_nothing_it_only_reads`, `extraction_js_targets_the_data_component_order_details_layout`, `extraction_js_reports_why_it_gave_up`, and `extraction_js_is_a_function_expression_covering_both_pages`; the last one's `kind: 'cards'` assertion becomes `page.raw('cards'`. Add:

```kotlin
    @Test fun extraction_js_has_no_date_arithmetic() {
        assertFalse(AmazonWebSpec.extractionJs.contains("new Date("))
        assertFalse(AmazonWebSpec.extractionJs.contains("Date.parse"))
        assertFalse(AmazonWebSpec.extractionJs.contains("getFullYear"))
    }
```

- [ ] **Step 2: Replace `AMAZON_EXTRACTION_JS`** (selectors unchanged; keep the header comment's QA notes about `data-component`):

```js
function(page) {
  var href = location.href;
  if (/\/ap\/signin/.test(href) || document.querySelector('form[name="signIn"], #ap_email, #signInSubmit')) return {page: 'loginWall'};

  if (/progress-tracker|ship-track/.test(href)) {
    var statusText = page.text('#primaryStatus', '[class*="pt-status-main"]', '#shipment-status-container h1', '.promise-slot h1');
    var events = [];
    var container = document.querySelector('#tracking-events-container') || document.body;
    var nodes = container.querySelectorAll('[class*="tracking-event"]');
    var day = null;
    for (var i = 0; i < nodes.length; i++) {
      var cls = '' + nodes[i].className;
      if (cls.indexOf('date-header') >= 0) { day = page.clean(nodes[i]); continue; }
      var msg = page.clean(nodes[i].querySelector('[class*="event-message"], .tracking-event-message'));
      if (!msg || !day) continue;
      var timeText = page.clean(nodes[i].querySelector('[class*="event-time"], .tracking-event-time'));
      events.push({whenText: day + ' ' + (timeText || ''),
                   description: msg,
                   location: page.clean(nodes[i].querySelector('[class*="event-location"], .tracking-event-location'))});
    }
    var r = page.raw('tracker', {
      statusText: statusText,
      etaText: page.text('[class*="promise"]', '#expected-delivery-date'),
      events: events
    });
    if (!statusText && !events.length) {
      r.why = 'trackerNoStatusNoEvents';
      r.detail = 'nodes=' + nodes.length;
      r.probe = page.probe('#primaryStatus', '[class*="tracking-event"]', '[class*="promise"]');
    }
    return r;
  }

  var cards = document.querySelectorAll('[data-component="shipmentCard"]');
  if (!cards.length) cards = document.querySelectorAll('.shipment, [class*="shipment-info-container"], [data-component="shipments"] .a-box');
  var out = [];
  for (var k = 0; k < cards.length; k++) {
    var head = page.clean(cards[k].querySelector('[data-component="shipmentStatus"], .shipment-top-row, [class*="shipment-status"], h4, h5')) || '';
    if (!head) head = (('' + (cards[k].innerText || '')).split('\n')[0] || '').trim();
    var link = cards[k].querySelector('a[href*="progress-tracker"], a[href*="ship-track"]');
    out.push({head: head, href: (link && link.href) ? link.href : null});
  }
  var c = page.raw('cards', {cards: out});
  if (!out.length) {
    c.why = 'noShipmentCards';
    c.url = href;
    c.probe = page.probe('.shipment', '[class*="shipment-info-container"]', '[data-component="shipments"] .a-box', '[data-component]', '.a-box', 'a[href*="progress-tracker"]', 'a[href*="ship-track"]');
    var dc = document.querySelectorAll('[data-component]');
    var names = [];
    for (var n = 0; n < dc.length && names.length < 20; n++) {
      var v = dc[n].getAttribute('data-component');
      if (v && names.indexOf(v) < 0) names.push(v);
    }
    c.probe.dataComponents = names;
  }
  return c;
}
```

Event date headers ("Today", "Yesterday", "Tuesday, July 15") plus the row's time now travel as `whenText`; `toTrackingEvent` resolves them against `todayIso`, which `page.raw` always sends. The window is found by the resolver from the status line first, then the promise element, exactly the order the old JS used.

- [ ] **Step 3: Full suite, commit, stop for the device check**

```bash
git add -A source/amazon
git commit -m "[amazon] Blob reads through the page helpers; event dates and the delivery window resolve in Kotlin"
```

- [ ] **Device check: Amazon** (Doug signs in on the phone first). Both pages: an order-details hop and the tracker page. Event count and dates must match the baseline; the ETA window must match.

---

### Task B4: UPS, DHL eCommerce, AMZL (batched; same shape)

**Files:**
- Modify: `source/ups/.../UpsWebSpec.kt`, `source/dhlecs/.../DhlEcsWebSpec.kt`, `source/amzl/.../AmzlWebSpec.kt`

- [ ] **Step 1: Replace the three blobs** (selectors unchanged; header comments stay):

UPS:
```js
function(page) {
  if (/log in|sign in to view/i.test(page.bodyText) && !/track/i.test(document.title)) return {page: 'loginWall'};
  return page.raw('tracker', {statusText: page.text('#stApp_txtPackageStatus', '[id*="PackageStatus"]', '.ups-tracking_status')});
}
```

DHL eCommerce:
```js
function(page) {
  var statusText = page.text('.list-status', 'main h1', 'h1');
  var r = page.raw('tracker', {statusText: statusText});
  if (!statusText) { r.why = 'noStatusHeadline'; r.url = location.href; r.probe = page.probe('.list-status', '[class*="shipment-status"]', '.no-result-found-msg', 'h1', '#root *'); }
  return r;
}
```

AMZL:
```js
function(page) {
  var statusText = page.text('#primaryStatus', '[class*="pt-status"]', '[class*="trackingStatus"]', '[class*="status-main"]', 'main h1', 'h1');
  var r = page.raw('tracker', {statusText: statusText});
  if (!statusText) { r.why = 'noStatusHeadline'; r.url = location.href; r.probe = page.probe('#primaryStatus', '[class*="status"]', 'main h1', 'h1', '[data-testid]'); }
  return r;
}
```

Note the UPS blob used a comma list for its three status selectors before; priority order is the same selectors in the same sequence.

- [ ] **Step 2: Full suite, commit, stop for the device check**

```bash
git add -A source/ups source/dhlecs source/amzl
git commit -m "[webview] UPS, DHL eCommerce, and AMZL blobs read through the page helpers"
```

- [ ] **Device check: UPS, DHL eCommerce, AMZL** (one refresh each). These three get `todayIso` for the first time; a relative-day promise on any of them now resolves, which the log will show as an ETA the baseline lacked. That is the intended improvement, not a regression.

---

### Task B5: QA note, docs, finish Part B

**Files:**
- Create: `docs/superpowers/qa/2026-09-<dd>-increment-3-qa.md` (controller-written from the ledger: per carrier, package stage, outcome, any selector or date-shape fix made; no tracking numbers)
- Modify: `SOURCE.md` section 3c (extractor signature `function(page)` / `function(page, finish)`, the `page` helper table, `page.raw`, and the example blob rewritten through the helpers); `README.md` "Running tests" count if it changed

- [ ] Full suite green; `./gradlew :source:webview:iosSimulatorArm64Test --console=plain` green.
- [ ] Squash-merge `refactor/source-consolidation-3b` into `main` with subject `[architecture] Source consolidation increment 3b: page helpers in the extraction runner; USPS and Amazon dates and windows decided in Kotlin`; delete the branch; do not push.
- [ ] Reinstall a clean build of `main` on the Pixel: `./gradlew :app-android:installDebug --console=plain`.

---

## Self-review against the spec

- **Increment 3, helpers:** B1 defines the object the spec lists (`bodyText`, `pageText`, `todayIso`, `clean`, `text`, `count`, `probe`, `raw`) and the `function(page, finish)` signature with the same sync/deferred semantics and backstop.
- **Increment 3, universal `todayIso`/`pageText`:** `page.raw` always sends both; B2 to B4 migrate every blob onto it.
- **Increment 3, decisions out of JS:** USPS `Date.parse` (B2) and Amazon `parseDay` (B3) become `whenText`; Amazon's `windowText` becomes the resolver's `findEtaWindowText` (A1 orders it status line first, as the JS did).
- **Increment 3, login walls stay in JS:** UPS's and Amazon's checks are kept verbatim.
- **Increment 3, device pass:** every Part B task ends in one; B0 gives it a baseline.
- **Carry-overs from the increment 2 review:** A1 (ETA hook on `DomRaw`, Amazon tracker on the shared resolver), A2 (Settings order, router test carrier). The shared API-event helper stays deferred; four parsers hand-roll different field shapes and no fixture motivates it yet.
- **Sequencing rule honored:** Part A merges alone and `main` stays green if Part B is postponed; Part B is one branch so a half-migrated runner never reaches `main`.
- **Type consistency:** `TrackerPageRules.etaDate(raw: DomRaw, today: LocalDate?)`, `AMAZON_PAGE`, `page.raw(kind, fields)`, `page.text(...)`, `page.probe(...)` are used with the same names in every task.

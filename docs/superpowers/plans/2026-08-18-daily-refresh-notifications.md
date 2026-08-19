# Daily Refresh + Status-Change Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh every undelivered parcel once a day at a user-chosen local time (default 8:00 AM) with the app closed, and post one grouped notification per parcel whose status or ETA date changed.

**Architecture:** All decision logic — candidate selection, change detection, notification copy, sign-in-nag dedup — lives in `data/commonMain` behind two thin platform interfaces (`StatusNotifier`, `DailyRefreshScheduler`), following the existing `WebScraper`/`NoWebScraper` pattern. Android actuals use WorkManager self-rescheduling one-time work plus `NotificationManagerCompat`; iOS actuals use `BGTaskScheduler` plus `UNUserNotificationCenter`. Permission requesting stays in `ui` because it needs a foreground Activity.

**Tech Stack:** Kotlin Multiplatform 2.4, Compose Multiplatform 1.11.1, Koin 4.2, Room 3, DataStore Preferences, kotlinx-datetime 0.8.0, AndroidX WorkManager, Navigation 3.

**Spec:** `docs/superpowers/specs/2026-08-18-daily-refresh-notifications-design.md`

## Global Constraints

- Module dependency direction is one-way and must not be violated: `source/*` → `source/api` → `domain`; `data` → `source/api`; `design` → `domain`; `ui` wires everything. **`data` must never import from `ui`.**
- Every new shared type goes in `data/commonMain` unless it needs a platform API.
- Platform implementations are bound in `platformDataModule()` per platform — Android, iOS, **and JVM** (the JVM target must compile and gets no-ops).
- Notification copy is defined once in `data/commonMain` (`NotificationText`) so Android and iOS say the same thing.
- The daily toggle defaults **off**. `dailyUpdateTime` defaults to `LocalTime(8, 0)`.
- Daily run calls `repository.refreshAll(force = true)` — the foreground `RefreshFrequency` setting must not be able to suppress it.
- `FailureReason.AUTH` is the sign-in signal. One nag per source per expiry episode, cleared by that source's next success.
- No `Co-Authored-By` trailer, no "Generated with Claude Code" footer in any commit.
- Commit subjects start with a bracketed tag: `[data]`, `[android]`, `[iOS]`, `[ui]`, `[docs]`, `[deps]`.
- Work happens on branch `feat/daily-refresh-notifications` (already created).

**Test commands** (the whole suite, used at the end of most tasks):

```bash
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest :source:ups:jvmTest \
          :source:usps:jvmTest :source:webview:jvmTest :ui:testAndroidHostTest --console=plain
```

Note `:ui`'s task is `testAndroidHostTest`, **not** `testDebugUnitTest`.

---

### Task 1: Move shared display copy into `domain`

Notification bodies must read exactly like the on-screen copy ("Out for delivery", "Tue, Aug 19"). Those formatters currently live in `ui/util`, which `data` may not depend on. Moving them to `domain` (no dependencies, already owns `LocalDate`/`LocalTime`) lets both consume one definition instead of drifting copies. Pure refactor — no behavior change.

**Files:**
- Create: `domain/src/commonMain/kotlin/com/dgmltn/shiphappens/domain/Display.kt`
- Create: `domain/src/commonTest/kotlin/com/dgmltn/shiphappens/domain/DisplayTest.kt`
- Delete: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/util/Formatters.kt`
- Delete: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/util/TrackingSteps.kt`
- Delete: `ui/src/androidHostTest/kotlin/com/dgmltn/shiphappens/ui/util/FormattersTest.kt`
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/detail/DetailViewModel.kt:11-14` (imports)
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/list/ListViewModel.kt:15-16` (imports)
- Modify: `ui/src/androidHostTest/kotlin/com/dgmltn/shiphappens/ui/detail/DetailViewModelTest.kt:21-22` (imports)

**Interfaces:**
- Consumes: nothing.
- Produces: `com.dgmltn.shiphappens.domain.designFormat(): String` on `LocalDate`; `design12h(): String` on `LocalTime`; `formatEtaWindow(start: LocalTime?, end: LocalTime?): String?`; `TRACKING_STEP_LABELS: List<String>`.

- [ ] **Step 1: Create the moved file verbatim**

Create `domain/src/commonMain/kotlin/com/dgmltn/shiphappens/domain/Display.kt` — same bodies as the two files being deleted, new package:

```kotlin
package com.dgmltn.shiphappens.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

private val WD = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MO = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * Display labels for the 5-step timeline, indexed by `TrackingStatus.stepIndex` /
 * `Parcel.effectiveStepIndex`. Shared by the list row status, the detail timeline, and
 * notification copy so every surface names the current step identically.
 */
val TRACKING_STEP_LABELS = listOf("Label created", "Shipped", "In transit", "Out for delivery", "Delivered")

// kotlinx-datetime 0.8.0: monthNumber/dayOfMonth are deprecated in favor of month.number/day.
fun LocalDate.designFormat(): String = "${WD[dayOfWeek.isoDayNumber - 1]}, ${MO[month.number - 1]} $day"

private fun LocalTime.clock12(): String {
    val h12 = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
    return "$h12:${minute.toString().padStart(2, '0')}"
}

private fun LocalTime.meridiem(): String = if (hour < 12) "AM" else "PM"

fun LocalTime.design12h(): String = "${clock12()} ${meridiem()}"

/**
 * Renders an ETA delivery window.
 *
 * - both set   -> "3:00 – 5:00 PM" (meridiem collapsed when shared, else "11:30 AM – 1:30 PM")
 * - end only   -> "by 8:00 PM"
 * - no end     -> null (a start without an end is degenerate; no carrier produces it)
 */
fun formatEtaWindow(start: LocalTime?, end: LocalTime?): String? {
    if (end == null) return null
    if (start == null) return "by ${end.design12h()}"
    val startText = if (start.meridiem() == end.meridiem()) start.clock12() else start.design12h()
    return "$startText – ${end.design12h()}"
}
```

- [ ] **Step 2: Move the test**

Create `domain/src/commonTest/kotlin/com/dgmltn/shiphappens/domain/DisplayTest.kt` with the contents of the deleted `FormattersTest.kt`, changing only the package to `com.dgmltn.shiphappens.domain` and adding one case for `designFormat`:

```kotlin
package com.dgmltn.shiphappens.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.*

class DisplayTest {
    @Test fun window_with_shared_meridiem_collapses() {
        assertEquals("3:00 – 5:00 PM", formatEtaWindow(LocalTime(15, 0), LocalTime(17, 0)))
    }

    @Test fun window_crossing_meridiem_keeps_both() {
        assertEquals("11:30 AM – 1:30 PM", formatEtaWindow(LocalTime(11, 30), LocalTime(13, 30)))
    }

    @Test fun end_only_reads_as_by() {
        assertEquals("by 8:00 PM", formatEtaWindow(null, LocalTime(20, 0)))
    }

    @Test fun no_end_is_null() {
        assertNull(formatEtaWindow(null, null))
        assertNull(formatEtaWindow(LocalTime(15, 0), null))
    }

    @Test fun midnight_and_noon() {
        assertEquals("12:00 AM – 12:00 PM", formatEtaWindow(LocalTime(0, 0), LocalTime(12, 0)))
    }

    @Test fun design12h_output_is_unchanged() {
        assertEquals("9:05 AM", LocalTime(9, 5).design12h())
        assertEquals("12:00 PM", LocalTime(12, 0).design12h())
        assertEquals("12:00 AM", LocalTime(0, 0).design12h())
    }

    @Test fun designFormat_is_weekday_month_day() {
        assertEquals("Wed, Aug 19", LocalDate(2026, 8, 19).designFormat())
    }
}
```

- [ ] **Step 3: Delete the old files and repoint imports**

```bash
rm ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/util/Formatters.kt \
   ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/util/TrackingSteps.kt \
   ui/src/androidHostTest/kotlin/com/dgmltn/shiphappens/ui/util/FormattersTest.kt
grep -rln "com.dgmltn.shiphappens.ui.util.\(design12h\|designFormat\|formatEtaWindow\|TRACKING_STEP_LABELS\)" \
  --include="*.kt" ui | xargs sed -i '' 's/com\.dgmltn\.shiphappens\.ui\.util\.\(design12h\|designFormat\|formatEtaWindow\|TRACKING_STEP_LABELS\)/com.dgmltn.shiphappens.domain.\1/'
```

`DetailViewModel.kt` and `ListViewModel.kt` already import `com.dgmltn.shiphappens.domain.*` in some form — if the sed leaves a duplicate import of a name already covered by a star import, Kotlin accepts it; leave it.

- [ ] **Step 4: Run the suite to verify the refactor is behavior-neutral**

Run the full test command above.
Expected: PASS, with `:domain:jvmTest` count up by 7 and `:ui:testAndroidHostTest` count down by 6.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "[domain] Move display formatters and step labels out of ui so data can share them"
```

---

### Task 2: `ParcelChange` and change-reporting refresh

**Files:**
- Create: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/ParcelChange.kt`
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/ParcelRepository.kt` (`RefreshSummary`, `applySnapshot`, `RefreshOutcome`, `refreshRow`, `refreshAll`)
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/ParcelChangeTest.kt`

**Interfaces:**
- Consumes: `TRACKING_STEP_LABELS` (Task 1) — not directly here, but the same `TrackingStatus` vocabulary.
- Produces:
  - `data class ParcelChange(parcelId: String, parcelName: String, statusBefore: TrackingStatus, statusAfter: TrackingStatus, etaBefore: LocalDate?, etaAfter: LocalDate?)` with `statusChanged`, `etaChanged`, `isNotable` computed properties.
  - `RefreshSummary(attempted: Int, failed: Int, firstFailureReason: FailureReason? = null, changes: List<ParcelChange> = emptyList(), authFailedSourceIds: Set<String> = emptySet(), succeededSourceIds: Set<String> = emptySet())`.
  - `ParcelRepository.applySnapshot(...)` keeps returning `Boolean`.

- [ ] **Step 1: Write the failing test**

Create `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/ParcelChangeTest.kt`:

```kotlin
package com.dgmltn.shiphappens.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.FakeSource
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import kotlin.test.*

class ParcelChangeTest {
    private lateinit var settings: SettingsRepository

    private fun repo(scope: CoroutineScope, vararg sources: TrackingSource): ParcelRepository {
        val dir = kotlin.io.path.createTempDirectory("change").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        return ParcelRepository(db.parcelDao(), SourceRegistry(sources.toList(), settings), settings, FixedClock())
    }

    private suspend fun enable(id: String) = settings.setSourceConfig(id, SourceConfig(enabled = true))

    @Test fun status_transition_is_notable() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = repo(scope, src)
        enable("u")
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        r.refreshAll(force = true)  // UNKNOWN -> IN_TRANSIT

        src.trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY))
        val summary = r.refreshAll(force = true)

        val change = summary.changes.single { it.parcelId == added.parcel.id }
        assertEquals(TrackingStatus.IN_TRANSIT, change.statusBefore)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, change.statusAfter)
        assertTrue(change.statusChanged)
        assertTrue(change.isNotable)
        assertEquals("Keyboard", change.parcelName)
    }

    @Test fun identical_snapshot_is_not_notable() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.refreshAll(force = true)

        val summary = r.refreshAll(force = true)

        assertEquals(1, summary.changes.size)
        assertFalse(summary.changes.single().isNotable)
    }

    @Test fun eta_date_shift_is_notable_without_status_change() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(
                TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 8, 19))))
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.refreshAll(force = true)

        src.trackResult = SourceResult.Success(
            TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 8, 21)))
        val change = r.refreshAll(force = true).changes.single()

        assertFalse(change.statusChanged)
        assertTrue(change.etaChanged)
        assertTrue(change.isNotable)
        assertEquals(LocalDate(2026, 8, 19), change.etaBefore)
        assertEquals(LocalDate(2026, 8, 21), change.etaAfter)
    }

    @Test fun unknown_status_after_is_never_notable() {
        val change = ParcelChange("p1", "Keyboard", TrackingStatus.UNKNOWN, TrackingStatus.UNKNOWN, null, null)
        assertFalse(change.isNotable)
    }

    @Test fun auth_failure_is_reported_with_its_source_id() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Failure(FailureReason.AUTH, "session expired"))
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)

        val summary = r.refreshAll(force = true)

        assertEquals(setOf("u"), summary.authFailedSourceIds)
        assertTrue(summary.changes.isEmpty())
        assertEquals(1, summary.failed)
    }

    @Test fun success_reports_its_source_id_for_nag_clearing() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS)
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)

        assertEquals(setOf("u"), r.refreshAll(force = true).succeededSourceIds)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :data:jvmTest --tests "*ParcelChangeTest*" --console=plain`
Expected: FAIL — compilation error, `ParcelChange` unresolved.

- [ ] **Step 3: Create `ParcelChange`**

Create `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/ParcelChange.kt`:

```kotlin
package com.dgmltn.shiphappens.data

import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate

/**
 * What one refresh actually changed about a parcel. Produced by every successful refresh;
 * only [isNotable] ones are worth a notification (see DailyRefreshRunner).
 */
data class ParcelChange(
    val parcelId: String,
    val parcelName: String,
    val statusBefore: TrackingStatus,
    val statusAfter: TrackingStatus,
    val etaBefore: LocalDate?,
    val etaAfter: LocalDate?,
) {
    val statusChanged: Boolean get() = statusBefore != statusAfter
    val etaChanged: Boolean get() = etaBefore != etaAfter

    /**
     * Worth telling the user about: a real status transition, or a moved delivery date.
     * The UNKNOWN/null guards are belt-and-braces — applySnapshot never writes UNKNOWN over a
     * known status and never nulls an existing etaDate — but they keep a future source that
     * regresses a parcel from waking anyone at 8am.
     */
    val isNotable: Boolean get() =
        (statusChanged && statusAfter != TrackingStatus.UNKNOWN) || (etaChanged && etaAfter != null)
}
```

- [ ] **Step 4: Make the repository report changes**

In `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/ParcelRepository.kt`:

Replace the `RefreshSummary` declaration:

```kotlin
data class RefreshSummary(
    val attempted: Int,
    val failed: Int,
    val firstFailureReason: FailureReason? = null,
    /** Every successful refresh's diff, notable or not. */
    val changes: List<ParcelChange> = emptyList(),
    /** Sources that failed with AUTH — their session needs re-establishing. */
    val authFailedSourceIds: Set<String> = emptySet(),
    /** Sources that refreshed at least one parcel successfully — clears a stale sign-in nag. */
    val succeededSourceIds: Set<String> = emptySet(),
)
```

Split `applySnapshot` so the diff is available internally while the public signature is unchanged. Replace the whole existing `applySnapshot` function with:

```kotlin
    /**
     * Persist a snapshot produced OUTSIDE the normal track() path (e.g. the visible web view's
     * scrape-on-view). Identical merge semantics to a successful refresh: UNKNOWN status and null
     * fields never clobber existing values, events replace wholesale when non-empty, and the
     * parcel is pinned to [sourceId]. Returns false when the parcel no longer exists.
     */
    suspend fun applySnapshot(id: String, snapshot: TrackingSnapshot, sourceId: String): Boolean =
        applyAndDiff(id, snapshot, sourceId) != null

    /** [applySnapshot] plus the before/after diff. Null ONLY when the row doesn't exist. */
    private suspend fun applyAndDiff(id: String, snapshot: TrackingSnapshot, sourceId: String): ParcelChange? {
        val row = dao.getById(id) ?: return null
        // etaWindowStart/etaWindowEnd are two halves of one value, so they're merged as a unit:
        // if the snapshot carries either bound, take both from the snapshot (a start-less
        // snapshot clears a stored start rather than leaving it paired with a new end); only
        // when the snapshot carries neither bound do we preserve the existing row's window.
        val newWindow = snapshot.etaWindowStart != null || snapshot.etaWindowEnd != null
        val updated = row.parcel.copy(
            status = if (snapshot.status == TrackingStatus.UNKNOWN) row.parcel.status else snapshot.status.name,
            etaDate = snapshot.etaDate?.toString() ?: row.parcel.etaDate,
            etaWindowStart = if (newWindow) snapshot.etaWindowStart?.toString() else row.parcel.etaWindowStart,
            etaWindowEnd = if (newWindow) snapshot.etaWindowEnd?.toString() else row.parcel.etaWindowEnd,
            latestLocation = snapshot.latestLocation ?: row.parcel.latestLocation,
            sourceId = sourceId,
            lastRefreshedAt = clock.now().toEpochMilliseconds(),
        )
        dao.upsertParcel(updated)
        if (snapshot.events.isNotEmpty()) dao.replaceEvents(id, snapshot.events.map { it.toEntity(id) })
        return ParcelChange(
            parcelId = id,
            parcelName = updated.name,
            statusBefore = row.parcel.status.toTrackingStatus(),
            statusAfter = updated.status.toTrackingStatus(),
            etaBefore = row.parcel.etaDate?.let(LocalDate::parse),
            etaAfter = updated.etaDate?.let(LocalDate::parse),
        )
    }

    private fun String.toTrackingStatus(): TrackingStatus =
        runCatching { TrackingStatus.valueOf(this) }.getOrDefault(TrackingStatus.UNKNOWN)
```

Add `import kotlinx.datetime.LocalDate` to the file's imports (`com.dgmltn.shiphappens.domain.*` is already star-imported, and `TrackingStatus` comes from there).

Widen `RefreshOutcome` and `refreshRow`:

```kotlin
    private sealed interface RefreshOutcome {
        data class Success(val change: ParcelChange?, val sourceId: String) : RefreshOutcome
        data object NoSource : RefreshOutcome
        data class Failed(val reason: FailureReason, val sourceId: String?) : RefreshOutcome
    }

    private suspend fun refreshRow(id: String): RefreshOutcome {
        val row = dao.getById(id) ?: return RefreshOutcome.Failed(FailureReason.UNKNOWN, null)
        val parcel = row.toDomain()
        val source = registry.sourceFor(parcel) ?: return RefreshOutcome.NoSource
        _refreshingIds.update { it + id }
        try {
            // Guard against a misbehaving source implementation throwing instead of returning
            // SourceResult.Failure — see TrackingSource.track KDoc.
            val result = runCatching { source.track(parcel.trackingNumber, parcel.carrier) }
                .getOrElse { SourceResult.Failure(FailureReason.UNKNOWN, it.message) }
            return when (result) {
                is SourceResult.Failure -> RefreshOutcome.Failed(result.reason, source.descriptor.id)
                is SourceResult.Success ->
                    RefreshOutcome.Success(applyAndDiff(id, result.value, source.descriptor.id), source.descriptor.id)
            }
        } finally {
            _refreshingIds.update { it - id }
        }
    }
```

`refresh(id)` still compiles unchanged (`refreshRow(id) is RefreshOutcome.Success`).

Accumulate in `refreshAll` — replace the loop and return:

```kotlin
        var failed = 0
        var firstReason: FailureReason? = null
        val changes = mutableListOf<ParcelChange>()
        val authFailed = mutableSetOf<String>()
        val succeeded = mutableSetOf<String>()
        for (e in candidates) {
            when (val outcome = refreshRow(e.id)) {
                is RefreshOutcome.Success -> {
                    outcome.change?.let(changes::add)
                    succeeded += outcome.sourceId
                }
                is RefreshOutcome.Failed -> {
                    failed++
                    if (firstReason == null) firstReason = outcome.reason
                    if (outcome.reason == FailureReason.AUTH && outcome.sourceId != null) {
                        authFailed += outcome.sourceId
                    }
                }
                RefreshOutcome.NoSource -> Unit
            }
        }
        return RefreshSummary(candidates.size, failed, firstReason, changes, authFailed, succeeded)
```

- [ ] **Step 5: Run the new test and the existing data suite**

Run: `./gradlew :data:jvmTest --console=plain`
Expected: PASS — 6 new tests, and `ParcelRepositoryTest` / `ApplySnapshotTest` unchanged and still green.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "[data] Report per-parcel status/ETA diffs and per-source outcomes from refreshAll"
```

---

### Task 3: Notification copy

**Files:**
- Create: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/notify/NotificationText.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/notify/NotificationTextTest.kt`

**Interfaces:**
- Consumes: `ParcelChange` (Task 2); `TRACKING_STEP_LABELS`, `LocalDate.designFormat()` (Task 1); `TrackingStatus.stepIndex` (existing, `domain/Tracking.kt`).
- Produces: `object NotificationText { fun title(change: ParcelChange): String; fun body(change: ParcelChange): String; fun signInTitle(sourceDisplayName: String): String; fun signInBody(sourceDisplayName: String): String }`.

- [ ] **Step 1: Write the failing test**

Create `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/notify/NotificationTextTest.kt`:

```kotlin
package com.dgmltn.shiphappens.data.notify

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlin.test.*

class NotificationTextTest {
    private fun change(
        before: TrackingStatus = TrackingStatus.IN_TRANSIT,
        after: TrackingStatus = TrackingStatus.OUT_FOR_DELIVERY,
        etaBefore: LocalDate? = null,
        etaAfter: LocalDate? = null,
    ) = ParcelChange("p1", "Nike shoes", before, after, etaBefore, etaAfter)

    @Test fun title_is_the_parcel_name() {
        assertEquals("Nike shoes", NotificationText.title(change()))
    }

    @Test fun status_change_body_uses_the_shared_step_label() {
        assertEquals("Out for delivery", NotificationText.body(change()))
    }

    @Test fun status_change_with_eta_appends_the_arrival_date() {
        val c = change(etaBefore = LocalDate(2026, 8, 19), etaAfter = LocalDate(2026, 8, 19))
        assertEquals("Out for delivery · arriving Wed, Aug 19", NotificationText.body(c))
    }

    @Test fun delivered_reads_as_delivered() {
        assertEquals("Delivered", NotificationText.body(change(after = TrackingStatus.DELIVERED)))
    }

    @Test fun exception_has_its_own_wording() {
        assertEquals("Delivery exception — check the carrier",
            NotificationText.body(change(after = TrackingStatus.EXCEPTION)))
    }

    @Test fun eta_only_change_reads_as_a_moved_date() {
        val c = change(before = TrackingStatus.IN_TRANSIT, after = TrackingStatus.IN_TRANSIT,
            etaBefore = LocalDate(2026, 8, 19), etaAfter = LocalDate(2026, 8, 21))
        assertEquals("Now arriving Fri, Aug 21 (was Wed, Aug 19)", NotificationText.body(c))
    }

    @Test fun eta_appearing_for_the_first_time_omits_the_was_clause() {
        val c = change(before = TrackingStatus.IN_TRANSIT, after = TrackingStatus.IN_TRANSIT,
            etaBefore = null, etaAfter = LocalDate(2026, 8, 21))
        assertEquals("Now arriving Fri, Aug 21", NotificationText.body(c))
    }

    @Test fun sign_in_copy_names_the_source() {
        assertEquals("Sign in to UPS", NotificationText.signInTitle("UPS"))
        assertEquals("Ship Happens can't update your UPS packages until you sign in again.",
            NotificationText.signInBody("UPS"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :data:jvmTest --tests "*NotificationTextTest*" --console=plain`
Expected: FAIL — `NotificationText` unresolved.

- [ ] **Step 3: Implement**

Create `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/notify/NotificationText.kt`:

```kotlin
package com.dgmltn.shiphappens.data.notify

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.domain.TRACKING_STEP_LABELS
import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.domain.designFormat
import com.dgmltn.shiphappens.domain.stepIndex

/**
 * Every user-facing string the daily run can post. Shared by the Android and iOS notifiers so
 * both platforms say the same thing, and unit-testable without either.
 */
object NotificationText {

    fun title(change: ParcelChange): String = change.parcelName

    fun body(change: ParcelChange): String = when {
        change.statusChanged -> statusLine(change)
        else -> etaLine(change)
    }

    fun signInTitle(sourceDisplayName: String): String = "Sign in to $sourceDisplayName"

    fun signInBody(sourceDisplayName: String): String =
        "Ship Happens can't update your $sourceDisplayName packages until you sign in again."

    private fun statusLine(change: ParcelChange): String {
        val label = statusLabel(change.statusAfter)
        // A delivered parcel's ETA is history; anything still moving benefits from the date.
        val eta = change.etaAfter
        return if (eta != null && change.statusAfter != TrackingStatus.DELIVERED) {
            "$label · arriving ${eta.designFormat()}"
        } else {
            label
        }
    }

    private fun etaLine(change: ParcelChange): String {
        val after = change.etaAfter?.designFormat() ?: return statusLabel(change.statusAfter)
        val before = change.etaBefore?.designFormat()
        return if (before == null) "Now arriving $after" else "Now arriving $after (was $before)"
    }

    private fun statusLabel(status: TrackingStatus): String = when (status) {
        TrackingStatus.EXCEPTION -> "Delivery exception — check the carrier"
        TrackingStatus.UNKNOWN -> "Updated"
        else -> TRACKING_STEP_LABELS[status.stepIndex]
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :data:jvmTest --tests "*NotificationTextTest*" --console=plain`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "[data] Add shared notification copy for status and ETA changes"
```

---

### Task 4: Daily-update settings

**Files:**
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/settings/SettingsRepository.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/settings/SettingsRepositoryTest.kt` (append)

**Interfaces:**
- Produces: `AppSettings.dailyUpdateEnabled: Boolean`, `AppSettings.dailyUpdateTime: LocalTime`, `AppSettings.signInNaggedSourceIds: Set<String>`; `SettingsRepository.setDailyUpdateEnabled(Boolean)`, `setDailyUpdateTime(LocalTime)`, `setSignInNaggedSourceIds(Set<String>)`.

- [ ] **Step 1: Write the failing test**

Append to `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/settings/SettingsRepositoryTest.kt` (match the file's existing harness for creating a `SettingsRepository`; if it exposes a helper like `repo(scope)`, reuse it rather than duplicating DataStore setup):

```kotlin
    @Test fun daily_update_defaults_to_off_at_eight() = runTest {
        val r = repo(CoroutineScope(coroutineContext + SupervisorJob()))
        val s = r.settings.first()
        assertFalse(s.dailyUpdateEnabled)
        assertEquals(LocalTime(8, 0), s.dailyUpdateTime)
        assertEquals(emptySet(), s.signInNaggedSourceIds)
    }

    @Test fun daily_update_fields_round_trip() = runTest {
        val r = repo(CoroutineScope(coroutineContext + SupervisorJob()))
        r.setDailyUpdateEnabled(true)
        r.setDailyUpdateTime(LocalTime(6, 45))
        r.setSignInNaggedSourceIds(setOf("ups", "amazon"))
        val s = r.settings.first()
        assertTrue(s.dailyUpdateEnabled)
        assertEquals(LocalTime(6, 45), s.dailyUpdateTime)
        assertEquals(setOf("ups", "amazon"), s.signInNaggedSourceIds)
    }

    @Test fun corrupt_time_falls_back_to_eight() = runTest {
        val r = repo(CoroutineScope(coroutineContext + SupervisorJob()))
        r.setDailyUpdateTime(LocalTime(6, 45))
        r.writeRawDailyUpdateTimeForTest("not-a-time")
        assertEquals(LocalTime(8, 0), r.settings.first().dailyUpdateTime)
    }

    @Test fun empty_nag_string_is_an_empty_set_not_a_blank_id() = runTest {
        val r = repo(CoroutineScope(coroutineContext + SupervisorJob()))
        r.setSignInNaggedSourceIds(setOf("ups"))
        r.setSignInNaggedSourceIds(emptySet())
        assertEquals(emptySet(), r.settings.first().signInNaggedSourceIds)
    }
```

Add the imports the file needs: `kotlinx.datetime.LocalTime`, `kotlinx.coroutines.CoroutineScope`, `kotlinx.coroutines.SupervisorJob`, `kotlin.test.assertFalse`, `kotlin.test.assertTrue`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :data:jvmTest --tests "*SettingsRepositoryTest*" --console=plain`
Expected: FAIL — `dailyUpdateEnabled` unresolved.

- [ ] **Step 3: Implement**

In `SettingsRepository.kt`, add imports `kotlinx.datetime.LocalTime`, then extend `AppSettings`:

```kotlin
data class AppSettings(
    val sourceConfigs: Map<String, SourceConfig> = emptyMap(),
    val autoClipboardImport: Boolean = true,
    val refreshFrequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN,
    val dailyUpdateEnabled: Boolean = false,
    val dailyUpdateTime: LocalTime = DEFAULT_DAILY_UPDATE_TIME,
    /** Sources already nagged about an expired session; cleared when they next succeed. */
    val signInNaggedSourceIds: Set<String> = emptySet(),
)

val DEFAULT_DAILY_UPDATE_TIME = LocalTime(8, 0)
```

Add the keys next to the existing ones:

```kotlin
private val KEY_DAILY_ENABLED = booleanPreferencesKey("daily_update_enabled")
private val KEY_DAILY_TIME = stringPreferencesKey("daily_update_time")
private val KEY_SIGNIN_NAGGED = stringPreferencesKey("sign_in_nagged_source_ids")
```

Extend the `settings` mapping with:

```kotlin
            dailyUpdateEnabled = prefs[KEY_DAILY_ENABLED] ?: false,
            dailyUpdateTime = prefs[KEY_DAILY_TIME]
                ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                ?: DEFAULT_DAILY_UPDATE_TIME,
            signInNaggedSourceIds = prefs[KEY_SIGNIN_NAGGED]
                ?.split(',')?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet(),
```

And add the setters:

```kotlin
    suspend fun setDailyUpdateEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DAILY_ENABLED] = enabled }
    }

    suspend fun setDailyUpdateTime(time: LocalTime) {
        // LocalTime.toString() is ISO ("06:45"), which LocalTime.parse round-trips.
        dataStore.edit { it[KEY_DAILY_TIME] = time.toString() }
    }

    suspend fun setSignInNaggedSourceIds(ids: Set<String>) {
        dataStore.edit { it[KEY_SIGNIN_NAGGED] = ids.joinToString(",") }
    }

    /** Test hook for the corrupt-value fallback path; not used by production code. */
    internal suspend fun writeRawDailyUpdateTimeForTest(raw: String) {
        dataStore.edit { it[KEY_DAILY_TIME] = raw }
    }
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :data:jvmTest --tests "*SettingsRepositoryTest*" --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "[data] Persist daily-update toggle, time, and sign-in nag state"
```

---

### Task 5: Next-run-time computation

Pure, testable scheduling arithmetic so both platform schedulers stay dumb.

**Files:**
- Create: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/daily/NextRunTime.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/daily/NextRunTimeTest.kt`

**Interfaces:**
- Produces: `object NextRunTime { fun nextOccurrence(target: LocalTime, now: Instant, zone: TimeZone): Instant; fun delayUntilNext(target: LocalTime, now: Instant, zone: TimeZone): Duration }`.

- [ ] **Step 1: Write the failing test**

Create `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/daily/NextRunTimeTest.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.hours
import kotlin.test.*

class NextRunTimeTest {
    private val ny = TimeZone.of("America/New_York")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime(y, m, d, h, min).toInstant(ny)

    @Test fun later_today_is_today() {
        val now = at(2026, 8, 18, 6, 30)
        assertEquals(at(2026, 8, 18, 8, 0), NextRunTime.nextOccurrence(LocalTime(8, 0), now, ny))
    }

    @Test fun already_past_rolls_to_tomorrow() {
        val now = at(2026, 8, 18, 9, 15)
        assertEquals(at(2026, 8, 19, 8, 0), NextRunTime.nextOccurrence(LocalTime(8, 0), now, ny))
    }

    @Test fun exactly_now_rolls_to_tomorrow() {
        val now = at(2026, 8, 18, 8, 0)
        assertEquals(at(2026, 8, 19, 8, 0), NextRunTime.nextOccurrence(LocalTime(8, 0), now, ny))
    }

    @Test fun spring_forward_still_lands_on_local_eight_am() {
        // 2027-03-14 is the US DST spring-forward date; 8am local is 23 hours after 8am on the 13th.
        val now = at(2027, 3, 13, 9, 0)
        val next = NextRunTime.nextOccurrence(LocalTime(8, 0), now, ny)
        assertEquals(at(2027, 3, 14, 8, 0), next)
        assertEquals(23.hours, next - at(2027, 3, 13, 8, 0))
    }

    @Test fun delay_is_the_gap_from_now() {
        val now = at(2026, 8, 18, 6, 0)
        assertEquals(2.hours, NextRunTime.delayUntilNext(LocalTime(8, 0), now, ny))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :data:jvmTest --tests "*NextRunTimeTest*" --console=plain`
Expected: FAIL — `NextRunTime` unresolved.

- [ ] **Step 3: Implement**

Create `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/daily/NextRunTime.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * When the daily refresh should next fire, anchored to a WALL-CLOCK local time.
 *
 * Deliberately not "now + 24h": across a DST boundary that would drift the run an hour, and
 * every re-anchor would compound the drift. Computing the next local 8:00 each time keeps it
 * fixed to the user's morning, and shortens/lengthens the one interval that straddles the change.
 */
object NextRunTime {

    fun nextOccurrence(target: LocalTime, now: Instant, zone: TimeZone): Instant {
        val today = now.toLocalDateTime(zone).date
        val todayAt = LocalDateTime(today, target).toInstant(zone)
        // Strictly-after: a run that fires exactly at the target must schedule the NEXT day,
        // not re-enqueue itself for the instant it is already at.
        return if (todayAt > now) todayAt else LocalDateTime(today.plus(1, DateTimeUnit.DAY), target).toInstant(zone)
    }

    fun delayUntilNext(target: LocalTime, now: Instant, zone: TimeZone): Duration =
        nextOccurrence(target, now, zone) - now
}
```

If kotlinx-datetime 0.8.0 rejects `LocalDateTime(date, time)`, use `LocalDateTime(today.year, today.month, today.day, target.hour, target.minute)`; if `toInstant(zone)` is unresolved, the import is `kotlinx.datetime.toInstant`.

- [ ] **Step 4: Run the test**

Run: `./gradlew :data:jvmTest --tests "*NextRunTimeTest*" --console=plain`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "[data] Compute the next wall-clock occurrence of the daily refresh time"
```

---

### Task 6: Platform boundaries and `DailyRefreshRunner`

**Files:**
- Create: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/daily/DailyRefresh.kt` (both interfaces + no-ops)
- Create: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/daily/DailyRefreshRunner.kt`
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/di/DataModule.kt`
- Modify: `data/src/jvmMain/kotlin/com/dgmltn/shiphappens/data/di/PlatformDataModule.jvm.kt`
- Test: `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/daily/DailyRefreshRunnerTest.kt`

**Interfaces:**
- Consumes: `ParcelChange`, `RefreshSummary` (Task 2); `NotificationText` (Task 3); daily settings (Task 4).
- Produces:
  - `interface StatusNotifier { suspend fun notifyStatusChange(change: ParcelChange); suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) }`, `object NoOpStatusNotifier : StatusNotifier`.
  - `interface DailyRefreshScheduler { fun schedule(at: LocalTime); fun cancel() }`, `object NoOpDailyRefreshScheduler : DailyRefreshScheduler`.
  - `class DailyRefreshRunner(repository, settings, registry, notifier) { suspend fun runOnce(): RefreshSummary }`.

- [ ] **Step 1: Write the failing test**

Create `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/daily/DailyRefreshRunnerTest.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.AddResult
import com.dgmltn.shiphappens.data.FixedClock
import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.ParcelRepository
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.FakeSource
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.test.*

private class RecordingNotifier : StatusNotifier {
    val changes = mutableListOf<ParcelChange>()
    val signIns = mutableListOf<String>()
    override suspend fun notifyStatusChange(change: ParcelChange) { changes += change }
    override suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) { signIns += sourceId }
}

class DailyRefreshRunnerTest {
    private lateinit var settings: SettingsRepository
    private lateinit var repository: ParcelRepository
    private lateinit var registry: SourceRegistry
    private val notifier = RecordingNotifier()

    private fun runner(scope: CoroutineScope, vararg sources: TrackingSource): DailyRefreshRunner {
        val dir = kotlin.io.path.createTempDirectory("daily").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        registry = SourceRegistry(sources.toList(), settings)
        repository = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        return DailyRefreshRunner(repository, settings, registry, notifier)
    }

    private suspend fun enableAll(vararg ids: String) {
        settings.setDailyUpdateEnabled(true)
        ids.forEach { settings.setSourceConfig(it, SourceConfig(enabled = true)) }
    }

    @Test fun disabled_setting_skips_the_run_entirely() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS)
        val r = runner(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)

        val summary = r.runOnce()

        assertEquals(0, summary.attempted)
        assertTrue(src.trackedNumbers.isEmpty())
        assertTrue(notifier.changes.isEmpty())
    }

    @Test fun notable_change_notifies_once_per_parcel() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = runner(scope, src)
        enableAll("u")
        val added = repository.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added

        r.runOnce()  // UNKNOWN -> IN_TRANSIT is notable

        assertEquals(listOf(added.parcel.id), notifier.changes.map { it.parcelId })
    }

    @Test fun unchanged_parcel_does_not_notify() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = runner(scope, src)
        enableAll("u")
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.runOnce()
        notifier.changes.clear()

        r.runOnce()

        assertTrue(notifier.changes.isEmpty())
    }

    @Test fun auth_failure_nags_once_then_stays_quiet() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Failure(FailureReason.AUTH, "expired"))
        val r = runner(scope, src)
        enableAll("u")
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)

        r.runOnce()
        r.runOnce()

        assertEquals(listOf("u"), notifier.signIns)
        assertEquals(setOf("u"), settings.settings.first().signInNaggedSourceIds)
    }

    @Test fun a_later_success_rearms_the_nag() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Failure(FailureReason.AUTH, "expired"))
        val r = runner(scope, src)
        enableAll("u")
        repository.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.runOnce()

        src.trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT))
        r.runOnce()
        assertEquals(emptySet(), settings.settings.first().signInNaggedSourceIds)

        src.trackResult = SourceResult.Failure(FailureReason.AUTH, "expired again")
        r.runOnce()

        assertEquals(listOf("u", "u"), notifier.signIns)
    }

    @Test fun archived_and_delivered_parcels_are_never_candidates() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.DELIVERED)))
        val r = runner(scope, src)
        enableAll("u")
        val added = repository.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        r.runOnce()          // becomes DELIVERED, notifies
        notifier.changes.clear()
        repository.archive(added.parcel.id)

        val summary = r.runOnce()

        assertEquals(0, summary.attempted)
        assertTrue(notifier.changes.isEmpty())
    }
}
```

`FixedClock` already exists in `data/src/jvmTest/.../ParcelRepositoryTest.kt` at package `com.dgmltn.shiphappens.data`, so it imports cleanly.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :data:jvmTest --tests "*DailyRefreshRunnerTest*" --console=plain`
Expected: FAIL — `StatusNotifier` / `DailyRefreshRunner` unresolved.

- [ ] **Step 3: Create the platform boundaries**

Create `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/daily/DailyRefresh.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import com.dgmltn.shiphappens.data.ParcelChange
import kotlinx.datetime.LocalTime

/**
 * Posts user-visible notifications. Platform-implemented (NotificationManagerCompat on Android,
 * UNUserNotificationCenter on iOS) and deliberately narrow: the runner decides WHAT to say
 * (see NotificationText), the notifier only decides HOW to show it.
 */
interface StatusNotifier {
    suspend fun notifyStatusChange(change: ParcelChange)
    suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String)
}

/** Platforms without a notification implementation (JVM), and tests. */
object NoOpStatusNotifier : StatusNotifier {
    override suspend fun notifyStatusChange(change: ParcelChange) {}
    override suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) {}
}

/**
 * Books the once-a-day background run. [schedule] is idempotent — calling it again replaces
 * any existing booking, which is how a changed time takes effect.
 */
interface DailyRefreshScheduler {
    fun schedule(at: LocalTime)
    fun cancel()
}

object NoOpDailyRefreshScheduler : DailyRefreshScheduler {
    override fun schedule(at: LocalTime) {}
    override fun cancel() {}
}
```

- [ ] **Step 4: Implement the runner**

Create `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/daily/DailyRefreshRunner.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import com.dgmltn.shiphappens.data.ParcelRepository
import com.dgmltn.shiphappens.data.RefreshSummary
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import kotlinx.coroutines.flow.first

/**
 * One daily pass: refresh every undelivered parcel, then say what moved.
 *
 * Platform-free by design — the Android worker and the iOS BGTask handler both do nothing but
 * call [runOnce], so the candidate rule, the change rule, and the nag dedup exist once.
 */
class DailyRefreshRunner(
    private val repository: ParcelRepository,
    private val settings: SettingsRepository,
    private val registry: SourceRegistry,
    private val notifier: StatusNotifier,
) {

    suspend fun runOnce(): RefreshSummary {
        val current = settings.settings.first()
        // Defensive: the scheduler should already be cancelled when the toggle is off, but a
        // stale WorkManager booking (or an iOS task submitted before the user opted out) must
        // not put the app on the network.
        if (!current.dailyUpdateEnabled) return RefreshSummary(attempted = 0, failed = 0)

        // force = true: the daily pass deliberately ignores the foreground staleness setting,
        // including MANUAL. refreshAll's own filter already excludes archived and DELIVERED.
        val summary = repository.refreshAll(force = true)

        summary.changes.filter { it.isNotable }.forEach { notifier.notifyStatusChange(it) }

        val nagged = current.signInNaggedSourceIds
        val toNag = summary.authFailedSourceIds - nagged
        toNag.forEach { id ->
            notifier.notifySignInNeeded(id, displayNameOf(id))
        }
        // A source that succeeded is signed in again, so it re-arms for the next expiry.
        val updatedNags = (nagged + toNag) - summary.succeededSourceIds
        if (updatedNags != nagged) settings.setSignInNaggedSourceIds(updatedNags)

        return summary
    }

    private fun displayNameOf(sourceId: String): String =
        registry.all().firstOrNull { it.descriptor.id == sourceId }?.descriptor?.displayName ?: sourceId
}
```

- [ ] **Step 5: Wire DI**

In `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/di/DataModule.kt`, add the import `com.dgmltn.shiphappens.data.daily.DailyRefreshRunner` and one line inside `dataModule`:

```kotlin
    single { DailyRefreshRunner(get(), get(), get(), get()) }
```

Update the `platformDataModule` KDoc to: `/** Provides DataStore<Preferences>, ShipHappensDb, ClipboardReader, StatusNotifier, DailyRefreshScheduler per platform. */`

In `data/src/jvmMain/kotlin/com/dgmltn/shiphappens/data/di/PlatformDataModule.jvm.kt`, add to the module body:

```kotlin
    single<StatusNotifier> { NoOpStatusNotifier }
    single<DailyRefreshScheduler> { NoOpDailyRefreshScheduler }
```

with imports `com.dgmltn.shiphappens.data.daily.*`. Do the same for `PlatformDataModule.android.kt` and `PlatformDataModule.ios.kt` **temporarily with the no-ops** so every target compiles now; Tasks 7, 8 and 10 replace them with the real bindings.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :data:jvmTest --console=plain`
Expected: PASS — 6 new runner tests plus everything from Tasks 2–5.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "[data] Add DailyRefreshRunner with notifier and scheduler boundaries"
```

---

### Task 7: Android WorkManager scheduler and worker

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `data/build.gradle.kts` (androidMain dependency)
- Create: `data/src/androidMain/kotlin/com/dgmltn/shiphappens/data/daily/DailyRefreshWorker.kt`
- Create: `data/src/androidMain/kotlin/com/dgmltn/shiphappens/data/daily/WorkManagerDailyRefreshScheduler.kt`
- Modify: `data/src/androidMain/kotlin/com/dgmltn/shiphappens/data/di/PlatformDataModule.android.kt`

**Interfaces:**
- Consumes: `DailyRefreshScheduler`, `DailyRefreshRunner` (Task 6); `NextRunTime` (Task 5); `AppSettings.dailyUpdateTime` (Task 4).
- Produces: `WorkManagerDailyRefreshScheduler(context: Context)` bound as `DailyRefreshScheduler` on Android; unique work name `daily-refresh`.

- [ ] **Step 1: Add the dependency**

In `gradle/libs.versions.toml`, add under `[versions]`:

```toml
work = "2.10.1"
```

and under `[libraries]`:

```toml
androidx-work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
```

If Gradle reports a newer stable `androidx.work:work-runtime-ktx`, prefer it and update the version here.

In `data/build.gradle.kts`, add to the `androidMain.dependencies` block:

```kotlin
            implementation(libs.androidx.work.runtime)
```

- [ ] **Step 2: Write the worker**

Create `data/src/androidMain/kotlin/com/dgmltn/shiphappens/data/daily/DailyRefreshWorker.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The 8am pass. Resolves its collaborators from the app-wide Koin graph rather than taking a
 * WorkerFactory, because the app has no other custom worker and Koin is already started by
 * ShipHappensApplication before WorkManager can run anything.
 *
 * The headless scrape path is safe from here: HeadlessWebViewScraper hops to
 * Dispatchers.Main.immediate itself before constructing a WebView.
 */
class DailyRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val runner: DailyRefreshRunner by inject()
    private val settings: SettingsRepository by inject()
    private val scheduler: DailyRefreshScheduler by inject()

    override suspend fun doWork(): Result = try {
        runner.runOnce()
        Result.success()
    } catch (t: Throwable) {
        Result.retry()
    } finally {
        // Re-anchor tomorrow's run whatever happened — a failed pass must not end the series.
        // Reading the time fresh means a time change made while the app was closed takes effect.
        val current = settings.settings.first()
        if (current.dailyUpdateEnabled) scheduler.schedule(current.dailyUpdateTime) else scheduler.cancel()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "daily-refresh"
    }
}
```

Kotlin note: a `try/catch/finally` used as an expression returns the `try`/`catch` value; the `finally` block runs for its side effect only. That is the intent here.

- [ ] **Step 3: Write the scheduler**

Create `data/src/androidMain/kotlin/com/dgmltn/shiphappens/data/daily/WorkManagerDailyRefreshScheduler.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/**
 * Books the daily pass as SELF-RESCHEDULING one-time work rather than PeriodicWorkRequest.
 *
 * A 24-hour period drifts an hour across each DST change and can never be re-anchored; a
 * one-time request whose successor is enqueued by the worker recomputes the next local 8:00
 * every day (see NextRunTime). WorkManager persists enqueued work across reboot, so no
 * BOOT_COMPLETED receiver is needed.
 */
class WorkManagerDailyRefreshScheduler(private val context: Context) : DailyRefreshScheduler {

    override fun schedule(at: LocalTime) {
        val delay = NextRunTime.delayUntilNext(at, Clock.System.now(), TimeZone.currentSystemDefault())
        val request = OneTimeWorkRequestBuilder<DailyRefreshWorker>()
            .setInitialDelay(delay.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DailyRefreshWorker.UNIQUE_WORK_NAME,
            // REPLACE, so changing the time re-books rather than queuing a second run.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(DailyRefreshWorker.UNIQUE_WORK_NAME)
    }
}
```

- [ ] **Step 4: Bind it**

In `data/src/androidMain/kotlin/com/dgmltn/shiphappens/data/di/PlatformDataModule.android.kt`, replace the temporary `NoOpDailyRefreshScheduler` binding with:

```kotlin
    single<DailyRefreshScheduler> { WorkManagerDailyRefreshScheduler(androidContext()) }
```

(imports: `com.dgmltn.shiphappens.data.daily.DailyRefreshScheduler`, `...daily.WorkManagerDailyRefreshScheduler`.)

- [ ] **Step 5: Verify it compiles on every target**

Run: `./gradlew :data:compileDebugKotlinAndroid :data:compileKotlinJvm :data:jvmTest --console=plain`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "[android] Schedule the daily refresh with self-rescheduling WorkManager work"
```

---

### Task 8: Android notifications and deep links

**Files:**
- Create: `data/src/androidMain/kotlin/com/dgmltn/shiphappens/data/daily/AndroidStatusNotifier.kt`
- Modify: `data/src/androidMain/kotlin/com/dgmltn/shiphappens/data/di/PlatformDataModule.android.kt`
- Modify: `app-android/src/main/AndroidManifest.xml`
- Create: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/navigation/DeepLink.kt`
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/App.kt`
- Modify: `app-android/src/main/kotlin/com/dgmltn/shiphappens/android/MainActivity.kt`

**Interfaces:**
- Consumes: `StatusNotifier` (Task 6), `NotificationText` (Task 3), `ParcelChange` (Task 2).
- Produces:
  - `AndroidStatusNotifier(context: Context)` bound as `StatusNotifier`.
  - Deep-link URIs `shiphappens://parcel/{parcelId}` and `shiphappens://signin/{sourceId}`.
  - `sealed interface DeepLink { data class Parcel(val parcelId: String); data class SignIn(val sourceId: String) }` and `fun parseDeepLink(uri: String?): DeepLink?` in `com.dgmltn.shiphappens.ui.navigation`.
  - `App(deepLink: DeepLink? = null, onDeepLinkHandled: () -> Unit = {})`.

- [ ] **Step 1: Write the deep-link parser test**

Create `ui/src/androidHostTest/kotlin/com/dgmltn/shiphappens/ui/navigation/DeepLinkTest.kt`:

```kotlin
package com.dgmltn.shiphappens.ui.navigation

import kotlin.test.*

class DeepLinkTest {
    @Test fun parcel_uri_parses() {
        assertEquals(DeepLink.Parcel("abc-123"), parseDeepLink("shiphappens://parcel/abc-123"))
    }

    @Test fun signin_uri_parses() {
        assertEquals(DeepLink.SignIn("ups"), parseDeepLink("shiphappens://signin/ups"))
    }

    @Test fun unknown_host_null_and_blank_are_ignored() {
        assertNull(parseDeepLink("shiphappens://nonsense/x"))
        assertNull(parseDeepLink("shiphappens://parcel/"))
        assertNull(parseDeepLink(null))
        assertNull(parseDeepLink("https://example.com/parcel/1"))
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :ui:testAndroidHostTest --tests "*DeepLinkTest*" --console=plain`
Expected: FAIL — `DeepLink` unresolved.

- [ ] **Step 3: Implement the parser**

Create `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/navigation/DeepLink.kt`:

```kotlin
package com.dgmltn.shiphappens.ui.navigation

/** Where a tapped notification wants the app to land. */
sealed interface DeepLink {
    data class Parcel(val parcelId: String) : DeepLink
    data class SignIn(val sourceId: String) : DeepLink
}

private const val SCHEME = "shiphappens://"

/**
 * Parses `shiphappens://parcel/{id}` and `shiphappens://signin/{sourceId}`.
 *
 * Hand-rolled rather than android.net.Uri because this lives in commonMain — the shapes are
 * fixed and produced by our own notifier, so there is nothing to be liberal about.
 */
fun parseDeepLink(uri: String?): DeepLink? {
    val rest = uri?.removePrefix(SCHEME)?.takeIf { it != uri } ?: return null
    val host = rest.substringBefore('/')
    val arg = rest.substringAfter('/', "").takeIf { it.isNotBlank() } ?: return null
    return when (host) {
        "parcel" -> DeepLink.Parcel(arg)
        "signin" -> DeepLink.SignIn(arg)
        else -> null
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :ui:testAndroidHostTest --tests "*DeepLinkTest*" --console=plain`
Expected: PASS (3 tests).

- [ ] **Step 5: Teach `App()` to consume a deep link**

In `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/App.kt`, change the signature and add the effect. Add imports `androidx.compose.runtime.LaunchedEffect` and `com.dgmltn.shiphappens.ui.navigation.DeepLink` (the file already star-imports `...ui.navigation.*`, so no new import is needed for `DeepLink`).

```kotlin
@Composable
fun App(deepLink: DeepLink? = null, onDeepLinkHandled: () -> Unit = {}) {
    ShipTheme {
        StatusBarIconsEffect()
        val backStack = remember { NavBackStack<NavKey>(ListRoute) }
        LaunchedEffect(deepLink) {
            when (deepLink) {
                // Pushed onto whatever is showing: a tapped notification should reveal the
                // parcel without discarding where the user already was.
                is DeepLink.Parcel -> backStack.add(DetailRoute(deepLink.parcelId))
                is DeepLink.SignIn -> backStack.add(WebLoginRoute(deepLink.sourceId))
                null -> return@LaunchedEffect
            }
            onDeepLinkHandled()
        }
        NavDisplay(
            // ...unchanged...
        )
    }
}
```

- [ ] **Step 6: Write the Android notifier**

Create `data/src/androidMain/kotlin/com/dgmltn/shiphappens/data/daily/AndroidStatusNotifier.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.notify.NotificationText

/**
 * Posts the daily run's findings.
 *
 * Notification ids come from the parcel id's hash so a second change to the same parcel
 * REPLACES the first rather than stacking two rows for one package. All package updates share
 * a group plus a summary notification, so several at 8am collapse into one expandable entry.
 *
 * Posting is silently dropped by the OS when POST_NOTIFICATIONS is denied — the settings
 * screen owns asking, and there is nothing useful to do about it from a background worker.
 */
class AndroidStatusNotifier(private val context: Context) : StatusNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override suspend fun notifyStatusChange(change: ParcelChange) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(NotificationText.title(change))
            .setContentText(NotificationText.body(change))
            .setContentIntent(deepLinkIntent("shiphappens://parcel/${change.parcelId}", change.parcelId.hashCode()))
            .setAutoCancel(true)
            .setGroup(GROUP_UPDATES)
            .build()
        post(change.parcelId.hashCode(), notification)
        postGroupSummary()
    }

    override suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) {
        ensureChannels()
        val notification = NotificationCompat.Builder(context, CHANNEL_SIGN_IN)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(NotificationText.signInTitle(sourceDisplayName))
            .setContentText(NotificationText.signInBody(sourceDisplayName))
            .setStyle(NotificationCompat.BigTextStyle().bigText(NotificationText.signInBody(sourceDisplayName)))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(deepLinkIntent("shiphappens://signin/$sourceId", sourceId.hashCode()))
            .setAutoCancel(true)
            .build()
        post(SIGN_IN_ID_BASE + sourceId.hashCode(), notification)
    }

    private fun postGroupSummary() {
        val summary = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Package updates")
            .setGroup(GROUP_UPDATES)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()
        post(SUMMARY_ID, summary)
    }

    private fun post(id: Int, notification: android.app.Notification) {
        // areNotificationsEnabled() spares us a SecurityException-shaped surprise on API 33+;
        // NotificationManagerCompat.notify itself requires the permission at call time.
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(id, notification) }
    }

    private fun deepLinkIntent(uri: String, requestCode: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            setPackage(context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannels() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, "Package updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Status and delivery-date changes found by the daily check"
            }
        )
        system.createNotificationChannel(
            NotificationChannel(CHANNEL_SIGN_IN, "Sign-in needed", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A carrier session expired and packages can't be updated"
            }
        )
    }

    private companion object {
        const val CHANNEL_UPDATES = "package_updates"
        const val CHANNEL_SIGN_IN = "sign_in"
        const val GROUP_UPDATES = "com.dgmltn.shiphappens.UPDATES"
        const val SUMMARY_ID = 1
        const val SIGN_IN_ID_BASE = 100_000
    }
}
```

The app's `minSdk` is well above 26, so `NotificationChannel` needs no version guard — confirm with `grep shiphappens.minSdk gradle.properties` and add an `if (Build.VERSION.SDK_INT >= 26)` guard only if it is below 26.

- [ ] **Step 7: Bind it and add the permission**

In `PlatformDataModule.android.kt`, replace the temporary no-op:

```kotlin
    single<StatusNotifier> { AndroidStatusNotifier(androidContext()) }
```

In `app-android/src/main/AndroidManifest.xml`, add the permission next to `INTERNET`:

```xml
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

and give `MainActivity` the deep-link filter plus `singleTop` so a tap reuses the running task:

```xml
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTop"
            android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|uiMode|smallestScreenSize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="shiphappens" />
            </intent-filter>
        </activity>
```

- [ ] **Step 8: Handle the intent in `MainActivity`**

Replace the body of `app-android/src/main/kotlin/com/dgmltn/shiphappens/android/MainActivity.kt`:

```kotlin
package com.dgmltn.shiphappens.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dgmltn.shiphappens.ui.App
import com.dgmltn.shiphappens.ui.navigation.DeepLink
import com.dgmltn.shiphappens.ui.navigation.parseDeepLink

class MainActivity : ComponentActivity() {

    /**
     * Set from the launch intent and from onNewIntent (the activity is singleTop, so a tapped
     * notification re-enters the running instance rather than recreating it). App() clears it
     * once it has pushed the route, so a configuration change doesn't re-navigate.
     */
    private val pendingDeepLink = mutableStateOf<DeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        pendingDeepLink.value = parseDeepLink(intent?.dataString)
        // The app is light-only, so pin the system bars to dark icons rather than letting
        // enableEdgeToEdge() pick white ones from the system dark theme. Screens with a
        // carrier-colored header flip the status bar back to light icons themselves.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            App(
                deepLink = pendingDeepLink.value,
                onDeepLinkHandled = { pendingDeepLink.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink.value = parseDeepLink(intent.dataString)
    }
}
```

- [ ] **Step 9: Build and test**

Run: `./gradlew :app-android:assembleDebug :ui:testAndroidHostTest --console=plain`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "[android] Post grouped status notifications that deep-link to the parcel"
```

---

### Task 9: Settings UI, permission request, and scheduling on toggle

**Files:**
- Create: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/settings/NotificationPermission.kt` (expect)
- Create: `ui/src/androidMain/kotlin/com/dgmltn/shiphappens/ui/settings/NotificationPermission.android.kt`
- Create: `ui/src/iosMain/kotlin/com/dgmltn/shiphappens/ui/settings/NotificationPermission.ios.kt`
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/settings/SettingsViewModel.kt`
- Modify: `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/settings/SettingsScreen.kt`
- Test: `ui/src/androidHostTest/kotlin/com/dgmltn/shiphappens/ui/settings/SettingsViewModelTest.kt` (append)

**Interfaces:**
- Consumes: `DailyRefreshScheduler` (Task 6), daily settings (Task 4).
- Produces:
  - `interface NotificationPermissionController { val isGranted: Boolean; fun request(onResult: (Boolean) -> Unit) }` and `@Composable expect fun rememberNotificationPermissionController(): NotificationPermissionController`.
  - `SettingsUiState.dailyUpdateEnabled: Boolean`, `.dailyUpdateTime: LocalTime`, `.dailyUpdateBlocked: Boolean`.
  - `SettingsViewModel.onDailyUpdateEnabled(enabled: Boolean, permissionGranted: Boolean): Job`, `.onDailyUpdateTime(time: LocalTime): Job`.

- [ ] **Step 1: Write the failing ViewModel test**

Append to `ui/src/androidHostTest/kotlin/com/dgmltn/shiphappens/ui/settings/SettingsViewModelTest.kt`. Follow the file's existing `awaitState` helper for reads — do not use `advanceUntilIdle(); vm.state.value`:

```kotlin
    @Test fun enabling_daily_update_with_permission_persists_and_schedules() = runTest {
        // build vm via the file's existing setUp/harness, with a RecordingScheduler injected
        vm.onDailyUpdateEnabled(enabled = true, permissionGranted = true).join()

        val s = awaitState { it.dailyUpdateEnabled }
        assertTrue(s.dailyUpdateEnabled)
        assertFalse(s.dailyUpdateBlocked)
        assertEquals(listOf(LocalTime(8, 0)), scheduler.scheduled)
    }

    @Test fun enabling_without_permission_changes_nothing_and_flags_blocked() = runTest {
        vm.onDailyUpdateEnabled(enabled = true, permissionGranted = false).join()

        val s = awaitState { it.dailyUpdateBlocked }
        assertFalse(s.dailyUpdateEnabled)
        assertTrue(scheduler.scheduled.isEmpty())
        assertFalse(settings.settings.first().dailyUpdateEnabled)
    }

    @Test fun disabling_cancels() = runTest {
        vm.onDailyUpdateEnabled(enabled = true, permissionGranted = true).join()
        vm.onDailyUpdateEnabled(enabled = false, permissionGranted = true).join()

        awaitState { !it.dailyUpdateEnabled }
        assertEquals(1, scheduler.cancelCount)
    }

    @Test fun changing_the_time_while_enabled_reschedules() = runTest {
        vm.onDailyUpdateEnabled(enabled = true, permissionGranted = true).join()
        vm.onDailyUpdateTime(LocalTime(6, 30)).join()

        awaitState { it.dailyUpdateTime == LocalTime(6, 30) }
        assertEquals(listOf(LocalTime(8, 0), LocalTime(6, 30)), scheduler.scheduled)
    }

    @Test fun changing_the_time_while_disabled_does_not_schedule() = runTest {
        vm.onDailyUpdateTime(LocalTime(6, 30)).join()

        awaitState { it.dailyUpdateTime == LocalTime(6, 30) }
        assertTrue(scheduler.scheduled.isEmpty())
    }
```

Add this fake near the top of the test class:

```kotlin
    private class RecordingScheduler : DailyRefreshScheduler {
        val scheduled = mutableListOf<LocalTime>()
        var cancelCount = 0
        override fun schedule(at: LocalTime) { scheduled += at }
        override fun cancel() { cancelCount++ }
    }
```

and declare `private val scheduler = RecordingScheduler()`, passing it as the ViewModel's new last constructor argument in the existing setup.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :ui:testAndroidHostTest --tests "*SettingsViewModelTest*" --console=plain`
Expected: FAIL — `onDailyUpdateEnabled` unresolved.

- [ ] **Step 3: Extend the ViewModel**

In `SettingsViewModel.kt`: add imports `com.dgmltn.shiphappens.data.daily.DailyRefreshScheduler`, `kotlinx.datetime.LocalTime`, `com.dgmltn.shiphappens.data.settings.DEFAULT_DAILY_UPDATE_TIME`.

Extend the state:

```kotlin
data class SettingsUiState(
    val carriers: List<SourceCardUi> = emptyList(),
    val autoImport: Boolean = true,
    val frequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN,
    val dailyUpdateEnabled: Boolean = false,
    val dailyUpdateTime: LocalTime = DEFAULT_DAILY_UPDATE_TIME,
    /** True after the user tried to enable daily updates and the system permission was denied. */
    val dailyUpdateBlocked: Boolean = false,
    val toast: String? = null,
)
```

Add the constructor parameter `private val scheduler: DailyRefreshScheduler,` after `cookieJar`, plus a `private val blocked = MutableStateFlow(false)`. Fold `blocked` into the combine (`combine` supports up to 5 flows; use the `combine(settings.settings, toast, blocked) { s, t, b -> ... }` overload) and map the new fields:

```kotlin
        SettingsUiState(
            carriers = cards,
            autoImport = s.autoClipboardImport,
            frequency = s.refreshFrequency,
            dailyUpdateEnabled = s.dailyUpdateEnabled,
            dailyUpdateTime = s.dailyUpdateTime,
            dailyUpdateBlocked = b,
            toast = t,
        )
```

Add the actions — both return the `Job` for the same reason `onSignOut` does (DataStore writes resume off the test's main dispatcher):

```kotlin
    /**
     * [permissionGranted] is supplied by the screen, which owns the system prompt — a denied
     * prompt must leave the stored setting alone rather than persisting an on toggle that
     * silently drops every notification.
     */
    fun onDailyUpdateEnabled(enabled: Boolean, permissionGranted: Boolean): Job = viewModelScope.launch {
        if (enabled && !permissionGranted) {
            blocked.value = true
            return@launch
        }
        blocked.value = false
        settings.setDailyUpdateEnabled(enabled)
        if (enabled) scheduler.schedule(settings.settings.first().dailyUpdateTime) else scheduler.cancel()
    }

    fun onDailyUpdateTime(time: LocalTime): Job = viewModelScope.launch {
        settings.setDailyUpdateTime(time)
        if (settings.settings.first().dailyUpdateEnabled) scheduler.schedule(time)
    }
```

Update the Koin definition in `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/di/AppModules.kt` — `viewModelOf(::SettingsViewModel)` resolves constructor parameters automatically, so no change is needed there; verify by running `:ui:testAndroidHostTest --tests "*AppModulesTest*"`.

- [ ] **Step 4: Add the permission controller**

Create `ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/settings/NotificationPermission.kt`:

```kotlin
package com.dgmltn.shiphappens.ui.settings

import androidx.compose.runtime.Composable

/**
 * The system notification-permission prompt. Lives in ui, not data, because asking requires a
 * foreground Activity on Android — the 8am worker has none, so the request has to happen here,
 * at the moment the user flips the toggle.
 */
interface NotificationPermissionController {
    val isGranted: Boolean
    fun request(onResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberNotificationPermissionController(): NotificationPermissionController
```

Create `ui/src/androidMain/kotlin/com/dgmltn/shiphappens/ui/settings/NotificationPermission.android.kt`:

```kotlin
package com.dgmltn.shiphappens.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController {
    val context = LocalContext.current
    // Below API 33 notifications need no runtime permission at all, so the controller reports
    // granted and never launches anything.
    val needsRuntimePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    var granted by remember {
        mutableStateOf(
            !needsRuntimePermission || ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        pending?.invoke(result)
        pending = null
    }

    return remember(granted, needsRuntimePermission) {
        object : NotificationPermissionController {
            override val isGranted: Boolean get() = granted
            override fun request(onResult: (Boolean) -> Unit) {
                if (!needsRuntimePermission || granted) {
                    onResult(true)
                    return
                }
                pending = onResult
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
```

Create `ui/src/iosMain/kotlin/com/dgmltn/shiphappens/ui/settings/NotificationPermission.ios.kt`:

```kotlin
package com.dgmltn.shiphappens.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter

@Composable
actual fun rememberNotificationPermissionController(): NotificationPermissionController {
    var granted by remember { mutableStateOf(false) }
    return remember {
        object : NotificationPermissionController {
            override val isGranted: Boolean get() = granted
            override fun request(onResult: (Boolean) -> Unit) {
                val options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge
                UNUserNotificationCenter.currentNotificationCenter()
                    .requestAuthorizationWithOptions(options) { allowed, _ ->
                        granted = allowed
                        onResult(allowed)
                    }
            }
        }
    }
}
```

- [ ] **Step 5: Add the Settings card**

In `SettingsScreen.kt`, add imports `androidx.compose.material3.*` pieces needed (`AlertDialog`, `TimePicker`, `rememberTimePickerState`, `TextButton` already imported), `kotlinx.datetime.LocalTime`, `com.dgmltn.shiphappens.domain.design12h`, plus `androidx.compose.runtime.remember`/`mutableStateOf`/`getValue`/`setValue`.

Wire the screen composable:

```kotlin
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogin: (String) -> Unit = {}, vm: SettingsViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    val permission = rememberNotificationPermissionController()
    SettingsContent(
        state = s,
        onBack = onBack,
        onToggle = vm::onToggle,
        onAutoImport = vm::onAutoImport,
        onFrequency = vm::onFrequency,
        onSignIn = onOpenLogin,
        onSignOut = { vm.onSignOut(it) },
        onDailyUpdate = { enabled ->
            if (enabled) {
                permission.request { granted -> vm.onDailyUpdateEnabled(true, granted) }
            } else {
                vm.onDailyUpdateEnabled(false, permissionGranted = true)
            }
        },
        onDailyUpdateTime = { vm.onDailyUpdateTime(it) },
    )
}
```

Add three parameters to `SettingsContent` (`onDailyUpdate: (Boolean) -> Unit = {}`, `onDailyUpdateTime: (LocalTime) -> Unit = {}`, `permissionRevoked: Boolean = false`) and render the card right after `SyncCard(...)`:

```kotlin
                DailyUpdateCard(
                    enabled = state.dailyUpdateEnabled,
                    time = state.dailyUpdateTime,
                    // Either the user just denied the prompt, or they turned notifications off in
                    // system settings after enabling this — both leave the toggle unable to deliver.
                    blocked = state.dailyUpdateBlocked || permissionRevoked,
                    onEnabled = onDailyUpdate,
                    onTime = onDailyUpdateTime,
                )
```

`SettingsScreen` supplies `permissionRevoked = s.dailyUpdateEnabled && !permission.isGranted`, so a
permission revoked outside the app surfaces the explanation the next time Settings is shown.

Add the composable next to `SyncCard`:

```kotlin
@Composable
private fun DailyUpdateCard(
    enabled: Boolean,
    time: LocalTime,
    blocked: Boolean,
    onEnabled: (Boolean) -> Unit,
    onTime: (LocalTime) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily update", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink)
                Text("Check undelivered packages every morning and notify you when something changes",
                    fontSize = 12.sp, color = ShipColors.muted)
            }
            Switch(checked = enabled, onCheckedChange = onEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = ShipColors.ink, uncheckedTrackColor = ShipColors.toggleOff))
        }
        if (enabled) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).clickable { picking = true }
                    .border(1.dp, ShipColors.hairline, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Check at", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink, modifier = Modifier.weight(1f))
                Text(time.design12h(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink)
            }
        }
        if (blocked) {
            Spacer(Modifier.height(10.dp))
            Text("Notifications are turned off for Ship Happens. Turn them on in system settings to get daily updates.",
                fontSize = 12.sp, lineHeight = 17.sp, color = ShipColors.faint)
        }
    }
    if (picking) {
        DailyTimePickerDialog(time, onDismiss = { picking = false }, onConfirm = { onTime(it); picking = false })
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun DailyTimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val pickerState = androidx.compose.material3.rememberTimePickerState(
        initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false,
    )
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime(pickerState.hour, pickerState.minute)) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = { androidx.compose.material3.TimePicker(state = pickerState) },
    )
}
```

Add a `@Preview` mirroring the existing ones, with `dailyUpdateEnabled = true, dailyUpdateTime = LocalTime(8, 0)` in the state.

- [ ] **Step 6: Run the tests**

Run: `./gradlew :ui:testAndroidHostTest :app-android:assembleDebug --console=plain`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "[ui] Add the Daily update setting with its permission prompt and time picker"
```

---

### Task 10: iOS scheduler, notifier, and BGTask registration

**Files:**
- Create: `data/src/iosMain/kotlin/com/dgmltn/shiphappens/data/daily/IosStatusNotifier.kt`
- Create: `data/src/iosMain/kotlin/com/dgmltn/shiphappens/data/daily/BgTaskDailyRefreshScheduler.kt`
- Modify: `data/src/iosMain/kotlin/com/dgmltn/shiphappens/data/di/PlatformDataModule.ios.kt`
- Create: `ui/src/iosMain/kotlin/com/dgmltn/shiphappens/ui/IosDailyRefresh.kt`
- Modify: `app-ios/ShipHappens/ShipHappensApp.swift`
- Modify: `app-ios/ShipHappens/Info.plist`

**Interfaces:**
- Consumes: `StatusNotifier`, `DailyRefreshScheduler`, `DailyRefreshRunner` (Task 6); `NextRunTime` (Task 5); `NotificationText` (Task 3).
- Produces: task identifier `com.dgmltn.shiphappens.dailyrefresh`; Kotlin entry point `IosDailyRefresh.run(onComplete: (Boolean) -> Unit)`.

- [ ] **Step 1: Write the iOS notifier**

Create `data/src/iosMain/kotlin/com/dgmltn/shiphappens/data/daily/IosStatusNotifier.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.notify.NotificationText
import platform.Foundation.NSUUID
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

/**
 * Posts immediately (nil trigger) — the daily pass has already run, so there is nothing to wait
 * for. threadIdentifier groups the morning's updates into one stack, matching Android's group.
 */
class IosStatusNotifier : StatusNotifier {

    override suspend fun notifyStatusChange(change: ParcelChange) {
        post(
            title = NotificationText.title(change),
            body = NotificationText.body(change),
            thread = THREAD_UPDATES,
            userInfo = mapOf<Any?, Any?>("parcelId" to change.parcelId),
        )
    }

    override suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String) {
        post(
            title = NotificationText.signInTitle(sourceDisplayName),
            body = NotificationText.signInBody(sourceDisplayName),
            thread = THREAD_SIGN_IN,
            userInfo = mapOf<Any?, Any?>("sourceId" to sourceId),
        )
    }

    private fun post(title: String, body: String, thread: String, userInfo: Map<Any?, Any?>) {
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setThreadIdentifier(thread)
            setUserInfo(userInfo)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = NSUUID().UUIDString, content = content, trigger = null,
        )
        UNUserNotificationCenter.currentNotificationCenter().addNotificationRequest(request, null)
    }

    private companion object {
        const val THREAD_UPDATES = "package_updates"
        const val THREAD_SIGN_IN = "sign_in"
    }
}
```

- [ ] **Step 2: Write the iOS scheduler**

Create `data/src/iosMain/kotlin/com/dgmltn/shiphappens/data/daily/BgTaskDailyRefreshScheduler.kt`:

```kotlin
package com.dgmltn.shiphappens.data.daily

import kotlin.time.Clock
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * iOS gives no wall-clock guarantee: earliestBeginDate is the soonest the system will CONSIDER
 * running the task, and it may run hours later or skip a day entirely based on usage patterns.
 * The settings copy says "around" for exactly this reason.
 */
class BgTaskDailyRefreshScheduler : DailyRefreshScheduler {

    override fun schedule(at: LocalTime) {
        val next = NextRunTime.nextOccurrence(at, Clock.System.now(), TimeZone.currentSystemDefault())
        val request = BGAppRefreshTaskRequest(TASK_IDENTIFIER).apply {
            earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(next.epochSeconds.toDouble())
        }
        // Throws only for an unregistered identifier or a simulator without BGTaskScheduler;
        // neither is worth crashing the app over.
        runCatching { BGTaskScheduler.sharedScheduler.submitTaskRequest(request, null) }
    }

    override fun cancel() {
        BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(TASK_IDENTIFIER)
    }

    companion object {
        const val TASK_IDENTIFIER = "com.dgmltn.shiphappens.dailyrefresh"
    }
}
```

- [ ] **Step 3: Bind them**

In `data/src/iosMain/kotlin/com/dgmltn/shiphappens/data/di/PlatformDataModule.ios.kt`, replace the temporary no-ops with:

```kotlin
    single<StatusNotifier> { IosStatusNotifier() }
    single<DailyRefreshScheduler> { BgTaskDailyRefreshScheduler() }
```

- [ ] **Step 4: Add the Swift-facing entry point**

Create `ui/src/iosMain/kotlin/com/dgmltn/shiphappens/ui/IosDailyRefresh.kt`:

```kotlin
package com.dgmltn.shiphappens.ui

import com.dgmltn.shiphappens.data.daily.DailyRefreshRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Callback-shaped bridge for the BGAppRefreshTask handler: Swift must call
 * task.setTaskCompleted(success:) when the work finishes, and it cannot await a Kotlin
 * suspend function.
 */
object IosDailyRefresh {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun run(onComplete: (Boolean) -> Unit) {
        scope.launch {
            val ok = runCatching { GlobalContext.get().get<DailyRefreshRunner>().runOnce() }.isSuccess
            onComplete(ok)
        }
    }
}
```

- [ ] **Step 5: Register the task in Swift**

Replace `app-ios/ShipHappens/ShipHappensApp.swift`:

```swift
import BackgroundTasks
import SwiftUI
import SharedUI

private let dailyRefreshTaskId = "com.dgmltn.shiphappens.dailyrefresh"

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Registration must happen before launch completes, which is why this lives in Swift
        // rather than in the shared Kotlin bootstrap.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: dailyRefreshTaskId, using: nil) { task in
            IosDailyRefresh.shared.run { success in
                task.setTaskCompleted(success: success)
            }
        }
        return true
    }
}

@main
struct ShipHappensApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    init() {
        AppModulesKt.doInitKoin()
    }
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
```

iOS discards a task request once it has executed, so each run must book the next one. `runOnce()`
deliberately doesn't (the Android worker books its own successor), so `IosDailyRefresh.run` does it —
replace the body of the `scope.launch { }` block written in Step 4 with:

```kotlin
            val koin = GlobalContext.get()
            val ok = runCatching { koin.get<DailyRefreshRunner>().runOnce() }.isSuccess
            // iOS requires a fresh request per execution; re-book while the settings still say so.
            val settings = koin.get<com.dgmltn.shiphappens.data.settings.SettingsRepository>().settings.first()
            if (settings.dailyUpdateEnabled) {
                koin.get<com.dgmltn.shiphappens.data.daily.DailyRefreshScheduler>().schedule(settings.dailyUpdateTime)
            }
            onComplete(ok)
```

(add `import kotlinx.coroutines.flow.first`).

- [ ] **Step 6: Declare the task and background mode**

In `app-ios/ShipHappens/Info.plist`, add inside the top-level `<dict>`:

```xml
    <key>UIBackgroundModes</key>
    <array>
        <string>fetch</string>
    </array>
    <key>BGTaskSchedulerPermittedIdentifiers</key>
    <array>
        <string>com.dgmltn.shiphappens.dailyrefresh</string>
    </array>
```

- [ ] **Step 7: Verify the iOS framework compiles**

Run: `./gradlew :ui:compileKotlinIosSimulatorArm64 :data:compileKotlinIosSimulatorArm64 --console=plain`
Expected: PASS. (A full Xcode build needs `cd app-ios && xcodegen generate` first; run it if XcodeGen is installed, otherwise note that the Swift side is unverified.)

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "[iOS] Add BGAppRefreshTask scheduling and UNUserNotificationCenter posting"
```

---

### Task 11: Documentation and full verification

**Files:**
- Modify: `README.md` (module map note, test counts)
- Create: `docs/superpowers/qa/2026-08-18-daily-refresh-qa.md`

- [ ] **Step 1: Update the README**

In the module map, extend the `data/` line to mention the daily refresh:

```
data/          Room 3 database, ParcelRepository, SettingsRepository (DataStore), clipboard
               import manager, carrier detection, SourceRegistry, DailyRefreshRunner (the 8am
               background pass, with WorkManager/BGTaskScheduler and notifier actuals per
               platform), Koin DI wiring.
```

Add a short section after "Settings & API keys":

```markdown
## Daily update

With "Daily update" enabled in Settings, the app refreshes every undelivered parcel once a day at
the chosen local time and posts one notification per parcel whose status or delivery date changed.
The decision logic (candidate selection, change detection, notification copy) lives in
`data/commonMain`; Android schedules it with self-rescheduling WorkManager one-time work, iOS with
`BGAppRefreshTask` — which iOS runs opportunistically, so the time is a hint there, not a promise.

Notifications need runtime permission, requested when the toggle is switched on. Denial leaves the
toggle off rather than creating a setting that silently does nothing.
```

Update the test-count sentence under "Running tests" to the actual number reported by the full run, and add `:source:usps:jvmTest :source:webview:jvmTest` to the command if they aren't listed.

- [ ] **Step 2: Run the whole suite**

```bash
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest :source:ups:jvmTest \
          :source:usps:jvmTest :source:webview:jvmTest :ui:testAndroidHostTest \
          :app-android:assembleDebug --console=plain
```

Expected: PASS. Record the total test count for the README.

- [ ] **Step 3: Device verification**

Use the `verify` skill to build, install, and drive the app on an emulator:

1. Open Settings, enable a carrier source, enable "Daily update" — confirm the system permission dialog appears, and that granting it leaves the toggle on and the time row showing "8:00 AM".
2. Change the time via the picker; confirm it persists across an app restart.
3. Deny the permission on a fresh install (`adb shell pm revoke com.dgmltn.shiphappens android.permission.POST_NOTIFICATIONS`) and confirm the toggle stays off with the explanatory line.
4. Force the scheduled work rather than waiting for 8am:

```bash
adb shell dumpsys jobscheduler | grep -A3 com.dgmltn.shiphappens   # find the job id
adb shell cmd jobscheduler run -f com.dgmltn.shiphappens <jobId>
```

Confirm a grouped notification appears for a parcel whose status moved, and that tapping it opens that parcel's detail screen.

Write the findings to `docs/superpowers/qa/2026-08-18-daily-refresh-qa.md`, following the shape of `docs/superpowers/qa/2026-08-11-amzl-qa.md`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "[docs] Document the daily update feature and record QA findings"
```

- [ ] **Step 5: Squash merge**

Only after the suite is green and device QA is recorded:

```bash
git switch main
git merge --squash feat/daily-refresh-notifications
git commit   # write the message fresh: [data] Add a daily 8am refresh with status-change notifications
git branch -D feat/daily-refresh-notifications
```

**STOP after merging. Do not push — ask first.**

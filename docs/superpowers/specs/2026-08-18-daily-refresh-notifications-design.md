# Daily Refresh + Status-Change Notifications — Design

**Date:** 2026-08-18
**Status:** Approved

## Goal

Refresh every undelivered parcel once a day at a user-chosen local time (default 8:00 AM) without
the app being opened, and post a notification for each parcel whose status or ETA date changed.
This is the first background work in the app — `RefreshCoordinator` is explicitly foreground-only
("spec: no background schedulers in v1").

## Decisions (settled during brainstorming)

- **Change rule: status transition OR ETA-date change.** A parcel notifies when `status` moves to
  a different `TrackingStatus`, or when `etaDate` shifts to a different day. New scan events alone
  do not notify — a UPS package logs several a day and the shade would be useless.
- **One notification per changed parcel, grouped.** Android uses a group key plus a summary
  notification; iOS uses `threadIdentifier`. Tapping one deep-links to that parcel's detail screen.
- **Separate setting from refresh frequency.** A new "Daily update" toggle + time picker. The
  existing `RefreshFrequency` (15 min / 1 hour / Manual) keeps governing foreground staleness only;
  the two answer different questions and conflating them would make "Manual" silently disable
  background updates.
- **Toggle defaults off; enabling it triggers the permission prompt.** Android 13+
  `POST_NOTIFICATIONS` and iOS `UNUserNotificationCenter` both need runtime authorization, and a
  prompt needs a foreground Activity — the 8am worker has none. Denial flips the toggle back with
  an inline explanation, so there is never a toggle that reads on while doing nothing.
- **Expired sessions get one quiet notification per source per episode.** A parcel refresh that
  fails with `FailureReason.AUTH` posts a low-priority "UPS needs you to sign in again" that
  deep-links to the web login screen. Deduped by persisting the nagged source ids; the flag clears
  when that source next refreshes successfully, so one expiry produces one nag, not one per day.
- **Approach A — shared runner in `data`, thin platform adapters.** The candidate filter, change
  rule, and nag dedup live once in `data/commonMain` and are unit-testable on the JVM.
  Per-platform orchestration (rejected) would write the change rule twice; a change-event `Flow`
  off `RefreshCoordinator` (rejected) adds scope hazards inside a worker for no present benefit,
  and stays available later since this design is a strict subset of it.

## Known limitations, accepted

- **iOS produces zero notifications today.** `platformWebModule()` on iOS binds `NoWebScraper`
  (`source/webview/src/iosMain/.../WebModule.ios.kt`), so every parcel resolves to
  `RefreshOutcome.NoSource` and the runner finds nothing changed. The `BGTaskScheduler` and
  `UNUserNotificationCenter` code specified here is correct and dormant until a WKWebView scraper
  lands (`PlatformWebView.ios.kt` is still the "not available on iOS yet" placeholder).
- **iOS cannot honor a wall-clock time.** `BGAppRefreshTask` takes an `earliestBeginDate`, not a
  guarantee; the system may run it hours later or skip days. The iOS settings copy says
  "around 8:00 AM" rather than promising it.
- **Android Doze can defer the run.** On an idle device WorkManager may hold the job until the
  next maintenance window. Acceptable for a once-daily package check.

## Architecture

### 1. Change detection in `data` (common)

`ParcelRepository` learns to report what a refresh changed. New type:

```kotlin
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
    /** Worth telling the user about. */
    val isNotable: Boolean get() =
        (statusChanged && statusAfter != TrackingStatus.UNKNOWN) || (etaChanged && etaAfter != null)
}
```

`applySnapshot` already computes the merged row, so it knows both sides of the diff. Its public
signature stays `Boolean` (three call sites and `ApplySnapshotTest` depend on it); the diff comes
from a new private `applyAndDiff(id, snapshot, sourceId): ParcelChange?` that returns null **only**
when the row is missing. `applySnapshot` delegates: `applyAndDiff(...) != null`.

The guards in `isNotable` are belt-and-braces: `applySnapshot` never writes `UNKNOWN` over a known
status and never nulls an existing `etaDate`, so neither degenerate case should reach the notifier
anyway.

`RefreshOutcome` carries the extra data through `refreshRow`:

```kotlin
data class Success(val change: ParcelChange?) : RefreshOutcome   // null = row vanished mid-refresh
data class Failed(val reason: FailureReason, val sourceId: String) : RefreshOutcome
```

`RefreshSummary` gains three fields, all defaulted so existing construction sites and tests compile
unchanged:

```kotlin
data class RefreshSummary(
    val attempted: Int,
    val failed: Int,
    val firstFailureReason: FailureReason? = null,
    val changes: List<ParcelChange> = emptyList(),
    val authFailedSourceIds: Set<String> = emptySet(),
    val succeededSourceIds: Set<String> = emptySet(),
)
```

`succeededSourceIds` is what re-arms the sign-in nag: a source that refreshed something is signed in
again, so its nag flag clears and a later expiry notifies rather than staying silent forever.

The candidate filter in `refreshAll` is already exactly right for "undelivered packages": it
selects `dao.allActive()` (non-archived) minus `DELIVERED`. The daily run calls
`refreshAll(force = true)`, which bypasses the staleness cutoff and the `MANUAL` early-return, so
the foreground frequency setting cannot suppress it. A parcel that is undelivered at 8am and turns
out to be delivered still notifies, because candidates are chosen before the fetch.

### 2. `DailyRefreshRunner` in `data` (common)

One job: decide whether to run, run it, turn the summary into notifications.

```kotlin
class DailyRefreshRunner(
    private val repository: ParcelRepository,
    private val settings: SettingsRepository,
    private val registry: SourceRegistry,
    private val notifier: StatusNotifier,
) {
    suspend fun runOnce(): RefreshSummary
}
```

1. Read settings; if `dailyUpdateEnabled` is false, return `RefreshSummary(0, 0)` without touching
   the network (defensive — the scheduler should already be cancelled).
2. `repository.refreshAll(force = true)`.
3. For each `change` where `isNotable`, `notifier.notifyStatusChange(change)`.
4. For each source id in `authFailedSourceIds` not already in the persisted nag set:
   `notifier.notifySignInNeeded(...)` and add it. Remove from the nag set any source that refreshed
   successfully this run, so a re-login re-arms the nag.
5. Return the summary (the caller logs it; the worker uses it only to decide retry).

### 3. Platform boundaries

Two interfaces in `data/commonMain`, following the established `WebScraper`/`NoWebScraper` shape:

```kotlin
interface StatusNotifier {
    suspend fun notifyStatusChange(change: ParcelChange)
    suspend fun notifySignInNeeded(sourceId: String, sourceDisplayName: String)
}

interface DailyRefreshScheduler {
    fun schedule(at: LocalTime)
    fun cancel()
}
```

Both get no-op objects for platforms/tests that don't implement them, bound in `platformDataModule()`.

Notification **copy** is shared, not per-platform — a `NotificationText` object in `data/commonMain`
maps a `ParcelChange` to title/body ("Nike shoes", "Out for delivery — arriving Tue Aug 19"), so
Android and iOS say the same thing and the strings are unit-testable.

**Permission requesting is a UI concern**, not a data one: it needs a foreground Activity on
Android. It follows the `PlatformWebView` pattern — an `expect` composable-scoped helper in
`ui/commonMain`:

```kotlin
@Composable expect fun rememberNotificationPermissionController(): NotificationPermissionController
// interface: val isGranted: Boolean; fun request(onResult: (Boolean) -> Unit)
```

Android actual wraps `rememberLauncherForActivityResult(RequestPermission())` for
`POST_NOTIFICATIONS` (and reports `true` outright below API 33). iOS actual calls
`UNUserNotificationCenter.currentNotificationCenter.requestAuthorizationWithOptions`.

### 4. Android implementation

Lives in `data/androidMain` (so `platformDataModule()` binds it and no new wiring reaches `ui`),
except the manifest entries and the deep-link handling, which belong to `app-android`.

- **Scheduler:** `WorkManagerDailyRefreshScheduler`. Enqueues a **self-rescheduling one-time work**
  (`ExistingWorkPolicy.REPLACE`, unique name `daily-refresh`) with an initial delay computed to the
  next occurrence of the chosen local time. Chosen over `PeriodicWorkRequest` because a 24-hour
  period drifts across DST and cannot be re-anchored; the worker re-enqueues itself for the next
  day as its last act. WorkManager persists enqueued work across reboot, so no `BOOT_COMPLETED`
  receiver is needed. Constraint: `NetworkType.CONNECTED`.
- **Worker:** `DailyRefreshWorker : CoroutineWorker`, resolving `DailyRefreshRunner` from Koin
  (`KoinComponent`). Returns `Result.success()` after re-enqueuing tomorrow's run; returns
  `Result.retry()` (bounded by WorkManager's default backoff) only when the run threw, not when
  individual parcels failed. `HeadlessWebViewScraper` already hops to `Dispatchers.Main.immediate`
  itself, so WebView construction is safe from the worker's default dispatcher; the 15-minute
  `ScrapeThrottle` is irrelevant at a daily cadence.
- **Notifier:** `AndroidStatusNotifier` using `NotificationManagerCompat`, two channels —
  `package_updates` (default importance) and `sign_in` (low importance). Notification id is derived
  from the parcel id hash so a second change for the same parcel replaces the first rather than
  stacking. All updates share `GROUP_UPDATES` plus a `setGroupSummary(true)` summary notification.
- **Deep link:** content intent is a `PendingIntent` into `MainActivity` with a
  `shiphappens://parcel/{id}` data URI; `MainActivity` reads it and seeds the Nav3 back stack with
  the detail route. The sign-in notification targets the web-login route for that source.
- **Manifest:** `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`.
- **Dependency:** `androidx.work:work-runtime-ktx` added to the version catalog and to `data`'s
  Android source set.

### 5. iOS implementation

- **Info.plist:** `UIBackgroundModes: [fetch]` and `BGTaskSchedulerPermittedIdentifiers:
  [com.dgmltn.shiphappens.dailyrefresh]`, added to `app-ios/ShipHappens/Info.plist`.
- **Registration must happen before launch completes**, which is Swift's job: the SwiftUI app
  delegate calls `BGTaskScheduler.shared.register(forTaskWithIdentifier:)` and, inside the handler,
  invokes a Kotlin entry point exported from the shared framework
  (`IosDailyRefresh.run { success in ... }`, a `completionHandler`-shaped wrapper around
  `DailyRefreshRunner.runOnce()`), then calls `task.setTaskCompleted(success:)`.
- **Scheduler:** `BgTaskDailyRefreshScheduler` in `data/iosMain` sets `earliestBeginDate` to the
  next occurrence of the chosen local time and submits the request; `cancel()` calls
  `BGTaskScheduler.shared.cancel(taskRequestWithIdentifier:)`. The task is re-submitted at the end
  of each run, as iOS requires.
- **Notifier:** `IosStatusNotifier` posting `UNMutableNotificationContent` with a `nil` trigger,
  `threadIdentifier = "package_updates"` for grouping, and `userInfo["parcelId"]` for the deep link.

### 6. Settings UI

A new "Daily update" card in `SettingsScreen`, above the existing refresh-frequency control:

- Switch, label "Daily update", supporting text "Check undelivered packages every morning and
  notify you when something changes." Off by default.
- A time row, enabled only when the switch is on, showing the time ("8:00 AM") and opening a
  Material 3 `TimePicker` dialog.
- On iOS the supporting text reads "…every morning (iOS decides exactly when)".

Flow when the user flips the switch on: `SettingsViewModel` asks the permission controller; on
grant it persists `dailyUpdateEnabled = true` and calls `scheduler.schedule(time)`; on denial it
leaves the setting false and surfaces an inline message ("Notifications are off for Ship Happens —
enable them in system settings"). Flipping off persists false and calls `scheduler.cancel()`.
Changing the time while enabled re-schedules.

`AppSettings` gains `dailyUpdateEnabled: Boolean = false` and `dailyUpdateTime: LocalTime =
LocalTime(8, 0)` (persisted as `"HH:mm"`), plus `signInNaggedSourceIds: Set<String> = emptySet()`
(persisted as a comma-joined string), with matching setters on `SettingsRepository`.

## Error handling

| Situation | Behavior |
| --- | --- |
| No enabled source for a parcel | Skipped silently — `RefreshOutcome.NoSource` is not a failure (existing rule) and never notifies. |
| Source returns `AUTH` | One sign-in notification per source per expiry episode; parcel keeps its old data. |
| Source returns `NETWORK`/`UNKNOWN` | Silent. The daily job doesn't retry mid-run; tomorrow's run tries again. |
| Scrape times out (30s each) | Counts as a failure for that parcel only; the loop continues. |
| Permission revoked in system settings after enabling | Posts are silently dropped by the OS. Settings re-checks `isGranted` on resume and shows the toggle as off-with-explanation. |
| Runner throws | Worker returns `Result.retry()`; WorkManager backs off. The next day's run is still enqueued at the end of a successful attempt. |
| Zero notable changes | No notifications at all — the quiet path is the common one. |

## Testing

JVM (`:data:jvmTest`), using the existing `FakeSource` and an in-memory `FakeStatusNotifier`:

- `ParcelChange` detection: status transition notifies; ETA-date shift notifies; identical snapshot
  does not; `UNKNOWN` status-after never notifies; a row deleted mid-refresh yields no change.
- `DailyRefreshRunner`: disabled setting → no refresh, no notifications; one notification per
  notable change and none for unchanged parcels; delivered and archived parcels are never
  candidates; `AUTH` failure nags once and not again on the next run; a later success clears the nag
  so a subsequent `AUTH` nags again.
- `NotificationText`: title/body for each status transition and for an ETA-only change.
- `SettingsRepository`: daily-update fields round-trip, including the `"HH:mm"` and nag-set encodings.

`:ui:testAndroidHostTest`, with fake scheduler/permission controller:

- Enabling with permission granted persists true and schedules; enabling with permission denied
  persists nothing, schedules nothing, and surfaces the inline message; disabling cancels; changing
  the time while enabled re-schedules.

Manual/device verification (the `verify` skill): enable the toggle, confirm the system prompt, then
force the worker with `adb shell cmd jobscheduler run -f com.dgmltn.shiphappens <jobId>` and confirm
a grouped notification appears and deep-links to the right parcel.

## Out of scope

In-app "changed since you last looked" indicators; notification actions (archive/mute from the
shade); quiet hours; per-parcel notification opt-out; more than one run per day; a WKWebView scraper
for iOS.

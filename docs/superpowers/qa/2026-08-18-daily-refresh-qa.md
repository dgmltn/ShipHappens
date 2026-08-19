# Daily Refresh + Notifications — Device QA

**Date:** 2026-08-18
**Device:** `Small_Phone` emulator, API 36, 720×1280, device clock 22:45 PDT (America/Los_Angeles)
**Build:** `feat/daily-refresh-notifications`, `:app-android:installDebug`

## Verified

| # | Check | Result |
| --- | --- | --- |
| 1 | "Daily update" card renders in Settings below Sync, toggle off by default | PASS |
| 2 | Flipping the toggle on raises the system notification prompt | PASS |
| 3 | Denying leaves the toggle **off** and shows "Notifications are turned off for Ship Happens…" | PASS |
| 4 | Allowing enables the toggle and reveals the "Check at 8:00 AM" row | PASS |
| 5 | Enabling schedules `DailyRefreshWorker`; `dumpsys jobscheduler` showed `TIME=+9h14m39s` from 22:45 → **08:00 next day** | PASS |
| 6 | Time picker opens at the stored time, sets 6:30 AM, and re-books: `TIME=+7h43m35s` from 22:46 → **06:30** (unique work REPLACEd, one pending job) | PASS |
| 7 | Forced run refreshes an undelivered parcel and posts a notification | PASS |
| 8 | Notification grouping: per-parcel notification + `GROUP_SUMMARY` under `groupKey=com.dgmltn.shiphappens.UPDATES`, channel `package_updates` | PASS |
| 9 | Notification copy matches `NotificationText`: title "AmzlTest", body "Delivered" | PASS |
| 10 | Tapping the notification deep-links to that parcel's detail screen (`shiphappens://parcel/{id}`) | PASS |
| 11 | Worker re-books the next day's run in its `finally` block, at the **current** stored time | PASS |
| 12 | Second forced run posts nothing (the now-DELIVERED parcel is no longer a candidate) and re-books again | PASS |

## How the end-to-end run was staged

A parcel only produces a notable change when the daily pass is its *first* successful fetch, but
`ListViewModel` refreshes immediately after an add. To put the first fetch inside the worker:

1. Enabled the Amazon Logistics source.
2. Turned wifi/data off (`adb shell svc wifi disable; svc data disable`).
3. Added `AmzlTest` / `TBA333593378975` — the add-path refresh failed silently, parcel stayed UNKNOWN.
4. Restored network, backgrounded the app.
5. `adb shell cmd jobscheduler run -f -u 0 -n androidx.work.systemjobscheduler com.dgmltn.shiphappens <jobId>`

The worker scraped track.amazon.com headlessly from the background, wrote UNKNOWN → DELIVERED, and
posted. Note the `-n androidx.work.systemjobscheduler` namespace argument — without it,
`cmd jobscheduler run` reports "Could not find job".

## Findings

- **Time picker is not themed.** `androidx.compose.material3.TimePicker` inside an `AlertDialog`
  renders in stock Material 3 purple, against the app's warm off-white/ink palette. Functionally
  correct, visually foreign. Not fixed here — theming it means extending `ShipTheme` to supply an
  M3 `ColorScheme`, which is a design decision beyond this feature's scope.
- **Headless WebView works from a background worker**, confirming the design assumption: no
  foreground activity was present and `HeadlessWebViewScraper`'s own hop to `Dispatchers.Main`
  was sufficient.

## Not device-verified

- **Sign-in nag** (`FailureReason.AUTH` → one notification per expiry episode). Reproducing it
  needs a genuinely expired UPS/Amazon session; covered by `DailyRefreshRunnerTest`
  (`auth_failure_nags_once_then_stays_quiet`, `a_later_success_rearms_the_nag`).
- **iOS.** `xcodebuild` builds the app with the `BGTaskScheduler` registration and Info.plist
  entries in place, but the pass cannot produce notifications there until a WKWebView scraper
  exists — `platformWebModule()` on iOS still binds `NoWebScraper`.
- **The real 8am firing.** Only forced runs were exercised; Doze deferral behavior on an idle
  device is unobserved.

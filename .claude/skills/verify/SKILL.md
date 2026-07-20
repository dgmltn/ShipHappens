---
name: verify
description: Build, launch, and drive Ship Happens on an Android emulator to verify UI changes end-to-end with screenshots.
---

# Verifying Ship Happens on Android

## Build & launch

```bash
./gradlew :app-android:installDebug            # applicationId com.dgmltn.shiphappens
adb -s <serial> shell am start -n com.dgmltn.shiphappens/com.dgmltn.shiphappens.android.MainActivity
adb -s <serial> exec-out screencap -p > shot.png
```

- Doug's physical Pixel may be attached but is fingerprint-locked — don't try to unlock it;
  use an emulator instead: `~/Library/Android/sdk/emulator/emulator -list-avds` (e.g.
  `Small_Phone`, 720×1280 — screenshot pixels map 1:1 to `input tap` coordinates).
  Boot with `-no-snapshot-save`, poll `getprop sys.boot_completed`, kill with `adb emu kill`.

## Getting data on screen

Fresh installs are empty. Enable the **Demo data** source: gear button (top right of list)
→ toggle "Demo data" → back. It seeds 7 parcels covering every status step, ETAs relative
to today (see `source/demo/.../DemoSource.kt`).

- **Never-refreshed parcel:** add a manual package with a valid-looking UPS number the demo
  source doesn't know (e.g. `1Z999AA10123456999`) — its refresh fails NOT_FOUND, so it stays
  UNKNOWN/no-ETA. Expect a "Couldn't refresh — network error" toast.
- `adb shell input text` drops spaces — use `%s` or single words for names.

## Gotchas

- Demo refreshes complete in <1 frame; transient per-parcel loading states are not
  observable live — cover them with the gated `FakeSource` pattern in `ListViewModelTest`
  (`card_flags_refreshing_while_refresh_in_flight`).
- Pull-to-refresh only triggers from the top of the list; a swipe-down mid-scroll just scrolls.
- To catch fast UI, `adb shell screenrecord` then `ffmpeg -vf fps=30` frame extraction works.

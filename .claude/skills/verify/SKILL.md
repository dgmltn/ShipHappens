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

Fresh installs are empty, and there is no demo/seed source anymore (removed 2026-07-16).
Add parcels through the **"Add a package" card** at the top of the Active list: tap the
name/tracking fields, type, tap ✓.

- **Never-refreshed parcel:** a valid-format but made-up number (e.g. `1Z999AA10123456784`)
  adds instantly and stays UNKNOWN/no-ETA — the row reads "Waiting for first update". The
  add-path refresh is best-effort and shows no failure toast.
- **Populated parcel:** only a real tracking number gets live data — the app scrapes the
  carrier's site (UPS/USPS/Amazon webview sources), which needs network and takes seconds.
- `adb shell input text` drops spaces — use `%s` or single words for names.

## Gotchas

- Live scrapes take seconds and depend on carrier sites — don't assert on transient
  per-parcel loading states from the emulator; cover them with the gated `FakeSource`
  pattern in `ListViewModelTest` (`card_flags_refreshing_while_refresh_in_flight`).
- Pull-to-refresh only triggers from the top of the list; a swipe-down mid-scroll just scrolls.
- To catch fast UI, `adb shell screenrecord` then `ffmpeg -vf fps=30` frame extraction works.

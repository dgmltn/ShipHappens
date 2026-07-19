# Launcher Icon & Splash Screen — Design

**Date:** 2026-07-19
**Status:** Approved (visual direction), pending implementation plan

## Overview

Ship Happens ships with no launcher icon (Android shows the default robot) and no
splash screen (a bare `#F7F6F3` window background). This work gives the app a real
brand mark on both Android and iOS, a matching splash / launch screen, and carries
the mark's blue into the main-screen title so the app "remembers" its splash.

Everything derives from one glyph: the **package/box** (isometric cube) already used
throughout the app's list rows and design mockup
([resources/design/Parcels.dc.html](../../../resources/design/Parcels.dc.html)).

## Brand mark (source of truth)

A single SVG path set, reused for every output (Android vectors, iOS PNGs, in-app):

```
<path d="M12 3 3 7.5v9L12 21l9-4.5v-9L12 3Z"/>   <!-- box outline -->
<path d="M3 7.5 12 12l9-4.5"/>                     <!-- top-face seam -->
<path d="M12 12v9"/>                               <!-- front vertical seam -->
```
Stroked (round join/cap), no fill. viewBox `0 0 24 24`.

**Colors:**
- Brand blue `#1E3A8F` — the deep link-blue from the design mockup. Not yet a
  `ShipColors` constant (the existing `archiveAccent` is the brighter `#2563EB`);
  this work adds it as `ShipColors.brand`.
- Paper `#F7F6F3` (`ShipColors.bg`)
- White `#FFFFFF`
- Ink `#17150F` (`ShipColors.ink`) — current title color, being replaced by blue

### Chosen treatments (from visual brainstorming)

| Surface | Decision |
| --- | --- |
| Launcher icon | White box glyph on a full-bleed **brand-blue** field (option **B**) |
| Splash / launch screen | **C2**: paper `#F7F6F3` background, blue rounded **tile** holding the white box, "Ship Happens" wordmark in **blue** below |
| Main-screen title | Recolor the "Ship Happens" header wordmark from ink to **brand blue** (recolor only — no tile) |

## Android deliverables

### 1. Adaptive launcher icon (minSdk 26 — no legacy PNG fallback)

New resources under `app-android/src/main/res/`:

- `drawable/ic_launcher_foreground.xml` — vector, 108×108 viewport. The white box
  glyph scaled/centered to sit comfortably inside the ~66dp adaptive safe zone
  (glyph ≈ 46–50dp). Stroke white.
- `drawable/ic_launcher_monochrome.xml` — same box glyph as a single-color layer
  for Android 13+ themed icons.
- `values/ic_launcher_background.xml` (or a color) — background color `#1E3A8F`.
- `mipmap-anydpi-v26/ic_launcher.xml` and `mipmap-anydpi-v26/ic_launcher_round.xml`
  — `<adaptive-icon>` with `<background>` = blue, `<foreground>` = box vector,
  `<monochrome>` = box vector.

Manifest change ([app-android/src/main/AndroidManifest.xml](../../../app-android/src/main/AndroidManifest.xml)):
add `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"`
to `<application>`.

### 2. Splash screen (androidx.core:core-splashscreen)

- Add the `androidx.core:core-splashscreen` dependency (version via the version
  catalog, consistent with existing toolchain pins).
- New theme `Theme.ShipHappens.Starting` (parent `Theme.SplashScreen`) in
  [themes.xml](../../../app-android/src/main/res/values/themes.xml):
  - `windowSplashScreenBackground` = `#F7F6F3` (paper)
  - `windowSplashScreenAnimatedIcon` = the box foreground vector
  - `windowSplashScreenIconBackgroundColor` = `#1E3A8F` (blue)
  - `postSplashScreenTheme` = `Theme.ShipHappens`
  - `windowSplashScreenBrandingImage` = a "Ship Happens" wordmark vector, blue
- Point the manifest activity/application `android:theme` at the starting theme.
- Call `installSplashScreen()` in
  [MainActivity.onCreate](../../../app-android/src/main/kotlin/com/shiphappens/android/MainActivity.kt)
  **before** `super.onCreate()` / `setContent`.

**Known platform constraint:** on Android 12+ the system masks the splash icon to a
**circle**, so C2's rounded-square tile renders as a blue *circle* with the white box.
This is accepted — it stays cohesive (blue mark on paper). The wordmark branding image
shows at the bottom on Android 12+; on API 26–30 (core-splashscreen back-compat) only
the centered icon shows. No custom post-splash composable — the native splash is enough.

## iOS deliverables

App-icon and launch-screen assets live under `app-ios/ShipHappens/` (XcodeGen
auto-includes files in the `sources: [ShipHappens]` dir).

### 1. App icon

- New `Assets.xcassets/AppIcon.appiconset` containing a single **1024×1024 PNG**
  (single-size app icon; deployment target 16 supports it): white box glyph on the
  full-bleed brand-blue field (the same composition as Android option B, unmasked —
  iOS applies its own superellipse mask).
- `project.yml` target settings: `ASSETCATALOG_COMPILER_APPICON_NAME: AppIcon`.

### 2. Launch screen

- `LaunchScreen.storyboard` with:
  - Background color = paper `#F7F6F3`
  - Centered image view = the blue rounded **tile** with white box (PNG @1x/@2x/@3x
    in an `Assets.xcassets` image set)
  - Below it, the "Ship Happens" wordmark rendered as an image (blue, Hanken 800) —
    baked to a PNG so the launch screen matches the app font without bundling a font
    for a storyboard label
- `project.yml` info: replace `UILaunchScreen: {}` with
  `UILaunchStoryboardName: LaunchScreen` (keep `CFBundleDisplayName: Ship Happens`).

## Main-screen title change

In [ListScreen.kt](../../../ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListScreen.kt)
`Header`, change the "Ship Happens" `Text` color from `ShipColors.ink` to the brand
blue. Add the blue as a named `ShipColors` value (e.g. `brand = Color(0xFF1E3A8F)`) in
[Theme.kt](../../../design/src/commonMain/kotlin/com/shiphappens/design/Theme.kt) rather
than hard-coding a hex, and reuse that same constant for the Android icon/splash colors
where a Compose color is needed. Everything else in the header (date label, sub, gear)
is unchanged. Update the `@Preview`s if they assert on color.

## Asset generation

- **Android** foreground/monochrome/wordmark are hand-authored vector XML — no raster
  step.
- **iOS** needs rasterized PNGs (app icon 1024, tile @1x/@2x/@3x, wordmark @1x/@2x/@3x).
  Generate from the source SVG via a scripted, repeatable step (e.g. `rsvg-convert` or
  a headless render) and commit both the source SVG(s) and the generated PNGs. Add a
  short note under `scripts/` so the assets can be regenerated if the mark changes.

## Testing / verification

- **Android:** build and install; confirm the launcher icon appears (adaptive shape on
  a 12+ device, themed-icon monochrome when the user enables it) and the splash shows
  paper bg + blue circle/box + wordmark, dissolving into the list. Use the `verify`
  skill (emulator screenshots) for the icon-grid, splash, and recolored header.
- **iOS:** build in the simulator; confirm the home-screen app icon and the launch
  screen (paper + tile + wordmark).
- **Unit:** the header recolor is trivial; keep/adjust existing `ListScreen` previews.
  No new business logic to unit-test.

## Out of scope

- Making blue a broader theme color across the app (only the title picks it up).
- Animated / Lottie splash content beyond the native icon reveal.
- Notification / adaptive-icon shortcut variants, monochrome tinting beyond the standard
  Android 13 themed-icon layer.
```

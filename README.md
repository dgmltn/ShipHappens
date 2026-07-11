# Ship Happens

A package-tracking app for Android and iOS, built once in Kotlin Multiplatform / Compose
Multiplatform and shipped natively on both platforms. Add a tracking number (manually, or by
detecting one you just copied), and Ship Happens polls the right carrier or aggregator, shows a
timeline of scan events, and tells you when a package is arriving. Delivered packages archive with
undo; settings hold per-source API keys and a refresh cadence.

The full visual spec — every screen, state, and interaction (empty state, list, detail, archive
swipe, settings cards, clipboard-import card, toasts) — lives in
[`design/Parcels.dc.html`](design/Parcels.dc.html). Open it in a browser for the canonical UI
reference.

## Module map

```
domain/        Domain types: Parcel, Carrier, TrackingStatus/Event/Snapshot. No dependencies.
data/          Room 3 database, ParcelRepository, SettingsRepository (DataStore), clipboard
               import manager, carrier detection, SourceRegistry, Koin DI wiring.
design/        Design system: ShipTheme, ShipColors, font resources (Hanken + mono), and the
               canonical HTML visual spec (Parcels.dc.html).
source/
  api/         The plugin contract: TrackingSource, SourceConfig, SourceResult, SeedingSource.
               Every source module depends only on this.
  trackingmore/ TrackingMore v4 aggregator source (Ktor client).
  demo/        Seeds the design's 7 sample parcels; used by the Settings "Demo data" toggle.
  ups/         UPS carrier source (stub proving the plugin contract).
  usps/        USPS carrier source (stub).
  fedex/       FedEx carrier source (stub).
ui/            Compose Multiplatform screens (list, detail, settings), Navigation 3, ViewModels,
               and appModules() — the single place all Koin modules are assembled.
app-android/   Android application shell: MainActivity, Koin bootstrap with androidContext.
app-ios/       iOS application shell (SwiftUI entry point hosting the shared Compose UI),
               generated via XcodeGen from app-ios/project.yml.
```

Dependency direction is one-way: `source/*` depends on `source/api` (and `domain` for domain
types) but never on `data` or `ui`; `data` depends on `source/api` for the `TrackingSource`
contract but not on any specific source; `design` depends only on `domain`; `ui` wires everything
together in `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt`.

## Build & run

### Android

```bash
./gradlew :app-android:assembleDebug --console=plain
```

Produces `app-android/build/outputs/apk/debug/app-android-debug.apk` (applicationId
`com.shiphappens`). Install with `adb install` or run the `app-android` configuration from
Android Studio / IntelliJ.

### iOS

The Xcode project is generated, not checked in. Install [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`) once, then:

```bash
cd app-ios
xcodegen generate
cd ..
xcodebuild -project app-ios/ShipHappens.xcodeproj -scheme ShipHappens \
  -destination 'generic/platform=iOS Simulator' -configuration Debug build
```

Or just open `app-ios/ShipHappens.xcodeproj` in Xcode after `xcodegen generate` and run normally —
the scheme's pre-build script runs `./gradlew :ui:embedAndSignAppleFrameworkForXcode` for you, so
the shared Compose UI framework is always rebuilt before the Swift shell links against it.

Re-run `xcodegen generate` any time `app-ios/project.yml` changes; the generated `.xcodeproj` is
gitignored and recreated by `xcodegen generate`, while `app-ios/ShipHappens/Info.plist` and
`app-ios/project.yml` are tracked in git.

## Running tests

```bash
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest \
          :source:demo:jvmTest :source:trackingmore:jvmTest :source:ups:jvmTest \
          :ui:testAndroidHostTest --console=plain
```

Note `:ui`'s task is `testAndroidHostTest`, not `testDebugUnitTest` — the UI module's unit tests
run on the Android-host test source set. Current suite: 65 tests across 7 modules (model 5, data
29, api 2, demo 4, trackingmore 5, ups 3, ui 17), all passing.

## How to add a tracking source

The plugin boundary is `TrackingSource` in `source/api`. Adding a new carrier or aggregator never
touches `domain` or `data`:

1. **Implement `TrackingSource`** in a new `source/<name>` module (copy `source/ups` as a
   template: same `build.gradle.kts` shape — `api(projects.source.api)` plus Koin). Provide a
   `SourceDescriptor` (id, display name, `SourceKind.CARRIER` or `.UNIVERSAL`, config fields),
   `detectCarrier(trackingNumber)` for cheap local format recognition, `track(...)`, and
   `testConnection(...)`. Implement `SeedingSource` too if the source should offer demo/seed data.
   **Important:** `source/ups` sets `implemented = false` in its descriptor because it is a stub —
   a real source must leave `implemented` at its default (`true`), or `SourceRegistry` will skip it
   when resolving which source refreshes a parcel.
2. **Expose a Koin module** — one line, same pattern as every existing source:
   ```kotlin
   val myNewSourceModule: Module = module { single { MyNewSource() } bind TrackingSource::class }
   ```
3. **Register it** in `appModules()` in
   `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt` — add the module to the list
   returned there. `SourceRegistry` (in `data`) picks up every bound `TrackingSource`
   automatically; nothing in `data` needs to change.

Add `:source:<name>` to `settings.gradle.kts` and give the module the same
`kotlinMultiplatform` + `android.kotlin.multiplatform.library` shape as its siblings.

## Settings & API keys

`SettingsRepository` (`data`) persists source configs, auto-clipboard-import, and refresh
frequency in a Jetpack/Multiplatform DataStore `Preferences` file. **Keys are stored
unencrypted, by design** — this is a v1 personal-use app with no server component; there is no
keychain/keystore integration. Do not put production or shared credentials in the app.

# Ship Happens — Design Spec

Date: 2026-07-10
Status: Approved pending user review
Design source: Claude Design project `b7199339-b47b-4111-a58c-bfe98ab9a168`, file `Parcels.dc.html`
(local copy imported to `design/Parcels.dc.html`)

## 1. What we're building

A parcel-tracking app ("Ship Happens") for Android and iOS, built with Kotlin
Multiplatform + Compose Multiplatform, following Google's layered UI/Data
architecture with unidirectional data flow. The defining requirement: tracking
providers ("sources") are pluggable — adding a new source is a new Gradle
module implementing one interface, never a change to core code.

### Screens (from the Claude Design)

1. **List** — header with date + "N arriving soon"; Active/Archived segmented
   tabs; parcel cards showing carrier-colored icon, name, carrier + status
   text, and a days-left indicator (ring style by default); swipe-left to
   archive delivered parcels with an undo toast; Restore button on archived
   cards; empty states for both tabs; a pending-import card when a tracking
   number is found on the clipboard; a manual-add card with carrier picker
   (Auto-detect / UPS / USPS / FedEx).
2. **Detail** — carrier-colored header (name, headline like "Arrives in 2
   days"); estimated-delivery card; map-shaped placeholder card with latest
   checkpoint location text overlaid (no real map SDK in v1); 5-step tracking
   timeline (Label created → Shipped → In transit → Out for delivery →
   Delivered) with per-step timestamps; tracking-number card; delivered-photo
   card omitted in v1.
3. **Settings** — "Universal API" card for TrackingMore (enable toggle, API
   key field, test connection); "Direct carrier APIs" cards rendered
   dynamically from each source's config spec (UPS, USPS, FedEx as stubs);
   Sync section: clipboard auto-import toggle, refresh frequency
   (15 min / 1 hour / Manual). Footer note: keys stored on-device only.

### Design tokens

- Fonts: Hanken Grotesk (UI), JetBrains Mono (tracking numbers), bundled via
  Compose resources.
- Palette: background `#F7F6F3`, ink `#17150F`, muted `#8A857C` / `#A8A296`,
  hairline `#ECEAE3`, urgent/archive `#C2410C`, delivered `#1F7A4D` on
  `#E7F3EC`, TrackingMore teal `#0F766E`.
- Carrier brand colors: USPS `#1E3A8F`, UPS `#5A3A22`, FedEx `#5A1B9A`
  (tints `#EEF2FB` / `#F4EFE9` / `#F3ECFA`).
- Shape language: 18–20px card radii, 11–14px control radii; cards are white
  on the warm paper background with 1px `#ECEAE3` borders.

## 2. Decisions made during brainstorming

| Topic | Decision |
|---|---|
| Source implementations in v1 | TrackingMore fully real (v4 API); UPS/USPS/FedEx as stub modules proving the plugin contract; a Demo source seeds sample data |
| Refresh | Foreground-only: on app-foreground and pull-to-refresh, honoring the frequency setting as a staleness threshold ("Manual" = only explicit refresh). No WorkManager/BGTaskScheduler in v1 |
| Carrier modeling | Open `Carrier` data class provided by sources — not an enum. Well-known carriers get brand styling; unknown ones get deterministic fallback styling |
| API key storage | Ordinary DataStore Preferences, unencrypted (user's explicit choice; personal-use app) |
| Detail map/photo | Map stays a decorative placeholder card with real location text; delivered photo omitted |
| Clipboard import | Both platforms, checked on app-foreground when enabled; accept the OS clipboard-access notices |
| First run | Empty state; a Demo source toggle in Settings seeds the design's sample parcels |
| Module naming | `ui` (shared Compose UI), `app-android` (thin Android entry), `app-ios` (Xcode shell) |
| Room version | Room 3.0.0 (`androidx.room3`, stable 2026-07-01). Fallback if blocked: Room 2.8.4 (mechanical package rename) |

## 3. Versions (verified 2026-07-10; pin exact patches from Maven Central at implementation)

| Library | Version |
|---|---|
| Kotlin | 2.4.0 |
| Compose Multiplatform | 1.11.x (latest patch) |
| Ktor | 3.5.x (latest patch) |
| Koin | 4.2.x (latest patch; koin-bom) |
| Room | 3.0.0 (`androidx.room3`) |
| KSP | matching Kotlin 2.4.0 |
| kotlinx-coroutines / -serialization / -datetime | latest stable |
| AndroidX Lifecycle ViewModel (KMP) | latest stable |
| Navigation 3 for Compose Multiplatform | latest stable compatible with CMP 1.11 |
| AndroidX DataStore (KMP) | latest stable |
| AGP | latest stable compatible with Kotlin 2.4.0 |

Android minSdk 26, target/compile latest stable SDK. iOS deployment target per
current KMP template default.

## 4. Module structure

```
ShipHappens/
├── ui/                    # KMP: shared Compose UI, ViewModels, Navigation 3, theme
│                          #   targets: androidTarget + iosArm64 + iosSimulatorArm64
│                          #   exports the iOS framework (MainViewController())
├── app-android/           # Android application module: manifest, MainActivity,
│                          #   Application class, Koin startup. Depends on :ui
├── app-ios/               # Xcode project: Swift entry point, hosts the shared
│                          #   UIViewController, calls Koin bootstrap
├── core/
│   ├── model/             # Pure Kotlin domain models. Zero dependencies
│   └── data/              # ParcelRepository, Room 3 DB, DataStore settings,
│                          #   SourceRegistry, RefreshCoordinator, clipboard import
└── source/
    ├── api/               # TrackingSource contract + SourceDescriptor/ConfigSpec.
    │                      #   Depends only on :core:model
    ├── trackingmore/      # Real TrackingMore v4 implementation (Ktor)
    └── demo/              # Demo source: design's sample parcels, simulated progress
```

Dependency rule: `ui → core:data → source:api → core:model`. Source
implementation modules depend on `:source:api` (+ Ktor where needed) and are
referenced only by the app entry modules' DI setup. Gradle version catalog in
`gradle/libs.versions.toml`; shared build config via convention in root
build files (no build-logic module — YAGNI at this size).

## 5. Domain model (`:core:model`)

- `Parcel` — `id: String`, `name: String`, `trackingNumber: String`,
  `normalizedTracking: String` (uppercased, whitespace/dash-stripped; dedupe
  key), `carrier: Carrier`, `sourceId: String?` (pinned source),
  `status: TrackingStatus`, `etaDate: LocalDate?`, `etaTime: LocalTime?`,
  `events: List<TrackingEvent>`, `latestLocation: String?`,
  `isArchived: Boolean`, `createdAt: Instant`, `lastRefreshedAt: Instant?`.
- `Carrier(code: String, displayName: String, accentColorHex: String?)` —
  open type. `WellKnownCarriers` object supplies UPS/USPS/FedEx with brand
  colors; unknown carriers get `accentColorHex = null` and the UI assigns
  deterministic fallback styling (hash of code into a small curated palette).
- `TrackingStatus` — `LABEL_CREATED, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY,
  DELIVERED, EXCEPTION, UNKNOWN`. The timeline renders the first five as
  steps; EXCEPTION/UNKNOWN render as status text with the timeline held at
  the last known step.
- `TrackingEvent(timestamp: Instant, description: String, location: String?,
  status: TrackingStatus?)`.
- `TrackingSnapshot(status, events, etaDate, etaTime, latestLocation)` — a
  source's answer for one refresh.

## 6. Source contract (`:source:api`)

```kotlin
interface TrackingSource {
    val descriptor: SourceDescriptor
    fun detectCarrier(trackingNumber: String): Carrier?
    suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot>
    suspend fun testConnection(config: SourceConfig): SourceResult<Unit>
}

data class SourceDescriptor(
    val id: String,               // "trackingmore", "ups", "demo"
    val displayName: String,
    val kind: SourceKind,         // UNIVERSAL or CARRIER — drives settings grouping
    val accentColorHex: String?,  // card badge color (e.g. TrackingMore teal)
    val configSpec: List<ConfigField>,
)

data class ConfigField(
    val key: String, val label: String, val placeholder: String,
    val isSecret: Boolean,
)

data class SourceConfig(val enabled: Boolean, val values: Map<String, String>)

sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val reason: FailureReason, val message: String?) : SourceResult<Nothing>
}
enum class FailureReason { AUTH, NETWORK, NOT_FOUND, RATE_LIMITED, UNKNOWN }
```

- Settings renders one card per registered source from `descriptor` — fields,
  toggle, test-connection button — with zero per-source UI code. UNIVERSAL
  sources render in the "Universal API" section, CARRIER sources under
  "Direct carrier APIs".
- Sources receive their current `SourceConfig` via constructor/provider from
  core (sources never touch DataStore directly).
- **Adding a source** = new module implementing `TrackingSource` + exposing a
  Koin module binding it into the source set (Koin `bind` with qualifier
  collection; `SourceRegistry` collects `getAll<TrackingSource>()`), + one
  line in the app entry's module list.
- `SourceRegistry` (in `:core:data`) resolves refreshes: a parcel's pinned
  `sourceId` first; otherwise the first *enabled* source whose
  `detectCarrier` recognizes the number; UNIVERSAL sources act as fallback
  for any number.

### `:source:trackingmore`

TrackingMore v4 (`api.trackingmore.com/v4`, `Tracking-Api-Key` header):
create tracking on first add, then get by number+carrier on refresh; uses
TrackingMore's carrier auto-detect endpoint inside `detectCarrier` only when
local heuristics miss (local regexes first to stay offline-friendly);
`testConnection` hits a cheap authenticated endpoint. Ktor client +
kotlinx-serialization; response fixtures captured for tests. Maps
TrackingMore statuses (`pending/transit/pickup/delivered/exception/expired…`)
onto `TrackingStatus`.

### `:source:demo`

A `kind = UNIVERSAL` source with no config fields, disabled by default. When enabled, seeds the design's seven sample parcels through the
normal repository path and advances their status deterministically over time.
Serves as the template for future UPS/USPS/FedEx modules: those ship as
stub modules with real `configSpec`s (UPS client id/secret, USPS consumer
key/secret, FedEx api/secret key) whose `track()` returns
`Failure(UNKNOWN, "Not implemented yet")`.

## 7. Data layer (`:core:data`)

- **Room 3** database `ShipHappensDb`: `ParcelEntity`,
  `TrackingEventEntity` (FK to parcel, cascade delete). DAOs expose `Flow`s.
  Platform drivers per Room KMP setup (`Room.databaseBuilder` expect/actual
  for context/path).
- **Settings** via DataStore Preferences (KMP): per-source `SourceConfig`
  (JSON-serialized), `autoClipboardImport: Boolean` (default true),
  `refreshFrequency: FIFTEEN_MIN | ONE_HOUR | MANUAL` (default FIFTEEN_MIN).
  Unencrypted by decision. Display options (days-left style = Ring, carrier
  tint = accent dot, sort = soonest-first) are constants in v1.
- **`ParcelRepository`** (single source of truth):
  `observeParcels(archived: Boolean): Flow<List<Parcel>>` (sorted: active =
  undelivered by ETA then delivered; archived by archive time),
  `addParcel(name, trackingNumber, carrier?)` (dedupes on normalized number;
  triggers immediate refresh), `archive(id)` / `restore(id)`,
  `refresh(id)`, `refreshAll(force: Boolean)`.
- **`RefreshCoordinator`** observes app lifecycle (CMP lifecycle) and calls
  `refreshAll(force = false)` on foreground; repository skips parcels
  refreshed within the frequency window, skips DELIVERED parcels, and
  fans out per-source with per-parcel error isolation.
- **`ClipboardImportManager`**: `expect/actual ClipboardReader`
  (Android `ClipboardManager`, iOS `UIPasteboard`). On foreground when
  enabled: read text → `SourceRegistry.detectCarrier` (built-in regexes for
  UPS `1Z…`, USPS 20–26-digit/intl, FedEx 12/15/20–22-digit run first) →
  dedupe against existing normalized numbers and a last-dismissed memory →
  emit `PendingImport(carrier, tracking)` to the list screen.

## 8. UI layer (`:ui`, entries in `app-android` / `app-ios`)

- Navigation 3, three destinations: `ParcelList`, `ParcelDetail(id)`,
  `Settings`. Detail/Settings use slide-up transitions matching the design's
  sheet animation.
- One ViewModel per screen (KMP `androidx.lifecycle.ViewModel`, injected via
  `koin-compose-viewmodel`), each exposing a single immutable `UiState` via
  `StateFlow` (`stateIn(WhileSubscribed(5s))`), events as ViewModel methods.
  Strict UDF; composables are stateless below the screen level.
- `ListViewModel` owns: tab selection, parcel card view-states (days-left
  ring math, urgency coloring at ≤1 day, "Out for delivery today"
  overrides), pending-import card, manual-add card state + carrier picker,
  swipe-archive + undo toast (single toast slot with auto-dismiss).
- `DetailViewModel`: timeline step derivation from status/events, delivery
  window text, location text for the placeholder map card.
- `SettingsViewModel`: source cards from descriptors + configs, test
  connection (result → toast/status line), sync toggles, demo source toggle.
- Theme: design tokens from §1; light theme only in v1 (design defines no
  dark palette).
- `app-android`: single `MainActivity`, edge-to-edge, `Application` starts
  Koin. `app-ios`: SwiftUI `App` wrapping `MainViewController()`; Koin
  started from Kotlin `initKoin()` called in the Swift entry.

## 9. Error handling

- Refresh failures never delete or blank data: parcels keep their last
  snapshot; failures surface as a toast ("Couldn't refresh — check your
  TrackingMore key" for AUTH, generic for NETWORK) at `refreshAll`
  granularity, silent per-parcel otherwise.
- `NOT_FOUND` on a fresh add keeps the parcel with status UNKNOWN and status
  text "Waiting for first update".
- No enabled source for a parcel's carrier: parcel persists with UNKNOWN
  status; Settings is the fix, not an error dialog.
- `testConnection` results render inline in the source card status line
  ("Connected · syncing" green / "Not connected" muted) plus a toast, as in
  the design.
- Room/DataStore errors are programmer errors → fail fast in debug; Ktor
  timeouts (10s connect/30s request) map to NETWORK.

## 10. Testing

- `kotlin-test` + `kotlinx-coroutines-test` everywhere; tests live in each
  module's `commonTest` (JVM-run where platform-neutral).
- `:core:model`: normalization + carrier fallback-styling determinism.
- `:core:data`: repository tests on in-memory Room (JVM target); refresh
  staleness logic with a fake clock; clipboard detection regex table tests;
  registry resolution precedence tests with fake sources.
- `:source:trackingmore`: Ktor `MockEngine` with captured v4 fixtures —
  status mapping, error mapping (401 → AUTH, 429 → RATE_LIMITED), create-
  then-get flow.
- `:ui`: ViewModel tests against fake repository (days-left math, undo flow,
  pending-import lifecycle). Compose UI tests deferred to a later iteration.
- Definition of done for v1: all module tests green; app builds and runs on
  Android emulator and iOS simulator; demo source exercises the full
  pipeline end-to-end.

## 11. Out of scope for v1 (explicit)

- Background sync (WorkManager / BGTaskScheduler)
- Real UPS/USPS/FedEx API implementations
- Real map rendering and geocoding; delivered photos
- Push/local notifications
- Dark theme
- Encrypted credential storage
- Editing a parcel's name/carrier after creation

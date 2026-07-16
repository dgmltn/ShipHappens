# Remove Demo and FedEx Sources — Design

**Date:** 2026-07-16
**Status:** Approved

## Goal

Delete the demo-data source and the FedEx stub source now that three real sources
(UPS, USPS, Amazon) exist, and prune every framework hook those two were the only
users of. Full removal, not soft-unregistration: no dead code left behind;
everything is re-addable from git history if a real aggregator or FedEx API source
lands later.

## Decisions (settled during brainstorming)

- **FedEx: remove the source, keep the carrier.** `WellKnownCarriers.FEDEX`, the
  detection regex in `BuiltInCarrierDetection`, the manual-picker entry, and FedEx
  preview strings all stay — FedEx packages remain addable/brandable as
  unrefreshable cards. Only the stub source (the fake "Direct API coming soon"
  Settings row) is deleted.
- **Credential-config machinery: remove.** FedEx was the only source with
  `configSpec`; the Settings credential UI and its plumbing go with it.
- **Seeding and UNIVERSAL hooks: prune both.** Demo was the only `SeedingSource`
  and the only UNIVERSAL-kind source.

## 1. Module deletions

- Delete `source/demo/` and `source/fedex/` directories.
- Remove their `include(...)` lines from `settings.gradle.kts`.
- Remove their deps from `ui/build.gradle.kts` and their imports/entries from
  `AppModules.kt`. Settings lists exactly UPS, USPS, Amazon.

## 2. `:source:api` prunes (with knock-on edits)

- **`ConfigField` + `SourceDescriptor.configSpec`** — deleted. Knock-on:
  `WebViewBasedSource.descriptor` stops passing `configSpec = emptyList()`.
- **`TrackingSource.testConnection`** — deleted from the interface and from
  `WebViewBasedSource` (its only remaining implementation).
- **`SourceConfigProvider` + `SettingsRepository.current()`** — deleted, plus the
  `bind SourceConfigProvider::class` in `DataModule`. `SettingsViewModel.onTest`
  was the only consumer.
- **`SeedParcel` + `SeedingSource`** — deleted, plus
  `ParcelRepository.seedEnabledSources()` and its call site.
- **`SourceKind` enum + `SourceDescriptor.kind`** — deleted entirely. With
  UNIVERSAL gone it is a one-value enum carrying no information.
  `SourceRegistry.sourceFor` loses its UNIVERSAL fallback and becomes:
  pinned source → first detecting source → null.
- **Kept deliberately: `SourceConfig(enabled, values)`.** The `values` map stores
  the web-login `loggedIn` flag consumed by `SettingsViewModel` (status display
  and `onSignOut`); it is live plumbing, not config-field machinery.

## 3. Settings UI slimming

- `SettingsViewModel`: delete `FieldUi`, `SourceCardUi.fields`,
  `SourceCardUi.endpointText`, the `configured` check, `SettingsUiState.universal`,
  `onField`, and `onTest`. Status ladder becomes:
  `!enabled → "Not connected"`, `!implemented → "Coming soon"` (reworded from
  "Direct API coming soon"; the branch survives for web sources on iOS where
  `WebScraper.isAvailable` is false), else `"Connected · syncing"`.
- `SettingsScreen`: delete the "Universal API" section label + list, the
  credential text-field rendering, the endpoint/Test-connection row, and the
  `onField`/`onTest` parameters. Previews updated: universal/demo cards and
  `FieldUi` examples removed; the FedEx preview card becomes Amazon.

## 4. Deliberately untouched

`WellKnownCarriers.FEDEX`, `BuiltInCarrierDetection`'s FEDEX regex and case, the
manual carrier picker (built from `WellKnownCarriers.all`), FedEx strings in
List/Detail previews, and all `SourceConfig`-based enabled/login persistence.

## 5. Tests

- **`SettingsViewModelTest`** — the major rework. Demo/FedEx fixtures are replaced
  with the real web sources over `NoWebScraper` (plus the existing awaitState
  pattern). Tests covering credential fields (`onField`/`onTest`), demo seeding,
  and the universal section die with those features. Toggle, sign-out, and
  section-content tests retarget to UPS/USPS/Amazon.
- **Source tests** (`UpsSourceTest`, `UspsSourceTest`, `AmazonSourceTest`) — drop
  `SourceKind.CARRIER`, `configSpec`, and `testConnection` assertions.
- **`SourceRegistryTest`/`FakeSource`** — adjust for the removed `kind` field and
  UNIVERSAL fallback; the fallback test is deleted, pinned/detect tests stay.
- **`ListViewModelTest`** — unchanged (FedEx stays a carrier; picker list is
  still `[null, ups, usps, fedex, amazon]`).
- Full verification sweep (all module jvmTests, `:ui:testAndroidHostTest`,
  `:source:webview:compileAndroidMain`, and `:ui:compileKotlinIosSimulatorArm64`
  — the broadest iOS target, exercising the api-signature changes everywhere)
  at the end.

## 6. Data note — no migration

Existing dev installs may hold demo-seeded parcels; after removal they are
ordinary deletable cards. Two demo entries carry realistic USPS/UPS-shaped
numbers, so refreshing them routes to real scrapes and yields NOT_FOUND —
harmless. Parcels pinned to `sourceId = "demo"`/`"fedex"` fall through
`sourceFor`'s pinned lookup and resolve to detection or null. No migration code.

## Out of scope

- Removing the FedEx carrier identity (kept by decision).
- Any future FedEx API source or universal-aggregator source (git history holds
  the deleted scaffolding).
- Onboarding/sample-data replacement for the deleted demo seeds.

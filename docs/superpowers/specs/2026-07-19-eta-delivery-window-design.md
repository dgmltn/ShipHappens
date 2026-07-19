# ETA Delivery Window — Design

**Date:** 2026-07-19
**Status:** Approved (design), pending implementation plan
**Branch:** feature/launcher-icon-splash (implementation branch TBD)

## Problem

Carriers commonly expose an estimated delivery *window* — a start–end time range such as
"arrives today 3:30 PM – 5:30 PM" (Amazon), and UPS/USPS scheduled-delivery times that are
really the *end* of a window. The app can only model a single ETA time (`etaTime: LocalTime?`),
rendered as "by 8:00 PM". There is no way to hold or show a range.

This increment makes the app **able to hold and display a delivery window** across every layer
(domain → persistence → UI). It deliberately does **not** add any new window *parsing* — Amazon
and other scrapers are untouched and continue to yield what they do today.

## Decisions (from brainstorming)

1. **Model:** Replace the single `etaTime` with a start/end pair (not additive, not a wrapper type).
2. **Scope:** Model + persistence + UI plumbing only. No new scraper/parser window parsing.
3. **Migration:** Room **auto-migration** (preserve parcel data; carry the old column's value
   forward). *Amended after final review:* originally specified as `@DeleteColumn`; changed to
   `@RenameColumn` so existing `etaTime` values survive as `etaWindowEnd` — see "Persistence &
   migration" below.
4. **Range format:** Collapsed shared meridiem — `3:00 – 5:00 PM` (uppercase PM, spaced en-dash,
   matching the existing "by 8:00 PM" styling).

## Model

Across three parallel types, `etaTime: LocalTime?` is replaced by:

```kotlin
val etaWindowStart: LocalTime? = null   // null => open-ended ("by <end>")
val etaWindowEnd:   LocalTime? = null   // the cutoff / "by" time
```

- `domain/src/commonMain/kotlin/com/shiphappens/domain/Tracking.kt` — `TrackingSnapshot`
- `domain/src/commonMain/kotlin/com/shiphappens/domain/Parcel.kt` — `Parcel`
- `data/src/commonMain/kotlin/com/shiphappens/data/db/Entities.kt` — `ParcelEntity`
  (two nullable TEXT columns holding ISO-8601 `LocalTime` strings)

### Semantics

| start | end | meaning | render |
|-------|-----|---------|--------|
| null  | X   | open-ended cutoff | `by X` |
| X     | Y   | true window | `X – Y` |
| null  | null| unknown | (omitted) |

A lone cutoff maps to `start=null, end=cutoff`. This is semantically correct for the existing
carriers: `UpsApiParserTest` documents `"sdt":"14:30:00"` as *"end of delivery window"*, so
today's single time becomes `etaWindowEnd` with `start=null` — **zero behavior change** for
UPS/USPS ("by 2:30 PM" still renders identically).

## Persistence & migration

- `data/src/commonMain/kotlin/com/shiphappens/data/db/ShipHappensDb.kt`: bump `version = 1` → `2`.
- Add an `AutoMigrationSpec` nested class annotated `@RenameColumn(tableName = "parcels",
  fromColumnName = "etaTime", toColumnName = "etaWindowEnd")`, referenced via
  `@Database(autoMigrations = [AutoMigration(from = 1, to = 2, spec = ...)])`. The rename carries
  each existing cutoff forward as the window *end* (which is what it already meant); Room
  auto-adds the remaining `etaWindowStart` column. All other parcel data (name, tracking #,
  events, status) is preserved.
- **Note:** this migration recreates the `parcels` table, and `tracking_events` holds an
  `ON DELETE CASCADE` FK to it. That is safe only because Room 3 never issues
  `PRAGMA foreign_keys = ON` and `BundledSQLiteDriver` defaults FK enforcement off. Enabling
  foreign keys in a database-builder callback would make this migration delete every tracking
  event.
- Baseline schema `data/schemas/com.shiphappens.data.db.ShipHappensDb/1.json` already exists; the
  build will emit `2.json`. (`exportSchema` defaults true; `room.schemaLocation` is configured in
  `data/build.gradle.kts`.)
- `data/src/commonMain/kotlin/com/shiphappens/data/db/Mappers.kt`: `toEntity` / `toDomain` carry
  both fields instead of `etaTime`.
- `data/src/commonMain/kotlin/com/shiphappens/data/ParcelRepository.kt:88` (`applySnapshot` merge):
  the window is merged **atomically** — if a snapshot carries either bound, both are taken from it;
  if it carries neither, both are preserved from the existing row. *Amended after final review:*
  originally specified as independent per-field preserve-on-null, which would let a stale start
  attach to a newer end and render a window the carrier never quoted (e.g. a stored `3:00–5:00 PM`
  plus a later "by 10 PM" becoming `3:00 PM – 10:00 PM`). The neighbouring `status` / `etaDate` /
  `latestLocation` fields keep their independent preserve-on-null semantics.

## Scrape contract (no new parsing)

- `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/ScrapedTracking.kt`:
  the `@Serializable` DTO field `etaTime` → `etaWindowStart` / `etaWindowEnd`; `toSnapshot()`
  parses both.
- **No webview JS is touched.** Verified: no injected JS blob emits `etaTime`. `UspsWebSpec` and
  `AmazonWebSpec` emit only `etaDate`; `UpsWebSpec` emits neither. So renaming the DTO field is
  safe — there is no JSON producer of `etaTime`.
- The only `etaTime` producers are the **API parsers**, which set `etaWindowEnd` (start stays null):
  - `source/ups/src/commonMain/kotlin/com/shiphappens/source/ups/UpsApiParser.kt:62`
  - `source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsApiParser.kt:61`
- **Amazon and all real window parsing are explicitly out of scope.** `AMAZON_EXTRACTION_JS` is
  unchanged; Amazon still yields date-only. A follow-up can extend it to parse the
  "3:30pm – 5:30pm" text and emit `etaWindowStart`/`etaWindowEnd`.

## UI rendering

New formatter in `ui/src/commonMain/kotlin/com/shiphappens/ui/util/Formatters.kt`:

```kotlin
fun formatEtaWindow(start: LocalTime?, end: LocalTime?): String?
```

- both set → `"3:00 – 5:00 PM"` — collapse the meridiem when start and end share it; show both
  when they differ (`"11:30 AM – 1:30 PM"`).
- end only → `"by 5:00 PM"` (unchanged from today).
- neither → `null`.

`ui/src/commonMain/kotlin/com/shiphappens/ui/detail/DetailViewModel.kt:74-82`: `windowText` uses
the new formatter, producing e.g. `"Today · 3:00 – 5:00 PM"` or the unchanged `"Sun, Jul 13 · by
8:00 PM"`. The `deliveredAt` path (actual delivery time from the last DELIVERED event) is
unchanged. `DetailScreen`'s label ("Estimated delivery") and single `windowText` slot are unchanged.

## Testing

Update existing tests that reference `etaTime`:
- `ui/.../detail/DetailViewModelTest.kt`
- `source/ups/.../UpsApiParserTest.kt`, `source/usps/.../UspsApiParserTest.kt`
- `source/webview/.../ScrapedTrackingTest.kt`
- `data/.../ApplySnapshotTest.kt`, `data/.../db/ParcelDaoTest.kt`

Add:
- `formatEtaWindow` unit tests: both-set + shared meridiem (collapsed), both-set + differing
  meridiem (both shown), end-only ("by"), null/null (null result).
- A DAO round-trip asserting both window columns persist and reload.
- A migration sanity check that a v1 row survives to v2 with parcel fields intact (if the test
  harness supports migration testing; otherwise rely on the exported `2.json` schema).

## Out of scope / follow-ups

- Amazon window parsing (`AMAZON_EXTRACTION_JS`) — the motivating case; separate increment.
- Any new UI affordance beyond the existing single-line "Estimated delivery" text.
- List/home card time display (still day-granularity countdown ring).

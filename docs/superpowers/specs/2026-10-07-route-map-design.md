# Route Map — Design

**Date:** 2026-10-07
**Status:** Approved (conversational); awaiting written-spec review

## Goal

Replace the decorative map placeholder card on the detail screen
(`DetailScreen.kt`, "Map placeholder card") with a stylized, offline-drawn map of the
parcel's **trip history**: every scanned location the carrier reported, connected in order,
with each stop tappable to reveal what happened there.

Purpose, in Doug's words: trip history — look back at every stop the parcel passed through.
Accuracy of the line between stops is not a goal (carriers report hub scans, not roads);
the stops themselves should be right.

## Decisions

| Topic | Decision |
|---|---|
| Placement | Inside the existing 152dp card on the detail screen; no separate screen |
| Style | Stylized Compose `Canvas` drawing — no tile SDK, no API key, works offline |
| Geocoding | Bundled offline table first; platform geocoder fallback for misses |
| Extent | Fit to the route's bounds over a world land outline (international routes OK) |
| Destination | Route ends at the latest scan; no delivery address or device location |
| History UI | Tap a stop for a callout with its city and events |
| Coordinate storage | Separate geocache table keyed by place string (not on events) |
| No locations | Card keeps today's placeholder (e.g. Amazon order-page parcels) |
| One location | Single pin, minimum span applied |
| Platforms | Android and iOS actuals both written (see platform-parity preference) |

## Data available today

`TrackingEvent.location: String?` is persisted on `TrackingEventEntity.location`. Coverage:

- FedEx — full city trail (travel-history rows), e.g. Foster City → South San Francisco →
  Sacramento → Carlsbad.
- UPS, USPS, DHL eCommerce — per-event location from carrier APIs ("CITY, ST" shapes).
- AMZL — per-event `city`/`stateProvince`; field names still provisional.
- Amazon order page — typically none.

No coordinates exist anywhere; geocoding is the only new data dependency.

## Architecture

### New module `:geo` (KMP: android, iOS, jvm)

- `data class LatLng(val lat: Double, val lng: Double)`
- `interface Geocoder { suspend fun lookup(place: String): GeoResult }`
  - `GeoResult` is `Found(LatLng)`, `NotFound`, or `Unavailable` (transient: offline,
    rate-limited, platform error). The distinction drives caching (below).
- `PlaceKey.normalize(raw: String): String?` — uppercase, collapse whitespace, strip ZIP /
  ZIP+4, strip trailing country (`US`, `USA`, `UNITED STATES`), insert the comma in
  `"SOUTH SAN FRANCISCO CA"`, map full state names to postal codes. Returns null for
  blank/garbage input. The normalized key is both the bundled-table key and the cache key.
- `BundledGeocoder` — US Census Gazetteer places, loaded once from a compact binary
  resource into a sorted array; binary search by key. Returns `Found` or `NotFound`, never
  `Unavailable`.
- `PlatformGeocoder` — `expect`/`actual`:
  - Android: `android.location.Geocoder` (async `getFromLocationName` listener API on 33+,
    blocking call on `Dispatchers.IO` below). `Geocoder.isPresent() == false` → `Unavailable`.
  - iOS: `CLGeocoder.geocodeAddressString`, one request at a time (Apple throttles).
  - JVM: always `Unavailable` (tests and host only).
- `ChainGeocoder(bundled, platform)` — bundled `Found` wins; otherwise defer to platform.
- `buildRoute(events: List<TrackingEvent>, coords: Map<String, LatLng>): List<RouteStop>`
  — pure:
  - sort events oldest first;
  - drop events whose location doesn't normalize or isn't in `coords`;
  - collapse *consecutive* events at the same key into one `RouteStop(key, displayName,
    latLng, events)` (non-consecutive revisits stay separate stops);
  - `displayName` is the carrier's original string from the stop's first event,
    title-cased when the carrier shouted it.
- Projection helpers (pure): bounds, minimum span, antimeridian shift, equirectangular
  projection with longitude scaled by `cos(midLat)`, fit-into-rect with padding.

`:geo` depends on `:domain` (for `TrackingEvent`) and nothing app-level.

### `:data` additions

- `GeoCacheEntity(key: String /*PK*/, lat: Double?, lng: Double?, resolvedAt: Long)` —
  null lat/lng records a definitive miss.
- `ShipHappensDb` version 3 → 4 via `AutoMigration(from = 3, to = 4)` (table add only).
- `GeoDao`: `observe(keys)`, `upsert(entity)`.
- `GeoRepository.observe(places: List<String>): Flow<Map<String, LatLng>>`
  - normalizes and de-duplicates keys;
  - emits cached hits immediately;
  - for keys that are uncached, or cached misses older than **30 days**, resolves via
    `ChainGeocoder` **serially**, at most **10 platform lookups per collection**, writing
    each result back so the Room flow re-emits;
  - `Found` → upsert coords; `NotFound` → upsert miss; `Unavailable` → write nothing
    (retry next time the screen opens);
  - resolution runs in the collector's scope (the detail ViewModel), so leaving the screen
    cancels it. Background refresh never geocodes.
- Wired through the existing DI module alongside `ParcelRepository`.

### `:ui` additions

- `DetailUiState.route: RouteUi?`
  - `RouteUi(stops: List<StopUi>, delivered: Boolean, description: String)`
  - `StopUi(name: String, latLng: LatLng, events: List<StopEventUi>)`
  - `StopEventUi(whenText: String, description: String)` — formatted with the existing
    `TimeFormat` helpers, newest first.
  - `route == null` when zero stops resolve → placeholder card unchanged.
- `DetailViewModel` combines `observeParcel` with
  `geoRepository.observe(parcel.events.mapNotNull { it.location })` (flatMapLatest on the
  distinct location list) and runs `buildRoute`.
- `RouteMap(route, accent, modifier)` composable replaces the placeholder `Box` contents
  when `route != null`, keeping the card's size, clip, background and border, and the
  bottom-left location label.

## Rendering

- **Bounds:** stops' lat/lng bounds, padded 15% per side, minimum span ~3° in each axis
  (so a single pin or a same-metro route doesn't zoom to street level), then expanded to
  the card's aspect ratio.
- **Antimeridian:** if the stops' longitude span exceeds 180°, add 360 to negative
  longitudes before computing bounds and projecting (both stops and outline).
- **Land:** Natural Earth 110m land polygons, pre-simplified, stored as a compact binary
  resource. Projected into a `Path` once per bounds (`remember(bounds, size)`); polygons
  outside the bounds are culled. Fill: a slightly darker shade of the card background
  (`0xFFEEECE6`), no coastline stroke.
- **Route:** polyline through stops in the carrier accent color, round caps/joins.
- **Stops:** origin = hollow ring; intermediates = small filled dots; latest = larger filled
  dot; when delivered, the latest dot carries a check glyph.
- **Location label:** unchanged (bottom-left, `state.locationText`).

## Interaction

- Tap selects the nearest stop within 24dp of the touch (`pointerInput` + hit test against
  projected stop positions); selected stop gets a ring.
- A callout overlays the card: stop name, then its events newest first
  (`whenText · description`), max ~4 lines then "+N more". It flips left/right and
  above/below to stay inside the card bounds.
- Tapping empty space or the selected stop again dismisses. Selection resets when the
  route's stop list changes.

## Accessibility

Card `contentDescription`: "Route: Foster City → South San Francisco → Sacramento →
Carlsbad, 4 stops" (abbreviate the middle when there are more than 5 stops). Each stop is
not separately focusable in v1.

## Bundled assets

- `scripts/build-geo-assets` (one-off, run by hand, not by Gradle):
  - downloads the Census Gazetteer "Places" national file (public domain) and Natural
    Earth 110m land (public domain);
  - writes `geo/src/commonMain/composeResources/files/places.bin` (sorted keys + float
    lat/lng) and `land110m.bin` (polygon rings as float pairs, simplified);
  - expected sizes ≈ 400KB and ≈ 60KB.
- Outputs are committed; builds never hit the network. The script records source URLs and
  vintage in a header comment so assets can be regenerated.

## Error handling

- Platform geocoder exceptions → `Unavailable`; never crash, never cache.
- Corrupt/missing asset → `BundledGeocoder` returns `NotFound` for everything and the land
  layer is skipped; the route still draws on a plain background. Logged once.
- NaN/out-of-range coordinates from any source are dropped before caching.

## Testing

Unit (commonTest / jvmTest):

- `PlaceKey.normalize`: ZIP and ZIP+4, case, `US`/`USA`, missing comma, full state
  names, blank input, already-normalized input.
- `BundledGeocoder` against a small fixture asset; `ChainGeocoder` precedence with fakes.
- `buildRoute`: ordering, consecutive collapse, non-consecutive revisit, unresolved skip,
  empty input.
- Projection: fit, minimum span, aspect expansion, antimeridian shift.
- `GeoRepository` with in-memory Room on the JVM and a fake platform geocoder: cached hit
  emits without lookup, miss cached, 30-day miss retry, `Unavailable` not cached, 10-lookup
  cap, cancellation stops lookups.
- `DetailViewModel`: route null with no locations; route fills in as cache emits.

UI previews: no route (placeholder), single pin, FedEx 4-stop route, delivered, callout
open, international route crossing the antimeridian.

Device: `/verify` on the emulator with seeded events, then a live FedEx parcel on Doug's
Pixel; reinstall a clean build afterwards.

## Delivery

One plan, two increments, each merged separately:

1. **Geo foundation** — `:geo` module, assets + script, `GeoCacheEntity` + DB v4,
   `GeoRepository`, `buildRoute`, projection helpers, tests. No UI change.
2. **Route map UI** — `DetailUiState.route`, `RouteMap` rendering, tap callout,
   accessibility, previews, device QA.

## Out of scope

- Delivery address, home location, or device location as a route endpoint.
- Road-following paths or animated travel.
- A full-screen map, zoom, or pan.
- Geocoding during background refresh.
- Per-stop accessibility focus.

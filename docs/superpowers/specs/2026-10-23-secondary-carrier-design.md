# Secondary Carrier — Design

**Date:** 2026-10-23
**Status:** Draft; awaiting Doug's approval

## Goal

A parcel is often carried by two services: the one Doug entered it under, and a second one
the first hands it to. Amazon orders delivered by Amazon Logistics (AMZL) carry a `TBA…`
number; DHL eCommerce parcels enter the postal network under a USPS number. Today a parcel
is bound to exactly one source, so the second carrier's scans, cities, and ETA never reach it.

Purpose, in Doug's words: "as detailed information as possible … the thing I'm most
interested in is filling in gaps in details." The "via USPS" / "via AMZL" label is a
byproduct, not the point.

## Decisions

| Topic | Decision |
|---|---|
| Discovery | The handoff is found by the primary source's scrape, never typed by the user |
| Adding parcels | Unchanged: Doug still enters the Amazon order ID or the DHL number |
| Fetching | A refresh tracks the primary, then the handoff number through its own source |
| Timeline | One merged timeline; the rail stays, with each step's scans listed beneath it (Doug) |
| Conflicts | The carrier with the newest scan wins status, location, delay wording (my call, Doug deferred) |
| ETA | The delivering (secondary) carrier's date wins when it has one; else the primary's (my call) |
| Label | "Amazon via Amazon Logistics" / "DHL eCommerce via USPS" on the list card and detail header |
| Second number | Shown on the detail screen under its own carrier's name |
| Settings | The secondary source must be enabled in Settings, same rule as any source |
| Platforms | All in the shared layer; nothing platform-specific |

## What exists today

- `TrackingSource.track(number, carrier)` returns a `TrackingSnapshot`; `SourceRegistry`
  picks one source per parcel (`sourceFor`) and detects a carrier from a number
  (`detectCarrier`, backed by `Carrier.numberPattern`).
- `ParcelRepository.refreshRow` tracks once and calls `applyAndDiff`, which merges the
  snapshot into the row (UNKNOWN/null never clobber; events replace wholesale when
  non-empty) and produces one `ParcelChange` for notifications.
- `WellKnownCarriers`: USPS claims `92…` 22-digit numbers; AMAZON_LOGISTICS claims
  `TBA\d{9,15}`; DHL_ECOMMERCE claims the `420…` 30-digit shape.
- The Amazon order page's preloaded tracking sheet (read since 2026-10-23) has a
  `Tracking ID: TBA335777783614` row before its event rows.
- The DHL eCommerce API package carries `dspName: "USPS"` and
  `deliveryConfirmationNumber: "9261234500000000000042"` (fixture in `DhlEcsApiParserTest`).
  That number is the USPS one.
- UPS, USPS, FedEx and AMZL report no handoff today. FedEx SmartPost → USPS exists in the
  world but nothing on the scraped page has been seen carrying it; out of scope.
- DB is at version 4 (Room auto-migrations). `tracking_events` rows have no origin column.

## Design

### Domain

```kotlin
data class Handoff(val carrier: Carrier, val trackingNumber: String)

data class TrackingSnapshot(
    …existing…,
    /** The carrier the primary says now holds the parcel, with its number; null = none seen. */
    val handoff: Handoff? = null,
)

data class TrackingEvent(
    …existing…,
    /** Carrier code of the source that reported this scan; null = the parcel's own carrier. */
    val carrierCode: String? = null,
)

data class Parcel(
    …existing…,
    val handoff: Handoff? = null,
)
```

A source only *reports* a handoff. It must not resolve it to a source or fetch it.

### Reporting a handoff

- **Amazon** — the sheet reader sends the `Tracking ID:` row's text as a new
  `DomCard.handoffText`. `resolveAmazonCards` extracts the token after "Tracking ID" and
  keeps it only when `WellKnownCarriers.detect` claims it with a carrier other than
  AMAZON (today that is AMZL). Unclaimed text yields no handoff.
- **DHL eCommerce** — `DhlEcsApiParser` reads `dspName` and `deliveryConfirmationNumber`.
  Every DHL number embeds a USPS-shaped tail, so the number alone cannot gate the handoff.
  Rule (my call): `dspName` must name a well-known carrier other than DHL_ECOMMERCE, and
  that carrier must claim `deliveryConfirmationNumber`; otherwise no handoff. The partner
  is named before the parcel physically reaches it, so a NOT_FOUND from the secondary is
  expected early on and is quiet (see Refresh step 4).
- A handoff whose carrier equals the parcel's own carrier is dropped.

### Persistence (DB version 5)

- `parcels` gains `handoffCarrierCode: String?` and `handoffTrackingNumber: String?`.
  Carrier name/color are not stored; the code is resolved through `WellKnownCarriers` at
  read time (unknown code → dropped, parcel reads as no handoff).
- `tracking_events` gains `carrierCode: String?` (null = the parcel's own carrier).
- Both are Room auto-migrations with nullable columns; nothing to backfill.

### Refresh

`refreshRow` becomes:

1. Track the primary as today. On failure, stop as today (no secondary attempt; a parcel
   with a stored handoff keeps what it has).
2. Take the handoff from the primary snapshot, else the one already stored on the row (a
   page that no longer prints the number must not forget it).
3. Resolve a source for the handoff number via the registry's number-based detection
   (`sourceFor` over an ad-hoc carrier + number, honoring enabled-ness). If none is
   enabled, persist the primary snapshot plus the handoff and finish. The "via" label still
   shows; no second fetch happens.
4. Track the secondary. On failure (including NOT_FOUND before the handoff has happened),
   reuse the row's stored secondary-tagged events as its snapshot (status UNKNOWN, no ETA),
   so a flaky secondary cannot blank its history. A secondary failure never counts toward
   `RefreshSummary.failed` or raises a toast; the primary succeeded.
5. `mergeSnapshots(primary, secondary, secondaryCarrier)` — a pure commonMain function —
   yields one `TrackingSnapshot`; `applyAndDiff` persists it, producing one
   `ParcelChange`, so notifications see one before/after.

**Event rows are replaced per carrier (my call).** Today a non-empty snapshot replaces the
parcel's events wholesale. From now on `applyAndDiff` replaces only the rows whose
`carrierCode` matches a carrier the snapshot carries fresh events for (null = primary), and
leaves other carriers' rows alone. So:

- the "More details on …" web page (`WebDetailViewModel` → `applySnapshot`), which only
  ever sees the primary, refreshes the primary's rows and keeps the secondary's;
- a secondary that is disabled or failing leaves its earlier rows in place;
- a primary scrape with no events keeps the stored primary rows, as today.

`applySnapshot` also persists a handoff the viewed page reports, so the label can appear
without waiting for the next background refresh. Status/ETA from a primary-only
`applySnapshot` follow today's never-clobber rules; the newest-scan rule is re-applied on the
next full refresh.

The refreshing indicator covers both fetches. Worst case is two scrape timeouts
(2 × 30 s) per parcel; accepted.

### `mergeSnapshots` rules

- **Events:** secondary events are tagged with the secondary carrier code; primary events
  stay untagged. Union, sorted ascending. Two rows are duplicates when they fall within
  the same minute and classify to the same stage, or when their descriptions are equal
  ignoring case and punctuation; keep the secondary's row (it is the physical carrier's
  own wording), unless only the primary's has a location.
- **Newest scan:** the side whose newest event is later. No events on one side → the other.
  No events on either side → primary.
- **Status:** the newest side's status when it is not UNKNOWN, else the other's. DELIVERED
  from either side always wins.
- **Location:** the newest side's `latestLocation`, else the other's, else the newest
  event with a location after merging.
- **Delay note:** the newest side's note, else the other's.
- **ETA date and window:** the secondary's date when non-null, with its window; else the
  primary's date and window. The window follows the date it came with; never mixed.
- **Handoff:** the primary's, else the stored one.

Everything above is unit-tested with Amazon + AMZL and DHL + USPS fixtures.

### UI

- **List card:** carrier line reads "Amazon via Amazon Logistics" (`carrierName` already
  flows through `ListUiState`; the view model formats the label). Accent color stays the
  primary's.
- **Detail header:** same label.
- **Detail body:** a second tracking-number card, "Amazon Logistics tracking number",
  under the existing one.
- **Timeline (Doug):** the five-step rail stays. Under each step, the scans that classify
  to that step are listed as small rows, oldest first, so the rail reads top to bottom as
  the trip: "In transit" → "Arrived San Francisco · Thu, Oct 22" → "Departed San Francisco ·
  Thu, Oct 22" → "Arrived San Diego · Fri, Oct 23" → "Out for delivery". Arrive and depart
  scans at one city are separate rows (Doug). Row rules (my calls):
  - a row reads `verb city · day`. The verb comes from a small shared vocabulary over the
    scan's wording (arrived, departed/left, processed, …), a pure function beside the
    existing status vocabulary so it is unit-tested, not per-carrier; USPS "Arrived at USPS
    Regional Facility" → "Arrived", Amazon "Package left the shipper facility" → "Departed";
  - when no verb matches, the row shows the carrier's wording verbatim, then the city;
  - a scan with no city shows its wording and the day;
  - nothing collapses: every scan is a row;
  - a scan whose wording classifies to no stage sits under the step of the latest classified
    scan before it, or under the first step when none precedes it;
  - rows that came from the secondary carrier end with a small carrier tag, only when the
    parcel has a handoff. Single-carrier parcels get no tags.
  Nothing new is stored for this: it is a projection of `Parcel.events`, as the rail and the
  route map already are. `DetailUiState` gains `TimelineStepUi.scans: List<ScanUi>` where
  `ScanUi(text: String, carrier: String?)`.
- **Route map:** unchanged; it reads event locations, which now include both carriers'.
- **"More details on …" button:** unchanged (primary). The builder may add a second button
  for the secondary when its source is web-capable; not required.

### Settings

No new settings. A disabled secondary source means no secondary fetch, mirroring the
existing NoSource rule (not a failure, no toast).

## Non-goals

- Typing a secondary number by hand, or editing the discovered one.
- De-duplicating a parcel Doug also added under its secondary number as a separate parcel.
- Handoffs for UPS, USPS, FedEx (SmartPost), or AMZL.
- Chains longer than two carriers.

## Anti-goals

- Notifying twice for one state change because two carriers reported it.
- A stale "via X" label on a parcel whose carrier never handed it off (only report a
  handoff the page/API actually names).
- Letting a secondary fetch failure erase history the parcel already had.

## Left to the builder

- Exact dedup window and description normalization, within the rules above.
- Whether `Handoff` lives in `domain` beside `Carrier` or in `Tracking.kt`.
- The Amazon sheet's "Tracking ID" row parsing details (prefix text, spacing).
- How `SourceRegistry` exposes number-based source lookup for the secondary.
- Timeline scan-row and carrier-tag styling; the exact verb vocabulary.
- A second web-details button for the secondary carrier.

## Validation

- Unit: merge rules, handoff extraction for Amazon and DHL, repository refresh with fake
  sources (primary success + secondary success / failure / disabled).
- Device: the light-bulbs order (112-0087174-7649828) should show "Amazon via Amazon
  Logistics", the TBA number, and AMZL's per-scan rows merged with the order page's.
  A DHL parcel in the postal stream should show "DHL eCommerce via USPS" with USPS scans.

# Amazon ETA Delivery Window Parsing — Design

**Date:** 2026-07-19
**Status:** Approved (design), pending implementation plan
**Branch:** feat/amazon-eta-window

## Problem

The Amazon tracker page quotes a delivery *window* in its status line — "Arriving today
3:00 PM - 5:00 PM". The app can already hold and render such a window end-to-end
(`etaWindowStart`/`etaWindowEnd` through DTO → domain → Room → UI, landed in
`2026-07-19-eta-delivery-window-design.md`), but no scraper produces one. Amazon still yields
date-only, so the user sees "Today" where the carrier said "Today · 3:00 – 5:00 PM".

This increment is the follow-up that design named as out of scope: extract the window on the
Amazon tracker page and feed it into the existing plumbing.

## Decisions (from brainstorming)

1. **Source:** tracker-page status line only. The order-details coarse header, the list/home card,
   and the UPS/USPS API parsers are untouched.
2. **Split of labor:** the extraction JS does a **dumb text grab**; a **Kotlin parser** does the
   format handling. Rejected the alternative of parsing inside `AMAZON_EXTRACTION_JS` — the repo
   has no JS engine in `commonTest` (`AmazonWebSpecTest` can only string-match the blob), so
   JS-side parsing would be verifiable by device QA alone. Every meridiem and separator edge case
   is where the bugs live; those belong somewhere a unit test can reach.
3. **Transport:** a new `etaWindowText` field on `ScrapedTracking`, resolved during `toSnapshot()`.
4. **Failure mode:** a malformed or ambiguous window yields **no window at all**, never a partial
   one.

## Parser

New file `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/EtaWindowParser.kt`:

```kotlin
data class EtaWindow(val start: LocalTime?, val end: LocalTime?)

fun parseEtaWindow(text: String?): EtaWindow?   // null => no window found
```

Case-insensitive; accepts `-`, `–`, `—`, or `to` as the range separator.

| Input | Result |
|---|---|
| `3:00 PM - 5:00 PM` | 15:00 – 17:00 |
| `3 - 5 PM` | 15:00 – 17:00 (start inherits the end's meridiem) |
| `11:30 AM – 1:30 PM` | 11:30 – 13:30 |
| `8 AM to 12 PM` | 08:00 – 12:00 |
| `by 10 PM` | start `null`, end 22:00 |
| `12 AM - 2 AM` | 00:00 – 02:00 |
| anything else | `null` |

Rules:

- **The end meridiem is required.** A bare `3 - 5` is ambiguous, so it is rejected rather than
  guessed. The start meridiem is optional and inherits the end's when absent.
- Hour must be 1–12, minute 0–59. Out-of-range components reject the whole match.
- 12-hour → 24-hour: `PM` and hour ≠ 12 → +12; `AM` and hour == 12 → 0.
- Rejection returns `null` — never an `EtaWindow` with one bound filled from a half-parsed range.
  This matches the atomic window merge `ParcelRepository.applySnapshot` already enforces: a lone
  start can never attach to a newer end.

Placed in the `webview` module rather than `amazon` because the same text shapes will appear when
the UPS/USPS webview specs eventually surface windows, and because `toSnapshot()` — its only
caller — lives there.

## Scrape contract

`source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/ScrapedTracking.kt` gains:

```kotlin
val etaWindowText: String? = null
```

`toSnapshot()` resolution order:

1. If `etaWindowStart`/`etaWindowEnd` are present, they win (explicit ISO times from any future
   producer beat free text).
2. Else `parseEtaWindow(etaWindowText)` supplies both bounds.
3. Else both stay null.

No existing producer emits `etaWindowText`, so every current path is unaffected. The field is
**transport only** — it is consumed in `toSnapshot()` and never reaches the domain model, so there
is **no Room schema change and no migration**.

## Extraction JS

`AMAZON_EXTRACTION_JS` in `source/amazon/src/commonMain/kotlin/com/shiphappens/source/amazon/AmazonWebSpec.kt`,
**tracker-page branch only**. A `windowText(t)` helper returns the first substring matching a loose
time-ish pattern (a clock time or hour with a meridiem, optionally followed by a separator and a
second time), or `null`. It performs no conversion, no meridiem inference, and no validation —
that is the Kotlin parser's job.

It is applied to the same two elements `etaFromArriving` already reads: the tracker status text
first, then the promise element (`[class*="promise"], #expected-delivery-date`). It is **never**
applied to `document.body.innerText` — an unrelated timestamp elsewhere on the page (an event row,
a "delivered at" line) must not be mistaken for the promise.

The tracker return object gains `etaWindowText: <string|null>` alongside `etaDate`. The
order-details branch's `coarse` object is unchanged.

## UI

No work. `formatEtaWindow` already renders `"3:00 – 5:00 PM"` (collapsed shared meridiem) and
`"by 5:00 PM"`, and `DetailViewModel.windowText` already composes `"Today · 3:00 – 5:00 PM"`.

## Testing

New `source/webview/src/commonTest/kotlin/com/shiphappens/source/webview/EtaWindowParserTest.kt`:

- every accepted row of the table above,
- rejects: bare `3 - 5` (no meridiem), hour `0`/`13`, minute `60`, text with no time at all,
  empty string, `null`.

`ScrapedTrackingTest`:

- `etaWindowText` populates `etaWindowStart`/`etaWindowEnd` on the snapshot,
- explicit `etaWindowStart`/`etaWindowEnd` take precedence over a conflicting `etaWindowText`,
- an unparseable `etaWindowText` leaves both bounds null rather than failing the scrape.

`AmazonWebSpecTest`: string assertion that the blob emits `etaWindowText`.

Device QA remains the only check on the *selector* side — which element the text is grabbed from —
consistent with every other Amazon selector in the file.

## Out of scope / follow-ups

- Order-details coarse header window (the header shows a day, not a window, in the observed case).
- List/home card window display — still day-granularity countdown ring.
- UPS/USPS webview window extraction; their API parsers keep emitting a lone cutoff as
  `etaWindowEnd`.

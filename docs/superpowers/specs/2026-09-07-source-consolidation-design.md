# Source consolidation design

**Date:** 2026-09-07
**Status:** approved in discussion, awaiting implementation plan

## Goals

Six carrier sources now share the `:source:webview` scraping framework (UPS, USPS, FedEx,
Amazon, Amazon Logistics, DHL eCommerce). Their parsing code has converged on the same shape by
copy, not by sharing. This design consolidates it so that:

1. A new carrier is one spec file, one vocabulary declaration, an optional API parser, and a
   short test that delegates to a shared contract.
2. A minor parsing adjustment (a new wording, a new date format, a new not-found phrase) is a
   one-line data change with a unit test beside it.
3. An improvement made while fixing one carrier (a negated-delivered guard, a relative-day
   parser, a multi-stage rail refusal) applies to every carrier without touching them.

Carriers stay in their own modules. Each remains a distinct `:source:<carrier>` module so a
seventh can be added the same way; they depend on a richer `:source:webview` toolkit instead of
carrying their own copies of it.

## Non-goals

- A declarative DOM-recipe compiler that turns selector lists into extraction JS. The two
  complex sources (Amazon's two-page flow, FedEx's async click) would need escape hatches, and a
  generator bug would break six carriers at once through a surface no unit test can reach.
  Revisit when a seventh source shows what the recipe shape should be.
- Merging carriers into one module. Rejected in discussion: the module boundary is the point.
- Changing what any carrier scrapes or how it classifies today. Behavior is preserved
  increment by increment; every existing page-logic and API-parser test keeps passing, rewritten
  only where its subject moved.

## What is duplicated today

- **The tracker-page ladder.** Five `parseXRaw` functions share one body: check `kind`, match a
  not-found regex on `pageText`, classify each event row, bail as empty, then
  `status = classify(headline) ?: newest classified event ?: UNKNOWN` and
  `location = banner ?: newest event with a location`. The USPS, DHL, and AMZL API parsers copy
  the same ladder again.
- **Three status engines.** UPS, USPS, FedEx, and DHL use `classifyStatusWording`. Amazon
  reimplements the identical precedence chain with stricter phrases. AMZL's DOM fallback has a
  third hand-rolled `when`, and its API parser a fourth over CamelCase tokens. DHL patches the
  shared chain's DELIVERED-before-EXCEPTION order from outside ("undelivered"), and AMZL's API
  classifier reorders for the same reason.
- **Date and time helpers.** Four private 12-hour clock parsers (UPS, USPS, AMZL, shared), two
  M/D/YYYY parsers, two month-name parsers, and six copies of "local date + time in a zone to an
  ISO instant" and "sort events ascending".
- **A vestigial string round-trip.** No JS blob emits `page:'ok'` any more; every tracking
  object is built in Kotlin. Yet parsers still stringify statuses and instants into
  `ScrapedTracking` so `toSnapshot` can parse them back. Every `.name`, `valueOf`,
  `takeIf { it != "UNKNOWN" }` and `.toString()` in the parsers exists for this.
- **Per-module boilerplate.** A `Source.kt` whose only content is a detect regex that also
  lives in `BuiltInCarrierDetection`; an identical Koin line; a build file identical except for
  its namespace; a `SourceTest` and `WebSpecTest` of the same shape; four near-identical
  logged-in probes; one challenge-marker list repeated four times.
- **JS scaffolding.** Every blob redeclares `text`, `clean`, the 400-character `pageText`
  slice, a `probe` helper, and the raw envelope. Only FedEx and Amazon send `todayIso`, so the
  other four cannot benefit from relative-day parsing even though the Kotlin for it is shared.

## Increments

Three increments, each landing on `main` independently with the full test suite green. The
first is pure Kotlin and delivers goals 2 and 3. The second delivers goal 1. The third shrinks
the JS and is the only one that needs a device QA pass across all six carriers.

---

## Increment 1: typed outputs, shared resolver, one vocabulary engine

All changes are in `:source:webview` commonMain plus mechanical rewrites of each carrier's
`PageLogic` and `ApiParser` to call the shared code. No Gradle changes.

### 1.1 Typed parser outputs

`ScrapedTracking` and `ScrapedEvent` are deleted. Providers produce the domain types directly.

```kotlin
// WebProviderSpec
val parseApi: (url: String?, body: String) -> TrackingSnapshot? = { _, _ -> null },
val parseRaw: (DomRaw) -> PageOutcome? = { null },
```

`PageOutcome` is the Kotlin-side result of reading a page. It is not serializable and never
crosses the bridge.

```kotlin
sealed interface PageOutcome {
    data class Tracking(val snapshot: TrackingSnapshot) : PageOutcome
    /** One-hop navigation with an optional coarse fallback read from the requesting page. */
    data class Goto(val url: String, val coarse: TrackingSnapshot?) : PageOutcome
    data object NotFound : PageOutcome
    data object LoginWall : PageOutcome
    data object Challenge : PageOutcome
    data object Empty : PageOutcome
}
```

`DomExtraction` keeps only what JS still emits: `page` (`raw`, `notFound`, `loginWall`,
`challenge`, `empty`), `url`, and `raw`. Its `tracking` field goes away. `PayloadRouter` maps
`PageOutcome` to `RouteResult` and keeps enforcing the hop-URL check on `Goto`; a provider still
cannot opt out of it. `backfilledFrom` becomes an internal extension on `TrackingSnapshot`.
`WebViewBasedSource.track` no longer calls `toSnapshot`.

Delivery windows: `TrackingSnapshot` already carries `etaWindowStart`/`etaWindowEnd`. The
free-text `etaWindowText` path survives as an input to the assembler (1.3), which calls
`parseEtaWindow` once, instead of as a field every parser fills for `toSnapshot` to parse later.

`WebViewBasedSourceTest`, `PayloadRouterTest`, and `ScrapedTrackingTest` are rewritten against
the typed shapes; the iOS-safe plain-loop helpers in `WebViewBasedSource` stay as they are (the
Kotlin/Native 2.4.0 miscompile they work around is unrelated to this change).

### 1.2 One status engine, configured by data

`StatusVocabulary` becomes a value that carriers hold, replacing the free functions plus a
per-carrier wrapper.

```kotlin
class StatusVocabulary(
    val extras: StatusKeywords = StatusKeywords(),
    /** Carrier-page defaults, or none for pages that mix shipment and non-shipment copy. */
    val base: BaseKeywords = BaseKeywords.Carrier,
) {
    fun classify(text: String?): TrackingStatus?
    fun isDelayed(text: String?): Boolean
    fun isMultiStage(text: String?): Boolean
    /** CamelCase / SCREAMING_SNAKE API tokens, humanized before classification. */
    fun classifyToken(token: String?): TrackingStatus?
}
```

- The precedence chain gains a **negated-delivered lane** evaluated before DELIVERED, seeded
  with "undelivered" and "not delivered". DHL's external guard and AMZL's reordering both
  disappear into it; every carrier gets it.
- `BaseKeywords.None` gives Amazon the same engine with only its own phrase lists, preserving
  the 2026-07-19 decision that bare "return"/"attempt" must not match on order pages. Amazon's
  delay phrases ("running late", "now expected") become `extras.delayed`.
- AMZL's DOM `when` becomes `StatusKeywords` extras on the carrier base. Its API classifier
  becomes `classifyToken`, which splits CamelCase and underscores into words
  ("OutForDelivery" reads "out for delivery") and runs the same chain, with AMZL-specific tokens
  ("creation confirmed", "ready for receive", "pickup done") as extras. One AMZL vocabulary
  serves both layers, as UPS, USPS, and DHL already do.
- `isMultiStage` moves from a DHL-only call into the shared resolver (1.3), applied to every
  carrier's headline.

Existing `StatusVocabularyTest` cases carry over; new cases cover the negated lane, the
`None` base, and token humanization.

### 1.3 Shared assembler and tracker-page resolver

The ladder that five page logics and three API parsers copy becomes two functions.

```kotlin
/** The shared fallback ladder. Events may arrive in any order; the result is ascending. */
fun assembleSnapshot(
    vocabulary: StatusVocabulary,
    headline: String?,
    events: List<TrackingEvent>,
    etaDate: LocalDate? = null,
    etaWindow: EtaWindow? = null,
    etaWindowText: String? = null,
    location: String? = null,
    delayNote: String? = null,
    /** Used only when neither the headline nor any event classifies (UPS's type code). */
    statusFallback: TrackingStatus? = null,
): TrackingSnapshot
```

Status resolves as headline, then newest classified event, then `statusFallback`, then
UNKNOWN. Location resolves as the explicit banner, then the newest event with a location.
Window resolves as explicit bounds, then parsed text. The three API parsers that inline this
ladder (USPS, DHL, AMZL) call it; UPS passes its type-code mapping as `statusFallback`.

```kotlin
/** Everything one carrier declares about reading its tracker page. */
class TrackerPageRules(
    val vocabulary: StatusVocabulary,
    /** Regex sources merged with the shared not-found phrases. */
    val notFound: List<String> = emptyList(),
    val etaDate: (text: String?, today: LocalDate?) -> LocalDate? = ::parsePromiseDate,
    val location: (DomRaw) -> String? = { it.locationText },
    val delayNote: (headline: String?, events: List<TrackingEvent>) -> String? = ::headlineThenNewestEvent,
)

fun resolveTrackerPage(raw: DomRaw, rules: TrackerPageRules): PageOutcome?
```

`resolveTrackerPage` returns null for a `kind` other than `tracker`, `NotFound` when any
shared or carrier not-found pattern matches `pageText`, null (routes to Unparsed, nothing
persisted) when the headline names two or more stages, `Empty` when there is no headline, no
event, and no promise, and otherwise `Tracking(assembleSnapshot(...))`.

The shared not-found list is seeded with the phrases that appear in two or more carriers today
("can't find … tracking number", "couldn't find", "unable to find", "invalid tracking",
"no record of this tracking", "could not locate the tracking"). Each carrier keeps its
remaining wordings as `notFound` extras, so the merged behavior on every existing fixture is
unchanged.

`parsePromiseDate(text, today)` is the default ETA chain: numeric M/D/Y, month-name with year,
relative day, weekday name, then month+day without year. It is safe only on text that is the
promise element, which `DomRaw.etaText` is by contract. Amazon keeps its own `etaDate` hook
because it reads promises out of status headlines and needs the "arriving"/"now expected" gate
first. FedEx keeps a `location` hook for its "Currently in" prefix.

After this increment the four selector-only page logics (UPS, USPS, AMZL, DHL) reduce to a
`StatusVocabulary`, a `TrackerPageRules`, and any event or ETA hook the page needs. FedEx keeps
two hooks; Amazon keeps its cards resolver, which has no counterpart elsewhere.

### 1.4 Events and dates

- `DomRawEvent.toTrackingEvent(vocabulary, zone)` in the shared module: ISO `timestamp` if
  present, else `whenText` through the shared date and time parsers (FedEx's private
  `fedexEventInstant` has nothing FedEx-specific). A row with no resolvable date is dropped.
- `eventAt(date, time, zone): Instant` replaces the six inline `LocalDateTime(...).toInstant`
  builds. DHL's abbreviation-to-IANA zone table moves into the shared module as
  `zoneForAbbreviation`, since any US carrier API may stamp "ET".
- `PageDates` absorbs the private helpers: `parseTimeOfDay` (already shared) replaces the UPS,
  USPS, and AMZL clock parsers; `parseNumericMdyDate` replaces UPS's M/D/YYYY; a new
  `parseCompactDate` ("20260714") takes UPS's; AMZL's month-name regex is replaced by
  `parseMonthNameDate` plus `parseTimeOfDay`, with NBSP normalization moved into the shared
  helpers so every carrier gets it.
- `DomRaw.today()` becomes a shared extension.
- `EtaWindowParser` keeps its own component-time function because range parsing lets the start
  inherit the end's meridiem, which the general clock parser must not do.

### 1.5 Tests for increment 1

- New shared tests: `StatusVocabularyTest` additions, `SnapshotAssemblerTest`,
  `TrackerPageResolverTest`, `PageDatesTest` additions.
- Each carrier's `PageLogicTest` keeps its fixture strings and expected results. Where a test
  exercised a deleted wrapper (`classifyUpsStatus`), it calls the vocabulary or the resolver
  instead. The captured live-page strings in those tests are the regression net for this whole
  increment and must not be trimmed.
- Each `ApiParserTest` keeps its fixtures; assertions move from string statuses to enum values.

---

## Increment 2: module shape

Removes the per-carrier `Source.kt`, the twice-maintained detect regexes, the hand-copied
`SourceTest` and `WebSpecTest`, and the duplicated build script body. Carriers keep their
modules.

### 2.1 Carrier owns its number pattern

```kotlin
data class Carrier(
    val code: String,
    val displayName: String,
    val accentColorHex: String? = null,
    /** Matched against the normalized number; null for carriers we only display. */
    val numberPattern: Regex? = null,
) {
    fun claims(normalized: String): Boolean = numberPattern?.matches(normalized) == true
}
```

`WellKnownCarriers` carries the six regexes. FedEx's USPS-prefix exclusion becomes a negative
lookahead in its own pattern, so it no longer depends on evaluation order.
`BuiltInCarrierDetection.detect` iterates `WellKnownCarriers.all` with the existing length
guard, and its test becomes the single test of those patterns.

### 2.2 One concrete source class

`WebViewBasedSource` becomes final `WebSource(spec, scraper)`, with
`detectCarrier` = `spec.carrier.takeIf { it.claims(normalizeTracking(number)) }`. The six
subclasses are deleted.

```kotlin
/** Koin registration for one carrier: qualified by source id so six instances coexist. */
fun webSourceModule(spec: WebProviderSpec): Module = module {
    single(named(spec.sourceId)) { WebSource(spec, get()) } bind TrackingSource::class
}
```

`getAll<TrackingSource>()` in `DataModule` collects qualified bindings, so `SourceRegistry` is
unchanged. `AppModules.kt` keeps its one line per carrier. `sourceId` on the spec defaults to
`carrier.code`, which every carrier already uses.

### 2.3 Login recipe and shared defaults on the spec

```kotlin
class LoginRecipe(val url: String, val isLoggedInJs: String)

class WebProviderSpec(
    val carrier: Carrier,
    val cookieDomain: String,
    val trackingUrl: (String) -> String,
    /** Null for anonymous trackers (AMZL, DHL eCommerce): never logged in, no login screen. */
    val login: LoginRecipe? = null,
    val apiUrlPatterns: List<String> = emptyList(),
    val extraChallengeMarkers: List<String> = emptyList(),
    val extractionJs: String,
    val parseApi: (url: String?, body: String) -> TrackingSnapshot? = { _, _ -> null },
    val parseRaw: (DomRaw) -> PageOutcome? = { null },
    val settle: Duration = 3.seconds,
    val sourceId: String = carrier.code,
) {
    val challengeMarkers: List<String> get() = DEFAULT_CHALLENGE_MARKERS + extraChallengeMarkers
    val isLoggedInJs: String get() = login?.isLoggedInJs ?: NEVER_LOGGED_IN_JS
}
```

`BridgeScripts.loggedInProbe(selectors, textPattern)` generates the four "logout link or
sign-out text" probes from their selector lists and regex; Amazon's account-menu probe stays
custom. `WebLoginViewModel` and `SettingsViewModel` treat a null `login` as not web-capable for
sign-in purposes while keeping the More-details web view.

### 2.4 Convention plugin

An included build `build-logic` with one precompiled script plugin,
`shiphappens.source-module`, that applies the KMP, serialization, and
`com.android.kotlin.multiplatform.library` plugins, declares the jvm and iOS targets, the
`kotlin.time.ExperimentalTime` opt-in, and the common dependencies (`:source:api`,
`:source:webview`, Koin, serialization, datetime, and the test libraries). A carrier's build
file becomes the plugin id plus its Android namespace. Versions stay in the version catalog and
SDK levels in `gradle.properties`, read from the plugin.

This is the riskiest piece of the increment given the AGP 9 KMP plugin shapes; it is verified
by a full build of every target plus the iOS simulator test task before the increment merges.

### 2.5 Contract tests

New module `:source:webview-testing` (commonMain, depends on `kotlin-test` and
`:source:webview`) exposing:

```kotlin
object WebSpecContract {
    fun verify(spec: WebProviderSpec, samples: SpecSamples)
}
object WebSourceContract {
    fun verify(spec: WebProviderSpec, claims: List<String>, rejects: List<String>)
}
```

`WebSpecContract` asserts: `sourceId == carrier.code`; the tracking URL for a sample number is
https and on the cookie domain; origin rules have the documented shape; every API pattern
compiles and matches the sample API URL and rejects the sample page URL; `parseApi` returns
null for non-JSON and for foreign JSON; `parseRaw` returns null for a wrong-kind `DomRaw` and
`NotFound` for each sample not-found page text; `extractionJs` is a function expression.
`WebSourceContract` asserts the descriptor derives from the carrier, `implemented` is false with
`NoWebScraper`, detection claims and rejects the given numbers, and `track` fails cleanly
without a scraper.

Each carrier's `SourceTest` and `WebSpecTest` collapse into one `XContractTest` that supplies
samples and calls both. Carrier-specific spec assertions that are not contract material (the
FedEx empty-pattern rule, the AMZL cookie-domain scoping) stay as individual tests beside it.
Any assertion added to a contract later runs against every carrier.

---

## Increment 3: JS helpers

The extraction runner injects a `page` helper object and passes it to the extractor, so blobs
declare selectors and choreography and nothing else.

```js
page.bodyText            // document.body.innerText, once
page.pageText            // first 400 chars, whitespace-collapsed
page.todayIso            // device-local date, always present
page.clean(el)           // trimmed, whitespace-collapsed textContent or null
page.text(sel1, sel2…)   // FIRST selector that matches, in argument order (never a comma list)
page.count(sel)          // querySelectorAll length, -1 on a bad selector
page.probe(sel…)         // {selector: count} for tracer diagnostics
page.raw(kind, fields)   // {page:'raw', raw:{kind, pageText, todayIso, …fields}}
```

Extractor signature becomes `function(page, finish)`; the runner keeps the synchronous-return
and deferred-`finish` semantics and the backstop timer. `BridgeScriptsTest` locks the helper
surface as string assertions.

With `todayIso` and `pageText` universal, the shared ETA chain and not-found handling work on
every carrier without per-blob plumbing. Two decisions still made in JS move out:

- USPS's `Date.parse` of event dates and Amazon's `parseDay` for event date headers ("Today",
  "Yesterday", "Tuesday, July 15") both become `whenText` on the event row, resolved by the
  shared `toTrackingEvent` through `parseRelativeDay` and `parseDayWithoutYear`. Amazon's blob
  loses its 20-line date function and the January-rollover bug class it carried.
- Amazon's window-text extraction in JS becomes `findEtaWindowText` in Kotlin, which USPS and
  FedEx already use.

Login-wall detection stays in the blobs where it depends on selectors (Amazon's sign-in form,
UPS's title check).

Increment 3 changes the runner every carrier shares, so it ends with a device QA pass on all six
carriers against live numbers, following the no-instrumented-builds-left-installed rule.

---

## What a carrier looks like afterward

Files in `:source:ups` after all three increments:

- `UpsWebSpec.kt`: the vocabulary, the page rules, the extraction JS (about eight lines), the
  login recipe, and the `WebProviderSpec` value. `upsSourceModule = webSourceModule(UpsWebSpec)`.
- `UpsApiParser.kt`: the DTOs and one mapping function ending in `assembleSnapshot`.
- `UpsVocabularyTest.kt`, `UpsApiParserTest.kt`, `UpsContractTest.kt`.
- `build.gradle.kts`: two statements.

`UpsPageLogic.kt` and `UpsSource.kt` are gone. A carrier with no in-page API (FedEx, Amazon) has
no parser file. A carrier whose page needs choreography (FedEx) keeps a longer blob; that is
the only part of a carrier that is not data or a hook.

## Error handling

Unchanged in kind: every parser and resolver degrades rather than throws. A malformed field
yields null for that field, an unclassifiable wording yields null and falls through the ladder,
an undecodable body yields null from `parseApi`. The `Unparsed` route for a multi-stage
headline is deliberate: it persists nothing, so the API capture on the same page is the only
writer. The router's hop-URL validation remains the one rule a provider cannot bypass.

## Risks

- **Behavior drift while consolidating.** Mitigated by keeping every captured-page fixture and
  its expected result in the carrier tests, and by landing each increment only when the whole
  suite is green on JVM and iOS simulator.
- **Shared not-found phrases matching a valid page.** The seed list is limited to phrases that
  already live in two carriers and were validated live. New shared phrases need a fixture from
  a live page before they are added.
- **Kotlin/Native miscompile.** New shared code that scans a `List` of a sealed type with inline
  lambdas must not sit on the `track` path; the resolver and assembler return values rather than
  scan sealed lists, and the existing plain-loop helpers stay.
- **Convention plugin.** Verified by a full multi-target build before merge; if AGP 9 rejects the
  precompiled-script shape, the fallback is a plain `apply(from)` script, which still removes the
  duplicated body.
- **Koin qualifiers.** Verified by a `SourceRegistryTest` case that all six sources resolve
  through `getAll`.

## Sequencing

Each increment is its own branch and squash merge. Increment 1 first: it carries the parsing
goals and touches no build files. Increment 2 next. Increment 3 last, with its device pass. If
increment 3 is deferred, increments 1 and 2 stand on their own.

# Source Consolidation Increment 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the per-carrier module boilerplate that increment 1 left behind, so a new carrier is one spec file plus a short contract test, while every carrier keeps its own module.

**Architecture:** Number patterns move onto `Carrier`, so detection has one owner. `WebViewBasedSource` becomes a final `WebSource` registered by a `webSourceModule(spec)` helper, deleting the six subclasses. Login becomes a nullable `LoginRecipe` with a generated probe, challenge markers get a shared default, and `sourceId` defaults to the carrier code. A `build-logic` convention plugin replaces six identical build files. A `:source:webview-testing` module exposes contract checks that each carrier's test calls in a few lines.

**Tech Stack:** Kotlin Multiplatform 2.4.0, AGP 9.2.1 (`com.android.kotlin.multiplatform.library`), Gradle precompiled script plugins (`kotlin-dsl`), Koin 4.2.2, kotlin-test in `commonTest`.

**Spec:** `docs/superpowers/specs/2026-09-07-source-consolidation-design.md` (Boundary rule and Increment 2, sections 2.1 to 2.5).

## Global Constraints

- **Boundary rule (spec):** `:source:webview` and `:source:webview-testing` hold mechanisms and carrier-neutral English only. Every selector, URL, regex on carrier copy, and carrier string stays in the carrier's module. Number patterns move to `:domain` because `Carrier` is a domain type; they are data about the carrier, not scraping logic.
- **Carriers stay in their own modules.** No carrier code moves into another module.
- **Behavior preserved:** every existing test's expected values keep passing. Detection results for every number in the existing tests are unchanged. No fixture string is edited.
- **No parsing changes.** `TrackerPageRules`, `assembleSnapshot`, `StatusVocabulary`, and the carriers' vocabularies are untouched by this increment.
- **Kotlin/Native guard:** the three plain-`for`-loop helpers in the source class stay plain loops.
- **Time values:** `Duration` / `kotlin.time.Instant`, never unit-suffixed primitives.
- **Versions** stay in `gradle/libs.versions.toml`; SDK levels stay in `gradle.properties`; the convention plugin reads both, defines neither.
- **Commits:** subject starts with a bracketed tag (`[domain]`, `[data]`, `[webview]`, `[ups]` and the other carriers, `[build]`, `[ui]`, `[docs]`). No `Co-Authored-By` trailer, no "Generated with Claude Code" footer.
- **Branch:** all work on `refactor/source-consolidation-2`, created by the controller. Squash-merge locally at the end; never push without asking.
- **Auto-generated file:** never stage `gradle/gradle-daemon-jvm.properties`.

**Test commands:**

```bash
# Full suite (end of every task)
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest :source:ups:jvmTest \
          :source:usps:jvmTest :source:fedex:jvmTest :source:amazon:jvmTest \
          :source:amzl:jvmTest :source:dhlecs:jvmTest :source:webview:jvmTest \
          :ui:testAndroidHostTest --console=plain
# Multi-target compile proof (Task 4 and Task 7)
./gradlew :source:ups:compileAndroidMain :source:ups:compileKotlinIosSimulatorArm64 \
          :source:webview:iosSimulatorArm64Test --console=plain
```

## File map

| Task | Creates | Modifies | Deletes |
|---|---|---|---|
| 1 | `domain/src/commonTest/.../CarrierDetectionTest.kt` | `domain/.../Carrier.kt`, `data/.../SourceRegistry.kt`, `data/src/jvmTest/.../SourceRegistryTest.kt` | `data/.../BuiltInCarrierDetection.kt`, `data/src/jvmTest/.../BuiltInCarrierDetectionTest.kt` |
| 2 | `source/webview/.../WebSource.kt`, `.../WebSourceTest.kt` | six `XWebSpec.kt` (module val), six `XSourceTest.kt`, four `ui` host tests, `AppModulesTest.kt`, `HeadlessWebViewScraper.kt` (comment) | `WebViewBasedSource.kt`, `WebViewBasedSourceTest.kt`, six `XSource.kt` |
| 3 | — | `WebProviderSpec.kt`, `BridgeScripts.kt`, `BridgeScriptsTest.kt`, `PayloadRouterTest.kt`, six `XWebSpec.kt`, `DhlEcsWebSpecTest.kt`, `WebLoginViewModel.kt`, `SettingsViewModel.kt` | — |
| 4 | `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/shiphappens.source-module.gradle.kts` | `settings.gradle.kts`, six carrier `build.gradle.kts` | — |
| 5 | `source/webview-testing/build.gradle.kts`, `.../testing/WebSpecContract.kt`, `.../testing/WebSourceContract.kt`, six `XContractTest.kt` | `settings.gradle.kts`, `PayloadRouter.kt` (`isAllowedHopUrl` public), convention plugin (test dep), five `XWebSpecTest.kt` | six `XSourceTest.kt` |
| 6 | — | `README.md` | — |
| 7 | — | (finish branch) | — |

---

### Task 1: Number patterns on `Carrier`; detection has one owner

**Files:**
- Modify: `domain/src/commonMain/kotlin/com/dgmltn/shiphappens/domain/Carrier.kt`
- Create: `domain/src/commonTest/kotlin/com/dgmltn/shiphappens/domain/CarrierDetectionTest.kt`
- Delete: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/source/BuiltInCarrierDetection.kt`, `data/src/jvmTest/kotlin/com/dgmltn/shiphappens/data/source/BuiltInCarrierDetectionTest.kt`
- Modify: `data/src/commonMain/kotlin/com/dgmltn/shiphappens/data/source/SourceRegistry.kt`

**Interfaces:**
- Produces: `Carrier.numberPattern: Regex?`, `Carrier.claims(normalized: String): Boolean`, `WellKnownCarriers.detect(raw: String): Carrier?`.
- Consumed by: Task 2 (`WebSource.detectCarrier`), Task 5 (`WebSourceContract`).

- [ ] **Step 1: Write the failing test**

`CarrierDetectionTest.kt` (the old `BuiltInCarrierDetectionTest` cases, moved to the owner, plus the per-carrier `claims` cases that used to live in six `SourceTest`s):

```kotlin
package com.dgmltn.shiphappens.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarrierDetectionTest {
    private fun detect(raw: String) = WellKnownCarriers.detect(raw)

    @Test fun detects_ups() {
        assertEquals(WellKnownCarriers.UPS, detect("1Z 999 AA1 01 2345 6784"))
    }

    @Test fun detects_usps_numeric_and_intl() {
        assertEquals(WellKnownCarriers.USPS, detect("9400 1118 9922 3300 1122"))
        assertEquals(WellKnownCarriers.USPS, detect("LK123456789US"))
        assertEquals(WellKnownCarriers.USPS, detect("EC123456789US"))
        assertNull(detect("941234"))
    }

    @Test fun detects_fedex_12_15_20_22_digits() {
        assertEquals(WellKnownCarriers.FEDEX, detect("123456789012"))
        assertEquals(WellKnownCarriers.FEDEX, detect("1234 5678 9012"))
        assertEquals(WellKnownCarriers.FEDEX, detect("123456789012345"))
        assertEquals(WellKnownCarriers.FEDEX, detect("12345678901234567890"))
        assertEquals(WellKnownCarriers.FEDEX, detect("1234567890123456789012"))
        assertNull(detect("1234567890123"))        // 13 digits
        assertFalse(WellKnownCarriers.FEDEX.claims("12345678901234567"))  // 17 digits is not a FedEx shape
    }

    @Test fun fedex_does_not_claim_usps_prefixed_numbers_regardless_of_order() {
        // 22 digits fits FedEx's length pattern, but the 94 prefix is USPS's. The pattern itself
        // excludes it, so resolution can never depend on registration order.
        assertFalse(WellKnownCarriers.FEDEX.claims("9434636106092288655003"))
        assertTrue(WellKnownCarriers.USPS.claims("9434636106092288655003"))
        assertEquals(WellKnownCarriers.USPS, detect("9434636106092288655003"))
    }

    @Test fun detects_amazon_order_ids() {
        assertEquals(WellKnownCarriers.AMAZON, detect("113-1234567-1234567"))
        assertEquals(WellKnownCarriers.AMAZON, detect("701 2345678 9012345"))
        assertEquals(WellKnownCarriers.AMAZON, detect("11312345671234567"))
        assertEquals(WellKnownCarriers.AMAZON, detect("12345678901234567"))  // 17 digits starting with 1
        assertNull(detect("213-1234567-1234567"))   // US order ids start 1 or 7
        assertNull(detect("113-1234567-123456"))    // wrong length
    }

    @Test fun detects_amazon_logistics_tba() {
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, detect("TBA333593378975"))
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, detect("tba 3335 9337 8975"))
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, detect("TBA123456789"))
        assertNull(detect("TBA12345678"))          // 8 digits: too short
        assertNull(detect("TBA1234567890123456"))  // 16 digits: too long
    }

    @Test fun detects_dhl_ecommerce_zip_prefixed_impb_only() {
        assertEquals(WellKnownCarriers.DHL_ECOMMERCE, detect("420 30001 9261-2345 0000 0000 0000 42"))
        assertEquals(WellKnownCarriers.DHL_ECOMMERCE, detect("420300019261234500000000000042"))
        // The bare 22-digit IMpb stays USPS's: tools.usps.com tracks these too (2026-09-03).
        assertEquals(WellKnownCarriers.USPS, detect("9261234500000000000042"))
        assertNull(detect("420300011234567890123456789012")) // remainder isn't 9x…
        assertNull(detect("42030001926123"))                  // too short
        assertNull(detect("42030001" + "9" + "1".repeat(26))) // tail beyond 26 digits
    }

    @Test fun rejects_short_and_garbage() {
        assertNull(detect("123"))
        assertNull(detect("hello world, meeting at 3pm"))
        assertNull(detect(""))
    }

    @Test fun a_carrier_without_a_pattern_claims_nothing() {
        assertFalse(Carrier("other", "Other").claims("1Z999AA10123456784"))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :domain:jvmTest --console=plain --tests '*CarrierDetectionTest*'`
Expected: compilation FAILS on `WellKnownCarriers.detect` and `claims`.

- [ ] **Step 3: Implement in `Carrier.kt`**

```kotlin
package com.dgmltn.shiphappens.domain

data class Carrier(
    val code: String,
    val displayName: String,
    val accentColorHex: String? = null,
    /**
     * Matched against a normalized number (see [normalizeTracking]); null for carriers we only
     * display. Regex compares by identity, so code that needs a known carrier must take it from
     * [WellKnownCarriers.byCode] rather than constructing an equal-looking one.
     */
    val numberPattern: Regex? = null,
) {
    fun claims(normalized: String): Boolean = numberPattern?.matches(normalized) == true
}

object WellKnownCarriers {
    val UPS = Carrier("ups", "UPS", "#5A3A22", Regex("^1Z[0-9A-Z]{10,}$"))
    val USPS = Carrier("usps", "USPS", "#1E3A8F", Regex("^(94|93|92|95|82)\\d{14,24}$|^[A-Z]{2}\\d{9}US$"))
    // 20-22 digit numbers with a USPS service prefix are USPS labels (FedEx Ground Economy hands
    // those to USPS); the lookahead keeps FedEx from claiming them, so resolution never depends
    // on registration order.
    val FEDEX = Carrier("fedex", "FedEx", "#5A1B9A", Regex("^\\d{12}$|^\\d{15}$|^(?!(94|93|92|95|82))\\d{20,22}$"))
    // Amazon orange (#FF9900) darkened to sit with the muted brand accents above. Order ids are
    // 3-7-7 digits normalized to 17 (hyphens stripped); US ids start 1 or 7.
    val AMAZON = Carrier("amazon", "Amazon", "#146EB4", Regex("^[17]\\d{16}$"))
    // Amazon's squid-ink navy; distinct from the orders source so carrier→webSpec lookups stay 1:1.
    val AMAZON_LOGISTICS = Carrier("amzl", "Amazon Logistics", "#37475A", Regex("^TBA\\d{9,15}$"))
    // DHL red (#D40511) darkened likewise; "dhlecs" not "dhl", leaving room for a DHL Express
    // carrier. Only the 420+ZIP-prefixed IMpb form — the bare 22-digit body stays USPS's, whose
    // pattern claims it (USPS does the last mile and tracks it too). 2026-09-03.
    val DHL_ECOMMERCE = Carrier("dhlecs", "DHL eCommerce", "#B3040D", Regex("^420\\d{5}9\\d{21,25}$"))
    val all = listOf(UPS, USPS, FEDEX, AMAZON, AMAZON_LOGISTICS, DHL_ECOMMERCE)
    fun byCode(code: String): Carrier? = all.firstOrNull { it.code == code.trim().lowercase() }

    /** The first well-known carrier whose pattern claims [raw] once normalized; null below ten characters. */
    fun detect(raw: String): Carrier? {
        val norm = normalizeTracking(raw.trim())
        if (norm.length < 10) return null
        return all.firstOrNull { it.claims(norm) }
    }
}
```

Keep `FALLBACK_PALETTE` and `fallbackAccentColor` below unchanged.

- [ ] **Step 4: Move the consumer and delete the data-module copy**

In `SourceRegistry.kt`, `detectCarrier` becomes:

```kotlin
    suspend fun detectCarrier(trackingNumber: String): Carrier? =
        WellKnownCarriers.detect(trackingNumber)
            ?: enabled().firstNotNullOfOrNull { it.detectCarrier(trackingNumber) }
```

with `import com.dgmltn.shiphappens.domain.WellKnownCarriers`. Delete `BuiltInCarrierDetection.kt` and `BuiltInCarrierDetectionTest.kt`. `SourceRegistryTest.detectCarrier_uses_builtins_before_sources` keeps passing unchanged.

- [ ] **Step 5: Run domain and data tests, then the full suite**

Run: `./gradlew :domain:jvmTest :data:jvmTest --console=plain`, then the full suite.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add domain data
git commit -m "[domain] Carrier owns its number pattern; WellKnownCarriers.detect replaces BuiltInCarrierDetection"
```

---

### Task 2: One concrete `WebSource`, registered by `webSourceModule(spec)`

**Files:**
- Create: `source/webview/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/WebSource.kt` (from `WebViewBasedSource.kt`, which is deleted)
- Create: `source/webview/src/commonTest/.../WebSourceTest.kt` (from `WebViewBasedSourceTest.kt`, deleted)
- Delete: `source/{ups,usps,fedex,dhlecs,amzl,amazon}/src/commonMain/.../XSource.kt`
- Modify: the six `XWebSpec.kt` (add the module val), the six `XSourceTest.kt` (construct `WebSource`), `ui/src/androidHostTest/.../settings/SettingsViewModelTest.kt`, `.../web/WebLoginViewModelTest.kt`, `.../web/WebDetailViewModelTest.kt`, `.../detail/DetailViewModelTest.kt`, `.../di/AppModulesTest.kt`, `source/webview/src/androidMain/.../HeadlessWebViewScraper.kt` (one comment)

**Interfaces:**
- Consumes: `Carrier.claims` (Task 1).
- Produces: `class WebSource(webSpec: WebProviderSpec, scraper: WebScraper) : TrackingSource, WebCapableSource` (final), `fun webSourceModule(spec: WebProviderSpec): Module`. Module vals keep their names (`upsSourceModule` and the rest) so `AppModules.kt` is untouched.

- [ ] **Step 1: Write the failing tests**

Rename `WebViewBasedSourceTest.kt` to `WebSourceTest.kt` (class `WebSourceTest`) with `git mv`. Delete the private `TestWebSource` class; every `TestWebSource(scraper)` becomes `WebSource(testSpec(), scraper)` and every `TestWebSource(scraper, spec)` becomes `WebSource(spec, scraper)`. Add:

```kotlin
    @Test fun detects_by_the_carriers_number_pattern() {
        val src = WebSource(testSpec(), NoWebScraper)   // testSpec's carrier is UPS
        assertEquals(WellKnownCarriers.UPS, src.detectCarrier("1Z 999 AA1 01 2345 6784"))
        assertNull(src.detectCarrier("9400111899223300112"))
    }
```

In each carrier `XSourceTest.kt`, `UpsWebSource(NoWebScraper)` becomes `WebSource(UpsWebSpec, NoWebScraper)` (and likewise `UspsWebSpec`, `FedexWebSpec`, `DhlEcsWebSpec`, `AmzlWebSpec`, `AmazonWebSpec`), importing `com.dgmltn.shiphappens.source.webview.WebSource`. Assertions unchanged.

In the four `ui` host tests, the same substitution (`UpsWebSource(NoWebScraper)` → `WebSource(UpsWebSpec, NoWebScraper)`, `UspsWebSource(...)` → `WebSource(UspsWebSpec, ...)`, `AmazonWebSource(...)` → `WebSource(AmazonWebSpec, ...)`), including the fully-qualified form in `DetailViewModelTest.kt`.

In `AppModulesTest.appModules_graph_resolves`, keep the check and add the registration assertion:

```kotlin
        val app = koinApplication {
            modules(listOf(testPlatformDataModule(), testWebModule()) + realModulesMinusPlatform)
        }
        app.checkModules()
        // Six carriers registered under qualified singles must all surface through the
        // unqualified collection SourceRegistry is built from.
        assertEquals(6, app.koin.getAll<TrackingSource>().size)
```

with imports `com.dgmltn.shiphappens.source.api.TrackingSource` and `kotlin.test.assertEquals`.

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :source:webview:jvmTest --console=plain --tests '*WebSourceTest*'`
Expected: compilation FAILS on `WebSource`.

- [ ] **Step 3: Implement `WebSource.kt`**

`git mv` `WebViewBasedSource.kt` to `WebSource.kt`. Replace the abstract class with:

```kotlin
/**
 * A TrackingSource whose data comes from driving the carrier's own website. Everything derives
 * from the [webSpec] recipe: identity from the carrier, detection from the carrier's number
 * pattern, scraping from the spec. No credential fields — auth is an optional cookie session
 * established in the login WebView.
 */
class WebSource(
    override val webSpec: WebProviderSpec,
    private val scraper: WebScraper,
) : TrackingSource, WebCapableSource {

    override val descriptor = SourceDescriptor(
        id = webSpec.sourceId,
        displayName = webSpec.carrier.displayName,
        accentColorHex = webSpec.carrier.accentColorHex,
        implemented = scraper.isAvailable,
    )

    override fun detectCarrier(trackingNumber: String): Carrier? =
        webSpec.carrier.takeIf { it.claims(normalizeTracking(trackingNumber)) }

    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        // body unchanged from WebViewBasedSource.track
    }
}

/**
 * Koin registration for one carrier. Qualified by source id so six WebSource singles coexist;
 * `bind` keeps them all visible to `getAll<TrackingSource>()`, which SourceRegistry is built from.
 */
fun webSourceModule(spec: WebProviderSpec): Module = module {
    single(named(spec.sourceId)) { WebSource(spec, get()) } bind TrackingSource::class
}
```

Keep `WebCapableSource`, the `track` body, and the three plain-loop helpers exactly as they are. Imports added: `com.dgmltn.shiphappens.domain.normalizeTracking`, `org.koin.core.module.Module`, `org.koin.core.qualifier.named`, `org.koin.dsl.bind`, `org.koin.dsl.module`. Remove the `abstract`/`final override` modifiers.

- [ ] **Step 4: Collapse the six carriers**

For each carrier, delete `XSource.kt` and append to `XWebSpec.kt`:

```kotlin
val upsSourceModule: Module = webSourceModule(UpsWebSpec)
```

(`uspsSourceModule`, `fedexSourceModule`, `dhlEcsSourceModule`, `amzlSourceModule`, `amazonSourceModule` respectively), with imports `org.koin.core.module.Module` and `com.dgmltn.shiphappens.source.webview.webSourceModule`. The detection regexes and their comments in the deleted files are already on `WellKnownCarriers` (Task 1); do not re-add them. In `HeadlessWebViewScraper.kt`, the comment "WebViewBasedSource will surface it" becomes "WebSource will surface it".

- [ ] **Step 5: Run the full suite**

Expected: PASS, including `AppModulesTest` with six sources.

- [ ] **Step 6: Commit**

```bash
git add -A source ui
git commit -m "[webview] Final WebSource + webSourceModule(spec); delete the six per-carrier source classes"
```

---

### Task 3: `LoginRecipe`, shared challenge defaults, generated probes, `sourceId` default

**Files:**
- Modify: `source/webview/src/commonMain/.../WebProviderSpec.kt`, `.../BridgeScripts.kt`
- Modify: the six `XWebSpec.kt`
- Modify: `source/webview/src/commonTest/.../BridgeScriptsTest.kt`, `.../PayloadRouterTest.kt` (`testSpec`), `source/dhlecs/src/commonTest/.../DhlEcsWebSpecTest.kt`
- Modify: `ui/src/commonMain/.../web/WebLoginViewModel.kt`, `.../settings/SettingsViewModel.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class LoginRecipe(val url: String, val isLoggedInJs: String)
  val DEFAULT_CHALLENGE_MARKERS: List<String>
  const val NEVER_LOGGED_IN_JS = "(function() { return false; })()"
  class WebProviderSpec(
      val carrier: Carrier,
      val cookieDomain: String,
      val trackingUrl: (trackingNumber: String) -> String,
      val extractionJs: String,
      val login: LoginRecipe? = null,
      val apiUrlPatterns: List<String> = emptyList(),
      val extraChallengeMarkers: List<String> = emptyList(),
      val parseApi: (url: String?, body: String) -> TrackingSnapshot? = { _, _ -> null },
      val parseRaw: (DomRaw) -> PageOutcome? = { null },
      val settle: Duration = 3.seconds,
      val sourceId: String = carrier.code,
  ) {
      val challengeMarkers: List<String> get() = DEFAULT_CHALLENGE_MARKERS + extraChallengeMarkers
      val isLoggedInJs: String get() = login?.isLoggedInJs ?: NEVER_LOGGED_IN_JS
      fun allowedOriginRules(): List<String>
  }
  fun BridgeScripts.loggedInProbe(selectors: List<String>, textPattern: String): String
  ```
- `WebSessions.kt` (androidMain) reads `spec.isLoggedInJs` and `spec.apiUrlPatterns`; `BridgeScripts.extractionRunner` reads `spec.challengeMarkers`. Both keep working through the computed properties; neither file changes.

- [ ] **Step 1: Write the failing tests**

`BridgeScriptsTest.kt`, add:

```kotlin
    @Test fun logged_in_probe_embeds_selectors_and_text_pattern() {
        val js = BridgeScripts.loggedInProbe(listOf("a[href*=\"logout\"]", "[class*=\"sign-out\"]"), "sign out|welcome,")
        assertTrue(js.trimStart().startsWith("(function()"))
        assertTrue(js.contains("""document.querySelector("a[href*=\"logout\"], [class*=\"sign-out\"]")"""))
        assertTrue(js.contains("""new RegExp("sign out|welcome,", 'i')"""))
        assertTrue(js.contains("catch (e) { return false; }"))
    }
```

`PayloadRouterTest.kt`, `testSpec` becomes:

```kotlin
internal fun testSpec(
    parseApi: (String?, String) -> TrackingSnapshot? = { _, _ -> null },
    parseRaw: (DomRaw) -> PageOutcome? = { null },
) = WebProviderSpec(
    carrier = WellKnownCarriers.UPS,
    cookieDomain = "example.com",
    trackingUrl = { "https://www.example.com/track?n=$it" },
    extractionJs = "function(){return {page:'empty'}}",
    apiUrlPatterns = listOf(".*example\\.com/api/track.*"),
    parseApi = parseApi,
    parseRaw = parseRaw,
    sourceId = "test",
)
```

and add to `PayloadRouterTest`:

```kotlin
    @Test fun spec_defaults_derive_from_the_carrier_and_the_shared_markers() {
        val spec = WebProviderSpec(carrier = WellKnownCarriers.UPS, cookieDomain = "ups.com", trackingUrl = { it }, extractionJs = "function(){}")
        assertEquals("ups", spec.sourceId)
        assertEquals(NEVER_LOGGED_IN_JS, spec.isLoggedInJs)
        assertEquals(DEFAULT_CHALLENGE_MARKERS, spec.challengeMarkers)
        val withExtras = WebProviderSpec(carrier = WellKnownCarriers.UPS, cookieDomain = "ups.com", trackingUrl = { it }, extractionJs = "function(){}",
            login = LoginRecipe("https://www.ups.com/signin", "(function(){return true})()"), extraChallengeMarkers = listOf("Pardon Our Interruption"))
        assertEquals("(function(){return true})()", withExtras.isLoggedInJs)
        assertEquals(DEFAULT_CHALLENGE_MARKERS + "Pardon Our Interruption", withExtras.challengeMarkers)
    }
```

`DhlEcsWebSpecTest.login_is_never_reported` becomes:

```kotlin
    @Test fun login_is_never_reported() {
        // Anonymous tracker, AMZL-style: no login recipe, so the probe is the shared constant false.
        assertNull(DhlEcsWebSpec.login)
        assertEquals(NEVER_LOGGED_IN_JS, DhlEcsWebSpec.isLoggedInJs)
    }
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :source:webview:jvmTest --console=plain --tests '*BridgeScriptsTest*'`
Expected: compilation FAILS on `loggedInProbe`.

- [ ] **Step 3: Implement the spec and the probe**

`WebProviderSpec.kt`: apply the constructor from Interfaces above. Add above the class:

```kotlin
/** How a carrier's site is signed in to, for carriers whose tracking benefits from a session. */
class LoginRecipe(
    val url: String,
    /** Expression evaluating to a boolean in page context: is the session signed in? */
    val isLoggedInJs: String,
)

/** Bot-defense wordings any carrier's site may show. Carriers add their own via [WebProviderSpec.extraChallengeMarkers]. */
val DEFAULT_CHALLENGE_MARKERS: List<String> = listOf("Access Denied", "Reference #", "verify you are a human", "unusual activity")

/** The probe for a carrier with no login: never signed in. */
const val NEVER_LOGGED_IN_JS = "(function() { return false; })()"
```

Update the class KDoc: "Adding a new provider means writing one of these and registering it with `webSourceModule`"; the `isLoggedInJs` bullet now describes `login`.

`BridgeScripts.kt`, add inside the object:

```kotlin
    /**
     * The common "is this session signed in?" probe: any of [selectors] present (a logout link,
     * an avatar), or [textPattern] (a case-insensitive JS regex source) found in the page text.
     * Carriers whose chrome needs more than that (Amazon's account menu) write their own.
     */
    fun loggedInProbe(selectors: List<String>, textPattern: String): String = """
(function() {
  try {
    if (document.querySelector(${jsString(selectors.joinToString(", "))})) return true;
    return new RegExp(${jsString(textPattern)}, 'i').test((document.body && document.body.innerText) || '');
  } catch (e) { return false; }
})()
""".trimIndent()
```

- [ ] **Step 4: Update the six specs**

Each `XWebSpec.kt`: remove `sourceId`, `loginUrl`, `isLoggedInJs`, `challengeMarkers`; add `login` and `extraChallengeMarkers` as below; drop `apiUrlPatterns = emptyList()` and `parseApi = { _, _ -> null }` where present (defaults). Delete the per-carrier `X_IS_LOGGED_IN_JS` constants that the probe replaces.

| Carrier | `login` | `extraChallengeMarkers` |
|---|---|---|
| UPS | `LoginRecipe("https://www.ups.com/lasso/signin?loc=en_US", BridgeScripts.loggedInProbe(listOf("#ups-header a[href*=\"logout\"]", "[data-testid*=\"account\"]", ".ups-header_avatar"), "welcome,\|my profile\|sign out"))` | `listOf("Pardon Our Interruption")` |
| USPS | `LoginRecipe("https://reg.usps.com/entreg/LoginAction_input", BridgeScripts.loggedInProbe(listOf("a[href*=\"logout\"]", "a[href*=\"LogOutAction\"]", "[class*=\"sign-out\"]"), "sign out\|welcome,"))` | omit |
| FedEx | `LoginRecipe("https://www.fedex.com/secure-login/en-us/", BridgeScripts.loggedInProbe(listOf("a[href*=\"logout\"]", "a[href*=\"signout\"]", "[data-test-id*=\"logout\" i]"), "sign out\|log out\\b"))` | omit |
| DHL eCommerce | omit (null) | `listOf("Request unsuccessful")` |
| AMZL | omit (null) | `listOf("Enter the characters you see", "Type the characters you see", "not a robot", "automated access to Amazon data")` |
| Amazon | `LoginRecipe("https://www.amazon.com/gp/sign-in.html", AMAZON_IS_LOGGED_IN_JS)` (its custom account-menu probe stays) | same four as AMZL |

(The `\|` in the table is a literal `|` in code.) Keep each carrier's explanatory comments (UPS's "validated in live QA", DHL's and AMZL's "anonymous tracker" notes, rewritten to say the login is null rather than a constant-false JS).

- [ ] **Step 5: UI: a null login is not sign-in capable**

`WebLoginViewModel.kt`: the spec lookup becomes a pair so a carrier without a login yields the empty state:

```kotlin
    private val target: Pair<WebProviderSpec, LoginRecipe>? =
        registry.all().filterIsInstance<WebCapableSource>()
            .firstOrNull { it.webSpec.sourceId == sourceId }?.webSpec
            ?.let { spec -> spec.login?.let { spec to it } }

    val state: StateFlow<WebLoginUiState> = done.map { d ->
        target?.let { (spec, login) ->
            WebLoginUiState(
                url = login.url, name = spec.carrier.displayName,
                accentHex = spec.carrier.accentColorHex ?: "#17150F", spec = spec, done = d,
            )
        } ?: WebLoginUiState(done = d)
    }.stateIn(...)
```

Check the rest of the file for other uses of the old `spec` val and route them through `target?.first`.

`SettingsViewModel.kt`: the sign-in button is only for carriers that can sign in:

```kotlin
            val webSpec = (src as? WebCapableSource)?.webSpec
            SourceCardUi(
                ...,
                webCapable = webSpec?.login != null,
                signedIn = webSpec?.login != null && cfg.values["loggedIn"] == "true",
            )
```

`onSignOut` is unchanged (it clears cookies for any web spec). `WebDetailViewModel` is unchanged (More details needs no login).

- [ ] **Step 6: Run the full suite**

Expected: PASS. If a `ui` host test asserted `webCapable == true` for a carrier that now has no login, it is asserting the old bug; the three carriers those tests register (UPS, USPS, Amazon) all have logins, so none should.

- [ ] **Step 7: Commit**

```bash
git add -A source ui
git commit -m "[webview] LoginRecipe, shared challenge defaults, generated logged-in probes, sourceId defaults to the carrier"
```

---

### Task 4: Convention plugin for the carrier modules

**Files:**
- Create: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, `build-logic/src/main/kotlin/shiphappens.source-module.gradle.kts`
- Modify: `settings.gradle.kts` (root), six `source/<carrier>/build.gradle.kts`

**Interfaces:**
- Produces: plugin id `shiphappens.source-module`. Task 5 adds one dependency line to it.

**Bail-out rule (spec 2.4):** this is the riskiest task. If after two fix rounds the plugin cannot configure `kotlin { android { } }` under AGP 9.2.1, the implementer reports BLOCKED with the exact error and the controller parks the task; the six build files then stay as they are and Task 5 adds its dependency to each of them instead.

- [ ] **Step 1: Create the included build**

`build-logic/settings.gradle.kts`:

```kotlin
rootProject.name = "build-logic"

dependencyResolutionManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
```

`build-logic/build.gradle.kts`:

```kotlin
plugins {
    `kotlin-dsl`
}

// The Gradle plugin marker artifact for a plugin id, so the precompiled script below can apply
// it by id. Versions come from the catalog; nothing is pinned here.
fun Provider<PluginDependency>.asArtifact() = map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(libs.plugins.kotlinMultiplatform.asArtifact())
    implementation(libs.plugins.kotlinSerialization.asArtifact())
    implementation("com.android.kotlin.multiplatform.library:com.android.kotlin.multiplatform.library.gradle.plugin:${libs.versions.agp.get()}")
}
```

`build-logic/src/main/kotlin/shiphappens.source-module.gradle.kts`:

```kotlin
// One carrier module. Applies the KMP + serialization + Android KMP library plugins, the shared
// targets and opt-ins, and the dependencies every carrier needs. The Android namespace derives
// from the project name (`:source:ups` → com.dgmltn.shiphappens.source.ups).
import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.android.kotlin.multiplatform.library")
}

val libs = the<LibrariesForLibs>()

kotlin {
    android {
        namespace = "com.dgmltn.shiphappens.source.${project.name}"
        compileSdk = providers.gradleProperty("shiphappens.compileSdk").get().toInt()
        minSdk = providers.gradleProperty("shiphappens.minSdk").get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(project(":source:api"))
            api(project(":source:webview"))
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
```

`LibrariesForLibs` is the generated catalog accessor class; for it to be visible inside `build-logic`, add to `build-logic/build.gradle.kts` dependencies: `implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))` — the standard idiom that exposes the generated `libs` accessors to precompiled scripts. If that idiom is rejected by this Gradle version, use `val libs = the<VersionCatalogsExtension>().named("libs")` and `libs.findLibrary("koin-core").get()` style lookups instead; either is acceptable.

Root `settings.gradle.kts`: add `includeBuild("build-logic")` as the first line inside `pluginManagement { }`.

- [ ] **Step 2: Replace the six build files**

Each `source/<carrier>/build.gradle.kts` becomes exactly:

```kotlin
plugins {
    id("shiphappens.source-module")
}
```

- [ ] **Step 3: Prove every target compiles and tests run**

```bash
./gradlew :source:ups:compileAndroidMain :source:ups:compileKotlinIosSimulatorArm64 \
          :source:amazon:compileAndroidMain :source:amazon:compileKotlinIosSimulatorArm64 --console=plain
```

then the full suite, then `./gradlew :source:webview:iosSimulatorArm64Test --console=plain`. Amazon and FedEx had no serialization dependency before; applying it uniformly is harmless.

Expected: all PASS. Configuration cache is on (`gradle.properties`); a configuration-cache problem from the plugin is a failure to fix, not to suppress.

- [ ] **Step 4: Commit**

```bash
git add build-logic settings.gradle.kts source/*/build.gradle.kts
git commit -m "[build] shiphappens.source-module convention plugin replaces six identical carrier build files"
```

---

### Task 5: `:source:webview-testing` contracts, one short test per carrier

**Files:**
- Create: `source/webview-testing/build.gradle.kts`, `source/webview-testing/src/commonMain/kotlin/com/dgmltn/shiphappens/source/webview/testing/WebSpecContract.kt`, `.../testing/WebSourceContract.kt`
- Modify: root `settings.gradle.kts` (include), `build-logic/src/main/kotlin/shiphappens.source-module.gradle.kts` (test dependency) — or the six build files if Task 4 was parked
- Modify: `source/webview/src/commonMain/.../PayloadRouter.kt` (`isAllowedHopUrl` becomes public)
- Create: six `source/<carrier>/src/commonTest/.../XContractTest.kt`
- Delete: six `XSourceTest.kt`
- Modify: `UspsWebSpecTest.kt`, `FedexWebSpecTest.kt`, `AmazonWebSpecTest.kt` (drop `spec_identity_and_origins`), `DhlEcsWebSpecTest.kt` and `AmzlWebSpecTest.kt` (drop the origin-rules and parse-api wiring cases the contract now covers; keep the cookie-domain rationale cases as they are carrier-specific)

**Interfaces:**
- Produces (package `com.dgmltn.shiphappens.source.webview.testing`):
  ```kotlin
  class SpecSamples(
      val trackingNumber: String,
      val capturedApiUrl: String? = null,
      val uncapturedUrl: String? = null,
      val notFound: List<DomRaw> = emptyList(),
  )
  object WebSpecContract { fun verify(spec: WebProviderSpec, samples: SpecSamples) }
  object WebSourceContract {
      fun verify(spec: WebProviderSpec, claims: List<String>, rejects: List<String>)
      suspend fun verifyTrackFailsWithoutScraper(spec: WebProviderSpec, trackingNumber: String)
  }
  ```

- [ ] **Step 1: The module**

`source/webview-testing/build.gradle.kts` (this is not a carrier, so it does not use the convention plugin):

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    android {
        namespace = "com.dgmltn.shiphappens.source.webview.testing"
        compileSdk = providers.gradleProperty("shiphappens.compileSdk").get().toInt()
        minSdk = providers.gradleProperty("shiphappens.minSdk").get().toInt()
    }
    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(projects.source.webview)
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
```

Add `include(":source:webview-testing")` to the root `settings.gradle.kts` after `:source:webview`. In the convention plugin's `commonTest.dependencies`, add `implementation(project(":source:webview-testing"))` (if Task 4 was parked, add `implementation(projects.source.webviewTesting)` to each carrier's `commonTest.dependencies` instead).

In `PayloadRouter.kt`, change `internal fun isAllowedHopUrl` to `fun isAllowedHopUrl` (KDoc unchanged).

- [ ] **Step 2: The contracts**

`WebSpecContract.kt`:

```kotlin
package com.dgmltn.shiphappens.source.webview.testing

import com.dgmltn.shiphappens.source.webview.DEFAULT_CHALLENGE_MARKERS
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.PageOutcome
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.isAllowedHopUrl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The carrier-supplied examples a spec contract is checked against. */
class SpecSamples(
    val trackingNumber: String,
    /** An in-page API URL the capture hooks must match; null for DOM-only carriers. */
    val capturedApiUrl: String? = null,
    /** A same-site URL the capture hooks must NOT match; null when the carrier's patterns are deliberately broad. */
    val uncapturedUrl: String? = null,
    /** Raw extractions (of whichever page kind the carrier decides not-found on) that must route NotFound. */
    val notFound: List<DomRaw> = emptyList(),
)

/** What every WebProviderSpec must satisfy, regardless of carrier. A new assertion here runs against all of them. */
object WebSpecContract {
    fun verify(spec: WebProviderSpec, samples: SpecSamples) {
        val d = spec.cookieDomain
        assertEquals(spec.carrier.code, spec.sourceId, "sourceId is the carrier code")
        assertTrue(isAllowedHopUrl(spec.trackingUrl(samples.trackingNumber), d), "tracking URL is https on the cookie domain")
        assertEquals(listOf("https://*.$d", "https://$d"), spec.allowedOriginRules())
        assertTrue(spec.challengeMarkers.containsAll(DEFAULT_CHALLENGE_MARKERS), "shared challenge markers present")
        assertTrue(spec.extractionJs.trimStart().startsWith("function"), "extractionJs is a function expression")
        assertTrue(spec.isLoggedInJs.isNotBlank())
        spec.login?.let { assertTrue(it.url.startsWith("https://"), "login URL is https") }

        val patterns = spec.apiUrlPatterns.map { Regex(it) }   // throws on a malformed pattern
        samples.capturedApiUrl?.let { url -> assertTrue(patterns.any { it.containsMatchIn(url) }, "some api pattern captures $url") }
        samples.uncapturedUrl?.let { url -> assertFalse(patterns.any { it.containsMatchIn(url) }, "no api pattern captures $url") }
        if (samples.capturedApiUrl == null) assertTrue(spec.apiUrlPatterns.isEmpty(), "a DOM-only carrier declares no api patterns")

        assertNull(spec.parseApi(null, "not json"), "parseApi rejects non-JSON")
        assertNull(spec.parseApi(null, "{}"), "parseApi rejects foreign JSON")
        assertNull(spec.parseRaw(DomRaw(kind = "contract-foreign-kind")), "parseRaw refuses an unknown page kind")
        samples.notFound.forEach { raw -> assertIs<PageOutcome.NotFound>(spec.parseRaw(raw), "not-found wording: ${raw.pageText}") }
    }
}
```

`WebSourceContract.kt`:

```kotlin
package com.dgmltn.shiphappens.source.webview.testing

import com.dgmltn.shiphappens.source.api.SourceResult
import com.dgmltn.shiphappens.source.webview.NoWebScraper
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.WebSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What every WebSource built from a spec must satisfy: identity from the carrier, detection from its pattern, a clean failure without a scraper. */
object WebSourceContract {
    fun verify(spec: WebProviderSpec, claims: List<String>, rejects: List<String>) {
        val source = WebSource(spec, NoWebScraper)
        assertEquals(spec.sourceId, source.descriptor.id)
        assertEquals(spec.carrier.displayName, source.descriptor.displayName)
        assertEquals(spec.carrier.accentColorHex, source.descriptor.accentColorHex)
        assertFalse(source.descriptor.implemented, "NoWebScraper => not implemented")
        claims.forEach { assertEquals(spec.carrier, source.detectCarrier(it), "claims $it") }
        rejects.forEach { assertNull(source.detectCarrier(it), "rejects $it") }
    }

    suspend fun verifyTrackFailsWithoutScraper(spec: WebProviderSpec, trackingNumber: String) {
        assertIs<SourceResult.Failure>(WebSource(spec, NoWebScraper).track(trackingNumber, null))
    }
}
```

- [ ] **Step 3: Six contract tests**

Each replaces `XSourceTest.kt` (delete with `git rm`). Template, shown for UPS; the other five differ only in the samples, which are the numbers and wordings their old `SourceTest`/`WebSpecTest` used:

```kotlin
package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.testing.SpecSamples
import com.dgmltn.shiphappens.source.webview.testing.WebSourceContract
import com.dgmltn.shiphappens.source.webview.testing.WebSpecContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class UpsContractTest {
    @Test fun spec_contract() = WebSpecContract.verify(
        UpsWebSpec,
        SpecSamples(
            trackingNumber = "1Z999AA10123456784",
            capturedApiUrl = "https://www.ups.com/track/api/Track/GetStatus?loc=en_US",
            uncapturedUrl = "https://www.ups.com/track?loc=en_US&tracknum=1Z999AA10123456784",
            notFound = listOf(DomRaw(kind = "tracker", pageText = "The tracking number you entered is invalid")),
        ),
    )

    @Test fun source_contract() = WebSourceContract.verify(
        UpsWebSpec,
        claims = listOf("1Z 999 AA1 01 2345 6784"),
        rejects = listOf("9400111899223300112"),
    )

    @Test fun track_fails_without_a_scraper() = runTest {
        WebSourceContract.verifyTrackFailsWithoutScraper(UpsWebSpec, "1Z999AA10123456784")
    }
}
```

Samples for the others:

| Carrier | trackingNumber | capturedApiUrl | uncapturedUrl | notFound | claims | rejects |
|---|---|---|---|---|---|---|
| USPS | `9434636106092288655003` | `https://tools.usps.com/go/TrackConfirmAction?tLabels=9434636106092288655003` | null (pattern deliberately broad; `UspsWebSpecTest` keeps its POLocator negative) | tracker, `"Status Not Available for this item"` | `9434 6361 0609 2288 6550 03`, `EC123456789US` | `1Z999AA10123456784`, `941234` |
| FedEx | `123456789012` | null | null | tracker, `"The tracking number you entered can't be found right now"` | `1234 5678 9012`, `123456789012345`, `12345678901234567890`, `1234567890123456789012` | `1Z999AA10123456784`, `TBA333593378975`, `9434636106092288655003`, `1234567890123`, `12345678901234567` |
| DHL eCommerce | `420300019261234500000000000042` | `https://api.dhlecs.com/webtrack/v4/tracking` | `https://api.dhlecs.com/webtrack/v4/utility/config` | tracker, `"Unfortunately, no results found. Please confirm"` | `420300019261234500000000000042`, `420 30001 9261-2345 0000 0000 0000 42` | `9261234500000000000042`, `1Z999AA10123456784`, `420300011234567890123456789012`, `42030001926123` |
| AMZL | `TBA333593378975` | `https://track.amazon.com/api/tracker/TBA333593378975` | `https://www.amazon.com/gp/your-account/order-details` | tracker, `"We couldn't find this tracking number"` | `TBA333593378975`, `tba 3335-9337-8975` | `113-1234567-1234567`, `1Z999AA10123456784`, `9434636106092288655003`, `TBA12345678` |
| Amazon | `113-1234567-1234567` | null | null | `DomRaw(kind = "cards", pageText = "There's a problem finding this order")` | `113-1234567-1234567`, `701 2345678 9012345`, `11312345671234567` | `1Z999AA10123456784`, `9434636106092288655003`, `123456789012`, `213-1234567-1234567`, `113-1234567-123456` |

Then prune the carrier `WebSpecTest`s of the cases the contract now covers verbatim: `spec_identity_and_origins` (USPS, FedEx, Amazon), `origin_rules_are_confined_to_the_webtrack_subdomain` (DHL) and `origin_rules_are_confined_to_the_tracking_subdomain` (AMZL) lose their `allowedOriginRules` assertion but keep their `cookieDomain` assertion and rationale comment, `parse_api_is_wired_to_the_parser` / `parse_api_delegates_to_usps_parser` and `api_pattern_*` cases are deleted. Everything else (FedEx's DOM-only and settle case, Amazon's JS guards, AMZL's vocabulary cases, USPS's tracking-URL and broad-pattern cases, DHL's login case) stays.

- [ ] **Step 4: Run the full suite**

Expected: PASS. The six contract classes contribute 18 tests; the deleted `SourceTest`s contributed 18 to 21, so the total moves little.

- [ ] **Step 5: Commit**

```bash
git add -A source settings.gradle.kts build-logic
git commit -m "[webview] :source:webview-testing contracts; each carrier's identity, detection, and wiring checks become one short contract test"
```

---

### Task 6: README

**Files:**
- Modify: `README.md` ("How to add a tracking source" section, and the module list if it enumerates `source/*`)

- [ ] **Step 1: Rewrite the section**

Replace the three numbered steps and the trailing paragraph with:

```markdown
## How to add a tracking source

The plugin boundary is `TrackingSource` in `source/api`. A website-scraped carrier never touches
`domain` (beyond its `Carrier` entry), `data`, or the scraping machinery:

1. **Declare the carrier** in `WellKnownCarriers` (`domain`): code, display name, accent, and the
   regex that claims its tracking-number shape. Detection everywhere derives from that pattern.
2. **Write the spec** in a new `source/<name>` module whose `build.gradle.kts` is just
   `plugins { id("shiphappens.source-module") }`. One `WebProviderSpec` holds the carrier's
   `StatusVocabulary` and `TrackerPageRules`, its extraction JS, an optional `LoginRecipe`
   (`BridgeScripts.loggedInProbe` covers the usual logout-link-or-sign-out-text check), any
   extra challenge markers, and an optional API parser returning `TrackingSnapshot`. End the file
   with `val <name>SourceModule: Module = webSourceModule(<Name>WebSpec)`.
3. **Test it** with a `<Name>ContractTest` that calls `WebSpecContract.verify` and
   `WebSourceContract.verify` from `:source:webview-testing` with the carrier's sample numbers
   and not-found wording, plus a vocabulary test over captured page strings.
4. **Register it**: `include(":source:<name>")` in `settings.gradle.kts`, the module in
   `ui/build.gradle.kts`, and `<name>SourceModule` in `appModules()`
   (`ui/src/commonMain/kotlin/com/dgmltn/shiphappens/ui/di/AppModules.kt`). `SourceRegistry`
   picks up every bound `TrackingSource` automatically.
```

If Task 4 was parked, step 2's build-file sentence instead says "copy `source/usps/build.gradle.kts`". Update the test-count sentence in "Running tests" from the Task 5 full-suite output, and add `:source:webview-testing` to any module list that enumerates the `source/*` modules.

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "[docs] README: adding a carrier is a Carrier entry, one spec file, and a contract test"
```

---

### Task 7: Finish the branch

- [ ] **Step 1: Full verification on the tree to be merged**

Run the full suite and the multi-target compile proof from Global Constraints. Both must pass.

- [ ] **Step 2: Review the branch once**

```bash
git diff main --stat
git log --oneline main..HEAD
git status --short   # gradle/gradle-daemon-jvm.properties must not appear staged
```

- [ ] **Step 3: Squash-merge locally, delete the branch, do not push**

```bash
git switch main
git merge --squash refactor/source-consolidation-2
git commit -m "[architecture] Source consolidation increment 2: carrier-owned detection, one WebSource, login recipes, convention plugin, contract tests

Carrier carries its number pattern and WellKnownCarriers.detect replaces the data-module copy.
WebSource is final and registered by webSourceModule(spec); the six per-carrier source classes are
gone. Login is a nullable LoginRecipe with a generated probe; challenge markers have a shared
default; sourceId defaults to the carrier code. A build-logic convention plugin replaces six
identical build files. :source:webview-testing exposes WebSpecContract / WebSourceContract, and
each carrier's identity, detection, and wiring checks are one short contract test. No parsing
change; every fixture keeps its result. Spec:
docs/superpowers/specs/2026-09-07-source-consolidation-design.md"
git branch -D refactor/source-consolidation-2
```

Stop and report that `main` is ready to push.

---

## Self-review against the spec

- **2.1 Carrier owns its number pattern:** Task 1. FedEx exclusion is a negative lookahead; `BuiltInCarrierDetection` is replaced by `WellKnownCarriers.detect` (the spec said "iterates `WellKnownCarriers.all`"; moving the iterator into the domain object is the same behavior with one fewer file, and `SourceRegistry` still consults it before the sources).
- **2.2 One concrete source class:** Task 2. `WebSource`, `webSourceModule`, qualified singles, `AppModules.kt` untouched, `sourceId` default (Task 3).
- **2.3 Login recipe and shared defaults:** Task 3. `LoginRecipe`, `DEFAULT_CHALLENGE_MARKERS`, `loggedInProbe`, UI treats null login as not sign-in capable while More details keeps working.
- **2.4 Convention plugin:** Task 4, with the spec's fallback made concrete as a bail-out rule.
- **2.5 Contract tests:** Task 5. The spec's `SpecSamples` gained `uncapturedUrl` as nullable because USPS's pattern is deliberately broad; the spec's "rejects the sample page URL" holds for the other four API carriers.
- **Deferred to increment 3 or later (not in the spec's Increment 2):** widening `TrackerPageRules.etaDate` to take `DomRaw` so Amazon's tracker joins the resolver, and a shared API-event helper. Both are behavior-touching and belong with a device pass.
- **Type consistency:** `Carrier.claims(normalized)`, `WellKnownCarriers.detect(raw)`, `WebSource(webSpec, scraper)`, `webSourceModule(spec)`, `LoginRecipe(url, isLoggedInJs)`, `WebProviderSpec(carrier, cookieDomain, trackingUrl, extractionJs, login, apiUrlPatterns, extraChallengeMarkers, parseApi, parseRaw, settle, sourceId)`, `SpecSamples(trackingNumber, capturedApiUrl, uncapturedUrl, notFound)`, `WebSpecContract.verify(spec, samples)`, `WebSourceContract.verify(spec, claims, rejects)`, `verifyTrackFailsWithoutScraper(spec, trackingNumber)` are used with the same names throughout.

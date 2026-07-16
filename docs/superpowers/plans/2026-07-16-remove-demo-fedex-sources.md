# Remove Demo and FedEx Sources Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the demo-data source and the FedEx stub source now that UPS, USPS, and Amazon are real, and prune every framework hook (`ConfigField`/`configSpec`, `testConnection`, `SourceConfigProvider`, `SeedingSource`, `SourceKind`/`UNIVERSAL`) that only those two sources exercised — per `docs/superpowers/specs/2026-07-16-remove-demo-fedex-sources-design.md`.

**Architecture:** Six sequential tasks, each leaving the whole repo compiling and green. Tasks 1–2 delete the two modules and their DI wiring, shedding only the tests that were entirely about the deleted feature. Tasks 3–4 shrink the shared `TrackingSource`/`SourceDescriptor` contract in `:source:api` (credential-config machinery, then seeding/`SourceKind`), fixing every implementor and caller atomically — Kotlin enforces interface conformance at compile time, so these can't be split into a red/green TDD cycle; each is one cohesive commit verified by a full build. Task 5 rewrites `SettingsViewModelTest` onto the three real sources. Task 6 is the final cross-module verification sweep.

**Tech Stack:** Kotlin Multiplatform, Koin, Room, kotlinx-coroutines-test.

## Global Constraints

- Commit subjects start with a bracketed one-word topic: `[source]`, `[data]`, `[ui]`, `[docs]`.
- **Keep, do not remove:** `WellKnownCarriers.FEDEX`, the FedEx regex/case in `BuiltInCarrierDetection`, the manual carrier picker (built from `WellKnownCarriers.all`), and FedEx strings in List/Detail preview screens. FedEx packages must remain addable/brandable as unrefreshable cards with no source.
- **Keep, do not remove:** `SourceConfig(enabled, values)` — `values` stores the web-login `loggedIn` flag consumed by `SettingsViewModel`/`onSignOut`; it is unrelated to the credential-`ConfigField` machinery being removed.
- Unit tests for KMP modules run on the JVM target: `./gradlew :<module>:jvmTest`. UI/Android host tests: `./gradlew :ui:testAndroidHostTest`. iOS compile check: `./gradlew :ui:compileKotlinIosSimulatorArm64`.
- No migration code for existing demo-seeded parcels — per spec §6, they become ordinary deletable cards; a refresh on a demo-seeded USPS/UPS-shaped number will just NOT_FOUND harmlessly.
- Before any `git add -A`, watch for an auto-generated `gradle/gradle-daemon-jvm.properties` — do NOT commit it.

---

### Task 1: Delete `:source:demo` module and its wiring

**Files:**
- Delete: `source/demo/` (entire directory)
- Modify: `settings.gradle.kts`
- Modify: `ui/build.gradle.kts`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt`
- Modify: `ui/src/androidHostTest/kotlin/com/shiphappens/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: no `DemoSource`/`demoSourceModule` anywhere in the tree. `SettingsUiState.universal` still exists (Task 4 removes it) but is always empty in production until then. `SettingsViewModelTest.vm()` no longer takes a `demo` source in its registry.

- [ ] **Step 1: Delete the module**

```bash
git rm -r source/demo
```

- [ ] **Step 2: Remove the module include**

In `settings.gradle.kts`, delete this line:

```kotlin
include(":source:demo")
```

- [ ] **Step 3: Remove the Gradle dependency**

In `ui/build.gradle.kts`, delete this line from `commonMain.dependencies`:

```kotlin
            implementation(projects.source.demo)
```

- [ ] **Step 4: Remove the DI wiring**

In `AppModules.kt`, delete the import:

```kotlin
import com.shiphappens.source.demo.demoSourceModule
```

and delete this line from `appModules()`:

```kotlin
    demoSourceModule,
```

- [ ] **Step 5: Fix `SettingsViewModelTest`**

Delete the import:

```kotlin
import com.shiphappens.source.demo.DemoSource
```

Replace the `vm()` helper (currently constructs `demo` and passes it into the registry):

```kotlin
    private suspend fun TestScope.vm(extraCarriers: List<TrackingSource> = emptyList()): SettingsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = kotlin.io.path.createTempDirectory("settingsvm").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        // UpsWebSource(NoWebScraper) is the real webview-scraping source (empty configSpec,
        // testConnection always succeeds; see UpsSource.kt). Tests that need to exercise
        // credential-field editing / testConnection failure-vs-success toasts do so against an
        // extra unchanged credential-stub source (e.g. FedexSource) passed in via [extraCarriers],
        // since UPS itself no longer has any credentials to configure.
        val registry = SourceRegistry(listOf(UpsWebSource(NoWebScraper)) + extraCarriers, settings)
        repo = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        vm = SettingsViewModel(registry, settings, repo, NoOpCookieJar)
        // Records every emission and keeps WhileSubscribed alive for the whole test.
        backgroundScope.launch { vm.state.collect { check(recordedStates.tryEmit(it)) } }
        // Prime the pipeline: the first combined emission requires settings.settings' initial load.
        awaitState { it.carriers.isNotEmpty() }
        return vm
    }
```

(Only two changes from the current body: the registry list drops `demo,`, and the priming `awaitState` predicate switches from `it.universal.isNotEmpty()` to `it.carriers.isNotEmpty()` — with no demo source, `universal` never becomes non-empty and the old predicate would hang forever.)

Replace `sources_are_grouped_and_default_disabled`:

```kotlin
    @Test fun sources_are_grouped_and_default_disabled() = runTest {
        val vm = vm()
        val s = awaitState { it.carriers.isNotEmpty() }
        assertTrue(s.universal.isEmpty())
        assertEquals(listOf("ups"), s.carriers.map { it.id })
        assertEquals("Not connected", s.carriers.single().statusText)
    }
```

Delete `toggle_enables_and_seeds_demo` entirely (the demo-seeding feature it tests no longer exists):

```kotlin
    @Test fun toggle_enables_and_seeds_demo() = runTest {
        val vm = vm()
        vm.onToggle("demo")
        val s = awaitState { it.universal.singleOrNull()?.enabled == true }
        assertTrue(s.universal.single().enabled)
        assertEquals("Connected · 1,000+ couriers", s.universal.single().statusText)
        // Await seeding first (a bare first{} would race the flow's initial empty emission),
        // then assert the exact count on a FRESH read so over-seeding beyond 7 still fails.
        repo.observeParcels(false).first { it.size >= 7 }
        assertEquals(7, repo.observeParcels(false).first().size)  // demo seeds flowed through
    }
```

- [ ] **Step 6: Run tests to verify green**

Run: `./gradlew :ui:testAndroidHostTest 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add -A -- settings.gradle.kts ui/build.gradle.kts ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt ui/src/androidHostTest/kotlin/com/shiphappens/ui/settings/SettingsViewModelTest.kt
git status  # confirm source/demo/ deletion staged and no unrelated files (e.g. gradle-daemon-jvm.properties)
git add source/demo
git commit -m "[source] Delete demo-data source"
```

---

### Task 2: Delete `:source:fedex` module and its wiring

**Files:**
- Delete: `source/fedex/` (entire directory)
- Modify: `settings.gradle.kts`
- Modify: `ui/build.gradle.kts`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt`
- Modify: `ui/src/androidHostTest/kotlin/com/shiphappens/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: no `FedexSource`/`fedexSourceModule` anywhere. `SettingsViewModelTest.vm()` drops its `extraCarriers` parameter (nothing left to pass one).

- [ ] **Step 1: Delete the module**

```bash
git rm -r source/fedex
```

- [ ] **Step 2: Remove the module include**

In `settings.gradle.kts`, delete this line:

```kotlin
include(":source:fedex")
```

- [ ] **Step 3: Remove the Gradle dependency**

In `ui/build.gradle.kts`, delete this line from `commonMain.dependencies`:

```kotlin
            implementation(projects.source.fedex)
```

- [ ] **Step 4: Remove the DI wiring**

In `AppModules.kt`, delete the import:

```kotlin
import com.shiphappens.source.fedex.fedexSourceModule
```

and delete this line from `appModules()`:

```kotlin
    fedexSourceModule,
```

- [ ] **Step 5: Delete the now-untestable credential tests in `SettingsViewModelTest`**

FedEx was the only credentialed stub source in the test file; the config-field/test-connection
machinery it exercised is deleted in Task 3, so retargeting these tests would be immediately
thrown away. Delete both tests outright:

```kotlin
    /** True once [key]'s field on the [sourceId] carrier card holds [value] — the edit has committed. */
    private fun fieldCommitted(sourceId: String, key: String, value: String): (SettingsUiState) -> Boolean =
        { it.carriers.firstOrNull { c -> c.id == sourceId }?.fields?.any { f -> f.key == key && f.value == value } == true }

    // NOTE (controller-authorized expectation change): UPS is now UpsWebSource — a web-scraping
    // source with an empty configSpec (see UpsSource.kt) — so once enabled its status is always
    // "Direct API coming soon" (implemented == false for NoWebScraper) and it has no credential
    // fields left to edit. The credential-field-editing behavior this test exercises now lives on
    // FedEx, an unchanged implemented=false credential stub with a real configSpec (the same
    // shape UPS used to have). Field edits still persist normally; status still doesn't
    // transition past "Direct API coming soon" for either stub, since implemented=false wins.
    @Test fun field_edits_persist_and_change_status() = runTest {
        val vm = vm(listOf(FedexSource()))
        vm.onToggle("ups")
        val enabled = awaitState { it.carriers.firstOrNull { c -> c.id == "ups" }?.enabled == true }
        assertEquals("Direct API coming soon", enabled.carriers.first { it.id == "ups" }.statusText)

        vm.onToggle("fedex")
        val fedexEnabled = awaitState { it.carriers.firstOrNull { c -> c.id == "fedex" }?.enabled == true }
        assertEquals("Direct API coming soon", fedexEnabled.carriers.first { it.id == "fedex" }.statusText)
        // Fired back-to-back on purpose: updateSourceConfig makes concurrent same-source edits
        // atomic, so both fields must land (this exercises the race fix at the VM level).
        vm.onField("fedex", "apiKey", "abc"); vm.onField("fedex", "secretKey", "shh")
        val configured = awaitState { fieldCommitted("fedex", "apiKey", "abc")(it) }
        assertEquals("Direct API coming soon", configured.carriers.first { it.id == "fedex" }.statusText)
        assertEquals("abc", settings.current("fedex")["apiKey"])
    }

    // UPS (UpsWebSource) has no credentials to test — testConnection always succeeds — so the
    // failure/success toast behavior this test exercises is retargeted to FedEx, an unchanged
    // credential stub.
    @Test fun test_connection_toasts() = runTest {
        val vm = vm(listOf(FedexSource()))
        vm.onTest("fedex")
        assertEquals(
            "Enter FedEx credentials first",
            awaitRecorded { it.toast == "Enter FedEx credentials first" }.toast,
        )
        vm.onField("fedex", "apiKey", "a"); vm.onField("fedex", "secretKey", "b")
        // Both edits must be committed before onTest reads settings.current("fedex") — otherwise
        // the second test-connection could legitimately still see missing credentials.
        awaitState { fieldCommitted("fedex", "apiKey", "a")(it) && fieldCommitted("fedex", "secretKey", "b")(it) }
        vm.onTest("fedex")
        assertEquals(
            "FedEx credentials look valid",
            awaitRecorded { it.toast == "FedEx credentials look valid" }.toast,
        )
    }
```

Replace the `vm()` helper's signature and body to drop the now-unused `extraCarriers` parameter
(no remaining test in this file passes one):

```kotlin
    private suspend fun TestScope.vm(): SettingsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = kotlin.io.path.createTempDirectory("settingsvm").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        val registry = SourceRegistry(listOf(UpsWebSource(NoWebScraper)), settings)
        repo = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        vm = SettingsViewModel(registry, settings, repo, NoOpCookieJar)
        // Records every emission and keeps WhileSubscribed alive for the whole test.
        backgroundScope.launch { vm.state.collect { check(recordedStates.tryEmit(it)) } }
        // Prime the pipeline: the first combined emission requires settings.settings' initial load.
        awaitState { it.carriers.isNotEmpty() }
        return vm
    }
```

- [ ] **Step 6: Run tests to verify green**

Run: `./gradlew :ui:testAndroidHostTest 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL. Remaining tests: `sources_are_grouped_and_default_disabled`,
`sync_settings_roundtrip`, `ups_card_is_web_capable_and_sign_out_clears_flag`.

- [ ] **Step 7: Commit**

```bash
git add -A -- settings.gradle.kts ui/build.gradle.kts ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt ui/src/androidHostTest/kotlin/com/shiphappens/ui/settings/SettingsViewModelTest.kt
git status  # confirm source/fedex/ deletion staged and no unrelated files
git add source/fedex
git commit -m "[source] Delete FedEx stub source"
```

---

### Task 3: Prune credential-config machinery from `:source:api`

**Files:**
- Modify: `source/api/src/commonMain/kotlin/com/shiphappens/source/api/TrackingSource.kt`
- Modify: `source/api/src/commonMain/kotlin/com/shiphappens/source/api/SourceConfig.kt`
- Modify: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/WebViewBasedSource.kt`
- Modify: `data/src/commonMain/kotlin/com/shiphappens/data/settings/SettingsRepository.kt`
- Modify: `data/src/commonMain/kotlin/com/shiphappens/data/di/DataModule.kt`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/settings/SettingsViewModel.kt`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/settings/SettingsScreen.kt`
- Modify: `source/ups/src/commonTest/kotlin/com/shiphappens/source/ups/UpsSourceTest.kt`
- Modify: `source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsSourceTest.kt`
- Modify: `source/amazon/src/commonTest/kotlin/com/shiphappens/source/amazon/AmazonSourceTest.kt`
- Modify: `data/src/jvmTest/kotlin/com/shiphappens/data/source/FakeSource.kt`
- Modify: `data/src/jvmTest/kotlin/com/shiphappens/data/ParcelRepositoryTest.kt`
- Modify: `ui/src/androidHostTest/kotlin/com/shiphappens/ui/list/ListViewModelTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: `TrackingSource` interface with no `testConnection` method; `SourceDescriptor` with no
  `configSpec` field; no `ConfigField` type; no `SourceConfigProvider` interface anywhere.
  `SourceDescriptor(id, displayName, kind, accentColorHex = null, implemented = true)` — this is
  an intermediate signature; Task 4 removes `kind` too. `SettingsUiState.carriers` items no longer
  have `fields`/`endpointText`.

This is a single interface-shrink: Kotlin enforces override consistency at compile time, so every
implementor and caller must change together. There is no meaningful red/green split — verify with
one full build at the end.

- [ ] **Step 1: Shrink `TrackingSource.kt`**

Delete the `ConfigField` data class, the `configSpec` field from `SourceDescriptor`, and
`testConnection` from the `TrackingSource` interface:

```kotlin
package com.shiphappens.source.api

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.TrackingSnapshot

enum class SourceKind { UNIVERSAL, CARRIER }

data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val kind: SourceKind,
    val accentColorHex: String? = null,
    /** False for stub sources that declare themselves but don't actually fetch live data yet. */
    val implemented: Boolean = true,
)

enum class FailureReason { AUTH, NETWORK, NOT_FOUND, RATE_LIMITED, UNKNOWN }

sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val reason: FailureReason, val message: String? = null) : SourceResult<Nothing>
}

interface TrackingSource {
    val descriptor: SourceDescriptor
    /** Cheap, local-only recognition. Null = "not mine / don't know". */
    fun detectCarrier(trackingNumber: String): Carrier?
    /**
     * Implementations should return [SourceResult.Failure] rather than throwing; the repository
     * additionally guards against thrown exceptions.
     */
    suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot>
}

data class SeedParcel(val name: String, val trackingNumber: String, val carrier: Carrier)

/** Optional capability: a source that seeds parcels when enabled (the demo source). */
interface SeedingSource {
    fun seeds(): List<SeedParcel>
}
```

(`SourceKind`, `SeedParcel`, `SeedingSource` are untouched here — Task 4 removes them.)

- [ ] **Step 2: Remove `SourceConfigProvider` from `SourceConfig.kt`**

```kotlin
package com.shiphappens.source.api

import kotlinx.serialization.Serializable

@Serializable
data class SourceConfig(
    val enabled: Boolean = false,
    val values: Map<String, String> = emptyMap(),
) {
    operator fun get(key: String): String? = values[key]?.takeIf { it.isNotBlank() }
}
```

- [ ] **Step 3: Fix `WebViewBasedSource.kt`**

Update the class doc (it references `configSpec`, which no longer exists) and drop the
`configSpec` line and the `testConnection` override:

```kotlin
package com.shiphappens.source.webview

import com.shiphappens.domain.TrackingSnapshot
import com.shiphappens.domain.Carrier
import com.shiphappens.source.api.FailureReason
import com.shiphappens.source.api.SourceDescriptor
import com.shiphappens.source.api.SourceKind
import com.shiphappens.source.api.SourceResult
import com.shiphappens.source.api.TrackingSource

/** Marker capability: lets UI find the web recipe for a carrier (More-details screen, login). */
interface WebCapableSource {
    val webSpec: WebProviderSpec
}

/**
 * A TrackingSource whose data comes from driving the carrier's own website. Subclasses supply
 * only [detectCarrier]; everything else derives from the [webSpec] recipe. No credential fields —
 * auth is an optional cookie session established in the login WebView.
 */
abstract class WebViewBasedSource(
    final override val webSpec: WebProviderSpec,
    private val scraper: WebScraper,
) : TrackingSource, WebCapableSource {

    final override val descriptor = SourceDescriptor(
        id = webSpec.sourceId,
        displayName = webSpec.carrier.displayName,
        kind = SourceKind.CARRIER,
        accentColorHex = webSpec.carrier.accentColorHex,
        implemented = scraper.isAvailable,
    )

    abstract override fun detectCarrier(trackingNumber: String): Carrier?

    final override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val name = webSpec.carrier.displayName
        if (!scraper.isAvailable) {
            return SourceResult.Failure(FailureReason.UNKNOWN, "$name web tracking isn't available on this platform yet")
        }
        return when (val result = scraper.scrape(webSpec, trackingNumber)) {
            is ScrapeResult.Payloads -> {
                val router = PayloadRouter(webSpec)
                val routed = result.payloads.map(router::route)
                routed.firstNotNullOfOrNull { (it as? RouteResult.Tracking)?.tracking }
                    ?.let { return SourceResult.Success(it.toSnapshot()) }
                // A goto hop's embedded coarse tracking is the designed fallback when the hop's target
                // page never produced a rich extraction (design spec §1) — and it outranks the error
                // ladder because the page that emitted it was already past login and order lookup.
                routed.firstNotNullOfOrNull { (it as? RouteResult.Goto)?.tracking }
                    ?.let { return SourceResult.Success(it.toSnapshot()) }
                when {
                    routed.any { it is RouteResult.LoginWall } ->
                        SourceResult.Failure(FailureReason.AUTH, "Sign in to $name in Settings, then refresh")
                    routed.any { it is RouteResult.Challenge } ->
                        SourceResult.Failure(FailureReason.RATE_LIMITED, "$name wants a human check — open More details to continue")
                    routed.any { it is RouteResult.NotFound } ->
                        SourceResult.Failure(FailureReason.NOT_FOUND, "$name doesn't recognize this number")
                    else -> SourceResult.Failure(FailureReason.UNKNOWN, "Couldn't read tracking data from $name")
                }
            }
            is ScrapeResult.LoadError -> SourceResult.Failure(FailureReason.NETWORK, result.message)
            ScrapeResult.Timeout -> SourceResult.Failure(FailureReason.NETWORK, "Timed out loading $name")
            ScrapeResult.Unavailable -> SourceResult.Failure(FailureReason.UNKNOWN, "$name web tracking unavailable")
        }
    }
}
```

(Only the `SourceConfig` import and the `testConnection` override are gone versus the current
file; `SourceKind`/`kind =` stay for this task.)

- [ ] **Step 4: Fix `SettingsRepository.kt`**

Delete the `SourceConfigProvider` import, drop `: SourceConfigProvider` from the class header, and
delete the `current()` override (its last two lines):

```kotlin
class SettingsRepository(private val dataStore: DataStore<Preferences>) {
```

Delete:

```kotlin
    override suspend fun current(sourceId: String): SourceConfig =
        settings.first().sourceConfigs[sourceId] ?: SourceConfig()
```

(leave the trailing `}` that closes the class in place).

- [ ] **Step 5: Fix `DataModule.kt`**

Delete the import:

```kotlin
import com.shiphappens.source.api.SourceConfigProvider
```

Change:

```kotlin
    single { SettingsRepository(get()) } bind SourceConfigProvider::class
```

to:

```kotlin
    single { SettingsRepository(get()) }
```

- [ ] **Step 6: Fix `SettingsViewModel.kt`**

Delete the `FieldUi` data class. Remove `fields` and `endpointText` from `SourceCardUi`:

```kotlin
data class SourceCardUi(
    val id: String, val name: String, val accentHex: String, val enabled: Boolean,
    val statusText: String, val statusColorHex: String,
    val webCapable: Boolean = false, val signedIn: Boolean = false,
)
```

Rewrite the `state` combine block's card construction (drop the `configured` check, `fields`,
`endpointText`, and rename the `!d.implemented` message to `"Coming soon"`):

```kotlin
    val state: StateFlow<SettingsUiState> = combine(settings.settings, toast) { s, t ->
        val cards = registry.all().map { src ->
            val d = src.descriptor
            val cfg = s.sourceConfigs[d.id] ?: SourceConfig()
            val (statusText, statusColor) = when {
                !cfg.enabled -> "Not connected" to "#A8A296"
                !d.implemented -> "Coming soon" to "#A8A296"
                d.kind == SourceKind.UNIVERSAL -> "Connected · 1,000+ couriers" to (d.accentColorHex ?: "#1F7A4D")
                else -> "Connected · syncing" to "#1F7A4D"
            }
            val webSpec = (src as? WebCapableSource)?.webSpec
            SourceCardUi(
                id = d.id, name = d.displayName,
                accentHex = d.accentColorHex ?: Carrier(d.id, d.displayName).accentHex(),
                enabled = cfg.enabled, statusText = statusText, statusColorHex = statusColor,
                webCapable = webSpec != null,
                signedIn = webSpec != null && cfg.values["loggedIn"] == "true",
            )
        }
        SettingsUiState(
            universal = cards.filter { c -> registry.all().first { it.descriptor.id == c.id }.descriptor.kind == SourceKind.UNIVERSAL },
            carriers = cards.filter { c -> registry.all().first { it.descriptor.id == c.id }.descriptor.kind == SourceKind.CARRIER },
            autoImport = s.autoClipboardImport,
            frequency = s.refreshFrequency,
            toast = t,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
```

(`universal`/`kind`-based filtering stays for this task — Task 4 removes it.)

Delete `onField` and `onTest`:

```kotlin
    fun onField(sourceId: String, key: String, value: String) {
        viewModelScope.launch {
            settings.updateSourceConfig(sourceId) { it.copy(values = it.values + (key to value)) }
        }
    }

    fun onTest(sourceId: String) {
        viewModelScope.launch {
            val src = registry.all().firstOrNull { it.descriptor.id == sourceId } ?: return@launch
            when (val r = src.testConnection(settings.current(sourceId))) {
                is SourceResult.Success -> flash("${src.descriptor.displayName} credentials look valid")
                is SourceResult.Failure -> flash(r.message ?: "Couldn't reach ${src.descriptor.displayName}")
            }
        }
    }
```

- [ ] **Step 7: Fix `SettingsScreen.kt`**

In `SettingsScreen`, drop `onField = vm::onField,` and `onTest = vm::onTest,` from the
`SettingsContent(...)` call, and drop the corresponding lambdas from its own signature:

```kotlin
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenLogin: (String) -> Unit = {}, vm: SettingsViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    SettingsContent(
        state = s,
        onBack = onBack,
        onToggle = vm::onToggle,
        onAutoImport = vm::onAutoImport,
        onFrequency = vm::onFrequency,
        onSignIn = onOpenLogin,
        onSignOut = { vm.onSignOut(it) },
    )
}
```

In `SettingsContent`, drop `onField`/`onTest` from the signature and from both `SourceCard(...)`
calls:

```kotlin
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit = {},
    onToggle: (String) -> Unit = {},
    onAutoImport: (Boolean) -> Unit = {},
    onFrequency: (RefreshFrequency) -> Unit = {},
    onSignIn: (String) -> Unit = {},
    onSignOut: (String) -> Unit = {},
) {
```

```kotlin
                SectionLabel("Universal API")
                state.universal.forEach { SourceCard(it, onToggle, onSignIn, onSignOut) }
                Spacer(Modifier.height(10.dp))
                SectionLabel("Direct carrier APIs")
                state.carriers.forEach { SourceCard(it, onToggle, onSignIn, onSignOut) }
```

(`Universal API` section stays structurally for this task — Task 4 removes it.)

In `SourceCard`, drop the `onField`/`onTest` parameters and the field-rendering and
endpoint/test-connection blocks:

```kotlin
@Composable
private fun SourceCard(
    card: SourceCardUi,
    onToggle: (String) -> Unit,
    onSignIn: (String) -> Unit,
    onSignOut: (String) -> Unit,
) {
    val accent = colorFromHex(card.accentHex)
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent),
                contentAlignment = Alignment.Center,
            ) { Text("📦", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
            Column(Modifier.weight(1f)) {
                Text(card.name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = ShipColors.ink, fontFamily = hankenFamily())
                Text(card.statusText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colorFromHex(card.statusColorHex))
            }
            Switch(
                checked = card.enabled, onCheckedChange = { onToggle(card.id) },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, uncheckedTrackColor = ShipColors.toggleOff),
            )
        }
        if (card.webCapable && card.enabled) {
            TextButton(onClick = { if (card.signedIn) onSignOut(card.id) else onSignIn(card.id) }) {
                Text(if (card.signedIn) "Sign out of ${card.name}" else "Sign in to ${card.name}", fontSize = 13.sp)
            }
        }
    }
}
```

The following imports in `SettingsScreen.kt` were only used by the deleted field-rendering block
(`TextButton` is still used by the sign-in/sign-out button and must stay) — delete these four:

```kotlin
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
```

Update both previews to drop `fields`/`endpointText` args (the `universal` blocks and the fedex
card stay structurally for this task — Task 4 rewrites them):

```kotlin
@Preview
@Composable
private fun Preview_SettingsContent_SourcesDisabled() {
    ShipTheme {
        SettingsContent(
            SettingsUiState(
                universal = listOf(
                    SourceCardUi(
                        id = "demo", name = "Demo data", accentHex = "#17150F", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                    ),
                ),
                carriers = listOf(
                    SourceCardUi(
                        id = "ups", name = "UPS", accentHex = "#5A3A22", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                    ),
                    SourceCardUi(
                        id = "usps", name = "USPS", accentHex = "#1E3A8F", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                    ),
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_SettingsContent_SourcesEnabled() {
    ShipTheme {
        SettingsContent(
            SettingsUiState(
                universal = listOf(
                    SourceCardUi(
                        id = "demo", name = "Demo data", accentHex = "#17150F", enabled = true,
                        statusText = "Connected · demo parcels", statusColorHex = "#1F7A4D",
                    ),
                ),
                carriers = listOf(
                    SourceCardUi(
                        id = "ups", name = "UPS", accentHex = "#5A3A22", enabled = true,
                        statusText = "Enabled · add your credentials", statusColorHex = "#C2410C",
                    ),
                    SourceCardUi(
                        id = "fedex", name = "FedEx", accentHex = "#5A1B9A", enabled = true,
                        statusText = "Coming soon", statusColorHex = "#A8A296",
                    ),
                ),
                autoImport = true,
                frequency = RefreshFrequency.ONE_HOUR,
            ),
        )
    }
}
```

- [ ] **Step 8: Fix the three source test files**

In `source/ups/src/commonTest/kotlin/com/shiphappens/source/ups/UpsSourceTest.kt`, replace:

```kotlin
    @Test fun descriptor_declares_oauth_fields() {
        assertEquals("ups", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertTrue(src.descriptor.configSpec.isEmpty())
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }
    @Test fun detects_1z_numbers_only() {
        assertEquals(WellKnownCarriers.UPS, src.detectCarrier("1Z 999 AA1 01 2345 6784"))
        assertNull(src.detectCarrier("9400111899223300112"))
    }
    @Test fun track_is_not_implemented_and_test_connection_always_succeeds() = runTest {
        assertIs<SourceResult.Failure>(src.track("1Z999AA10123456784", null))
        assertIs<SourceResult.Success<Unit>>(src.testConnection(SourceConfig(enabled = true)))
    }
```

with:

```kotlin
    @Test fun descriptor_declares_carrier_kind() {
        assertEquals("ups", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }
    @Test fun detects_1z_numbers_only() {
        assertEquals(WellKnownCarriers.UPS, src.detectCarrier("1Z 999 AA1 01 2345 6784"))
        assertNull(src.detectCarrier("9400111899223300112"))
    }
    @Test fun track_is_not_implemented() = runTest {
        assertIs<SourceResult.Failure>(src.track("1Z999AA10123456784", null))
    }
```

In `source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsSourceTest.kt`, replace:

```kotlin
    @Test fun descriptor_is_configless_web_carrier() {
        assertEquals("usps", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertTrue(src.descriptor.configSpec.isEmpty())
        assertFalse(src.descriptor.implemented)           // NoWebScraper => not implemented
    }
```
```kotlin
    @Test fun track_unavailable_without_scraper_and_test_connection_succeeds() = runTest {
        assertIs<SourceResult.Failure>(src.track("9434636106092288655003", null))
        assertIs<SourceResult.Success<Unit>>(src.testConnection(SourceConfig(enabled = true)))
    }
```

with:

```kotlin
    @Test fun descriptor_is_web_carrier() {
        assertEquals("usps", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertFalse(src.descriptor.implemented)           // NoWebScraper => not implemented
    }
```
```kotlin
    @Test fun track_unavailable_without_scraper() = runTest {
        assertIs<SourceResult.Failure>(src.track("9434636106092288655003", null))
    }
```

In `source/amazon/src/commonTest/kotlin/com/shiphappens/source/amazon/AmazonSourceTest.kt`,
delete the `SourceConfig` import if now unused (check: `SourceConfig(enabled = true)` was only
used by the deleted `testConnection` assertion), and replace:

```kotlin
    @Test fun descriptor_is_configless_web_carrier() {
        assertEquals("amazon", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertTrue(src.descriptor.configSpec.isEmpty())
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }
```
```kotlin
    @Test fun track_unavailable_without_scraper_and_test_connection_succeeds() = runTest {
        assertIs<SourceResult.Failure>(src.track("113-1234567-1234567", null))
        assertIs<SourceResult.Success<Unit>>(src.testConnection(SourceConfig(enabled = true)))
    }
```

with:

```kotlin
    @Test fun descriptor_is_web_carrier() {
        assertEquals("amazon", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }
```
```kotlin
    @Test fun track_unavailable_without_scraper() = runTest {
        assertIs<SourceResult.Failure>(src.track("113-1234567-1234567", null))
    }
```

Check the top of `AmazonSourceTest.kt`: if `SourceConfig` is no longer referenced anywhere else in
the file, delete its import line (`import com.shiphappens.source.api.SourceConfig`); if the file
imports `com.shiphappens.source.api.*` there is no line to remove.

- [ ] **Step 9: Fix `FakeSource.kt` (data jvmTest helper)**

Delete the `testConnection` override:

```kotlin
class FakeSource(
    id: String,
    kind: SourceKind = SourceKind.CARRIER,
    private val detects: Carrier? = null,
    var trackResult: SourceResult<TrackingSnapshot> = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)),
    implemented: Boolean = true,
) : TrackingSource {
    override val descriptor = SourceDescriptor(id, id.uppercase(), kind, implemented = implemented)
    val trackedNumbers = mutableListOf<String>()
    override fun detectCarrier(trackingNumber: String): Carrier? = detects
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        trackedNumbers += trackingNumber
        return trackResult
    }
}
```

- [ ] **Step 10: Fix `ParcelRepositoryTest.kt`**

Delete the `testConnection` override from both local fakes (leave everything else — `SeedingFake`,
`SourceKind.UNIVERSAL` usages, and the seeding test stay for this task; Task 4 removes them):

```kotlin
class SeedingFake : TrackingSource, SeedingSource {
    override val descriptor = SourceDescriptor("seeder", "Seeder", SourceKind.UNIVERSAL)
    override fun detectCarrier(trackingNumber: String): Carrier? = null
    override suspend fun track(trackingNumber: String, carrier: Carrier?) =
        SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT))
    override fun seeds() = listOf(SeedParcel("Baseball cap", "1ZW463200377332024", WellKnownCarriers.USPS))
}

/** Always throws instead of returning a Failure — exercises the runCatching guard in refreshRow. */
class ThrowingSource : TrackingSource {
    override val descriptor = SourceDescriptor("boom", "Boom", SourceKind.UNIVERSAL)
    override fun detectCarrier(trackingNumber: String): Carrier? = null
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> =
        throw IllegalStateException("source exploded")
}
```

- [ ] **Step 11: Fix `ListViewModelTest.kt`**

Delete the `testConnection` override from the local `FakeSource`:

```kotlin
private class FakeSource(
    var snapshot: TrackingSnapshot = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 12)),
) : TrackingSource {
    /** When set, track() suspends until completed — lets tests observe the mid-refresh state. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override val descriptor = SourceDescriptor("fake", "Fake", SourceKind.UNIVERSAL)
    override fun detectCarrier(trackingNumber: String): Carrier? = null
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        gate?.await()
        return SourceResult.Success(snapshot)
    }
}
```

- [ ] **Step 12: Run the full verification sweep**

Run:
```bash
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest :source:webview:jvmTest :source:ups:jvmTest :source:usps:jvmTest :source:amazon:jvmTest :ui:testAndroidHostTest 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 13: Commit**

```bash
git add source/api source/webview data/src/commonMain/kotlin/com/shiphappens/data/settings/SettingsRepository.kt data/src/commonMain/kotlin/com/shiphappens/data/di/DataModule.kt ui/src/commonMain/kotlin/com/shiphappens/ui/settings ui/src/androidHostTest/kotlin/com/shiphappens/ui/list/ListViewModelTest.kt source/ups/src/commonTest source/usps/src/commonTest source/amazon/src/commonTest data/src/jvmTest
git commit -m "[source] Remove credential-config machinery (ConfigField, testConnection, SourceConfigProvider)"
```

---

### Task 4: Prune seeding and `SourceKind`/`UNIVERSAL`

**Files:**
- Modify: `source/api/src/commonMain/kotlin/com/shiphappens/source/api/TrackingSource.kt`
- Modify: `data/src/commonMain/kotlin/com/shiphappens/data/ParcelRepository.kt`
- Modify: `data/src/commonMain/kotlin/com/shiphappens/data/source/SourceRegistry.kt`
- Modify: `source/webview/src/commonMain/kotlin/com/shiphappens/source/webview/WebViewBasedSource.kt`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/settings/SettingsViewModel.kt`
- Modify: `ui/src/commonMain/kotlin/com/shiphappens/ui/settings/SettingsScreen.kt`
- Modify: `data/src/jvmTest/kotlin/com/shiphappens/data/source/FakeSource.kt`
- Modify: `data/src/jvmTest/kotlin/com/shiphappens/data/source/SourceRegistryTest.kt`
- Modify: `data/src/jvmTest/kotlin/com/shiphappens/data/ParcelRepositoryTest.kt`
- Modify: `ui/src/androidHostTest/kotlin/com/shiphappens/ui/list/ListViewModelTest.kt`
- Modify: `source/ups/src/commonTest/kotlin/com/shiphappens/source/ups/UpsSourceTest.kt`
- Modify: `source/usps/src/commonTest/kotlin/com/shiphappens/source/usps/UspsSourceTest.kt`
- Modify: `source/amazon/src/commonTest/kotlin/com/shiphappens/source/amazon/AmazonSourceTest.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: final `SourceDescriptor(id, displayName, accentColorHex = null, implemented = true)` —
  no `kind`. No `SourceKind` enum, no `SeedParcel`/`SeedingSource`, no `SettingsUiState.universal`.
  `SourceRegistry.sourceFor` = pinned → detect → null. Task 5 consumes this final shape.

Again a single-commit interface shrink — verify with one full build.

- [ ] **Step 1: Finish shrinking `TrackingSource.kt`**

```kotlin
package com.shiphappens.source.api

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.TrackingSnapshot

data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val accentColorHex: String? = null,
    /** False for stub sources that declare themselves but don't actually fetch live data yet. */
    val implemented: Boolean = true,
)

enum class FailureReason { AUTH, NETWORK, NOT_FOUND, RATE_LIMITED, UNKNOWN }

sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val reason: FailureReason, val message: String? = null) : SourceResult<Nothing>
}

interface TrackingSource {
    val descriptor: SourceDescriptor
    /** Cheap, local-only recognition. Null = "not mine / don't know". */
    fun detectCarrier(trackingNumber: String): Carrier?
    /**
     * Implementations should return [SourceResult.Failure] rather than throwing; the repository
     * additionally guards against thrown exceptions.
     */
    suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot>
}
```

- [ ] **Step 2: Fix `ParcelRepository.kt`**

Delete `seedEnabledSources()` and its call site in `refreshAll`:

```kotlin
    suspend fun refreshAll(force: Boolean): RefreshSummary {
        val staleAfter = settings.settings.first().refreshFrequency.staleAfterMinutes
        if (!force && staleAfter == null) return RefreshSummary(0, 0)  // MANUAL
        val cutoff = staleAfter?.let { clock.now() - it.minutes }
        val candidates = dao.allActive().filter { e ->
            e.status != TrackingStatus.DELIVERED.name &&
                (force || e.lastRefreshedAt == null ||
                    (cutoff != null && e.lastRefreshedAt < cutoff.toEpochMilliseconds()))
        }
        var failed = 0
        var firstReason: FailureReason? = null
        for (e in candidates) {
            val outcome = refreshRow(e.id)
            if (outcome is RefreshOutcome.Failed) {
                failed++
                if (firstReason == null) firstReason = outcome.reason
            }
        }
        return RefreshSummary(candidates.size, failed, firstReason)
    }
}
```

(This removes the `seedEnabledSources()` private method and the `seedEnabledSources()` call that
was the first line of the old `refreshAll`; the rest of the method body is unchanged.)

- [ ] **Step 3: Fix `SourceRegistry.kt`**

```kotlin
    suspend fun sourceFor(parcel: Parcel): TrackingSource? {
        // Only resolve among sources that actually implement live tracking — stub carrier
        // sources (implemented = false) must not intercept parcels that a universal source
        // could otherwise track.
        val enabled = enabled().filter { it.descriptor.implemented }
        parcel.sourceId?.let { pinned -> enabled.firstOrNull { it.descriptor.id == pinned }?.let { return it } }
        return enabled.firstOrNull { it.detectCarrier(parcel.trackingNumber) != null }
    }
```

(Replaces the two-line body that ended with the `firstOrNull { it.descriptor.kind == SourceKind.UNIVERSAL }`
fallback; drop the now-stale "could otherwise track" clause from the comment since there is no
universal fallback left — update the comment to: `// Only resolve among sources that actually
implement live tracking — stub carrier sources (implemented = false) must not intercept
parcels.`)

- [ ] **Step 4: Fix `WebViewBasedSource.kt`**

Delete the `kind = SourceKind.CARRIER,` line from the descriptor and the now-unused
`SourceKind` import:

```kotlin
import com.shiphappens.source.api.FailureReason
import com.shiphappens.source.api.SourceDescriptor
import com.shiphappens.source.api.SourceResult
import com.shiphappens.source.api.TrackingSource
```

```kotlin
    final override val descriptor = SourceDescriptor(
        id = webSpec.sourceId,
        displayName = webSpec.carrier.displayName,
        accentColorHex = webSpec.carrier.accentColorHex,
        implemented = scraper.isAvailable,
    )
```

- [ ] **Step 5: Fix `SettingsViewModel.kt`**

Delete `universal` from `SettingsUiState`:

```kotlin
data class SettingsUiState(
    val carriers: List<SourceCardUi> = emptyList(),
    val autoImport: Boolean = true,
    val frequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN,
    val toast: String? = null,
)
```

Rewrite the `state` combine block (drop the `SourceKind.UNIVERSAL` status branch and the
universal/carriers split — `carriers` is now every card):

```kotlin
    val state: StateFlow<SettingsUiState> = combine(settings.settings, toast) { s, t ->
        val cards = registry.all().map { src ->
            val d = src.descriptor
            val cfg = s.sourceConfigs[d.id] ?: SourceConfig()
            val (statusText, statusColor) = when {
                !cfg.enabled -> "Not connected" to "#A8A296"
                !d.implemented -> "Coming soon" to "#A8A296"
                else -> "Connected · syncing" to "#1F7A4D"
            }
            val webSpec = (src as? WebCapableSource)?.webSpec
            SourceCardUi(
                id = d.id, name = d.displayName,
                accentHex = d.accentColorHex ?: Carrier(d.id, d.displayName).accentHex(),
                enabled = cfg.enabled, statusText = statusText, statusColorHex = statusColor,
                webCapable = webSpec != null,
                signedIn = webSpec != null && cfg.values["loggedIn"] == "true",
            )
        }
        SettingsUiState(
            carriers = cards,
            autoImport = s.autoClipboardImport,
            frequency = s.refreshFrequency,
            toast = t,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())
```

`SourceKind` is now unused in this file — delete it from the `import com.shiphappens.source.api.*`
if it's an explicit import, or leave the wildcard import as-is if that's how the file imports
`source.api` (check the current import block; it uses `import com.shiphappens.source.api.*`, a
wildcard, so no import line needs to change).

- [ ] **Step 6: Fix `SettingsScreen.kt`**

Delete the "Universal API" section from `SettingsContent`:

```kotlin
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 34.dp)) {
                SectionLabel("Direct carrier APIs")
                state.carriers.forEach { SourceCard(it, onToggle, onSignIn, onSignOut) }
                Spacer(Modifier.height(10.dp))
                SectionLabel("Sync")
                SyncCard(state.autoImport, state.frequency, onAutoImport, onFrequency)
                Text(
                    "Keys are stored on this device only and used to fetch live tracking status directly from each carrier.",
                    color = ShipColors.faint, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 16.dp),
                )
            }
```

Update both previews: drop the `universal = listOf(...)` blocks entirely, and — per the design's
explicit instruction — turn the second preview's FedEx example card into an Amazon example:

```kotlin
@Preview
@Composable
private fun Preview_SettingsContent_SourcesDisabled() {
    ShipTheme {
        SettingsContent(
            SettingsUiState(
                carriers = listOf(
                    SourceCardUi(
                        id = "ups", name = "UPS", accentHex = "#5A3A22", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                    ),
                    SourceCardUi(
                        id = "usps", name = "USPS", accentHex = "#1E3A8F", enabled = false,
                        statusText = "Not connected", statusColorHex = "#A8A296",
                    ),
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun Preview_SettingsContent_SourcesEnabled() {
    ShipTheme {
        SettingsContent(
            SettingsUiState(
                carriers = listOf(
                    SourceCardUi(
                        id = "ups", name = "UPS", accentHex = "#5A3A22", enabled = true,
                        statusText = "Connected · syncing", statusColorHex = "#1F7A4D",
                    ),
                    SourceCardUi(
                        id = "amazon", name = "Amazon", accentHex = "#995C00", enabled = true,
                        statusText = "Coming soon", statusColorHex = "#A8A296",
                    ),
                ),
                autoImport = true,
                frequency = RefreshFrequency.ONE_HOUR,
            ),
        )
    }
}
```

- [ ] **Step 7: Fix `FakeSource.kt` (data jvmTest helper)**

Drop the `kind` parameter and its use:

```kotlin
class FakeSource(
    id: String,
    private val detects: Carrier? = null,
    var trackResult: SourceResult<TrackingSnapshot> = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)),
    implemented: Boolean = true,
) : TrackingSource {
    override val descriptor = SourceDescriptor(id, id.uppercase(), implemented = implemented)
    val trackedNumbers = mutableListOf<String>()
    override fun detectCarrier(trackingNumber: String): Carrier? = detects
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        trackedNumbers += trackingNumber
        return trackResult
    }
}
```

- [ ] **Step 8: Fix `SourceRegistryTest.kt`**

Delete `falls_back_to_detecting_source_then_universal` and
`unimplemented_carrier_source_is_skipped_for_universal_fallback` entirely (the universal fallback
they test no longer exists):

```kotlin
    @Test fun falls_back_to_detecting_source_then_universal() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val s = settings(scope)
        val carrier = FakeSource("carrier-src", detects = WellKnownCarriers.UPS)
        val universal = FakeSource("universal-src", kind = SourceKind.UNIVERSAL)
        s.setSourceConfig("universal-src", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(carrier, universal), s)
        // carrier-src disabled -> universal catches it
        assertEquals("universal-src", reg.sourceFor(parcel())!!.descriptor.id)
        s.setSourceConfig("carrier-src", SourceConfig(enabled = true))
        assertEquals("carrier-src", reg.sourceFor(parcel())!!.descriptor.id)
        scope.cancel()
    }

    @Test fun unimplemented_carrier_source_is_skipped_for_universal_fallback() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val s = settings(scope)
        val stubCarrier = FakeSource("stub-carrier", detects = WellKnownCarriers.UPS, implemented = false)
        val universal = FakeSource("universal-src", kind = SourceKind.UNIVERSAL)
        s.setSourceConfig("stub-carrier", SourceConfig(enabled = true))
        s.setSourceConfig("universal-src", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(stubCarrier, universal), s)
        // stub-carrier would normally win by detectCarrier match, but it's not implemented,
        // so the universal source must win instead.
        assertEquals("universal-src", reg.sourceFor(parcel())!!.descriptor.id)
        scope.cancel()
    }
```

Fix `detectCarrier_uses_builtins_before_sources` (drop the `kind = SourceKind.UNIVERSAL` arg):

```kotlin
    @Test fun detectCarrier_uses_builtins_before_sources() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val s = settings(scope)
        val dhl = Carrier("dhl", "DHL")
        val src = FakeSource("u", detects = dhl)
        s.setSourceConfig("u", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(src), s)
        assertEquals(WellKnownCarriers.UPS, reg.detectCarrier("1Z999AA10123456784"))
        assertEquals(dhl, reg.detectCarrier("XX99887766554433"))
        scope.cancel()
    }
```

The remaining two tests (`pinned_source_wins_when_enabled`, `no_enabled_source_returns_null`)
already don't pass `kind` — no change needed. If `SourceKind` is now unused in this file's
imports (it uses `import com.shiphappens.source.api.*`, a wildcard — no line to remove).

- [ ] **Step 9: Fix `ParcelRepositoryTest.kt`**

Delete the `SeedingFake` class and the test that exercises it:

```kotlin
class SeedingFake : TrackingSource, SeedingSource {
    override val descriptor = SourceDescriptor("seeder", "Seeder", SourceKind.UNIVERSAL)
    override fun detectCarrier(trackingNumber: String): Carrier? = null
    override suspend fun track(trackingNumber: String, carrier: Carrier?) =
        SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT))
    override fun seeds() = listOf(SeedParcel("Baseball cap", "1ZW463200377332024", WellKnownCarriers.USPS))
}
```

```kotlin
    @Test fun refreshAll_skips_delivered_and_seeds_seeding_sources() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val seeder = SeedingFake()
        val r = repo(scope, seeder)
        settings.setSourceConfig("seeder", SourceConfig(enabled = true))
        r.refreshAll(force = true)
        val parcels = r.observeParcels(archived = false).first()
        assertEquals(listOf("Baseball cap"), parcels.map { it.name })
        r.refreshAll(force = true)  // seeding is idempotent (dedupe)
        assertEquals(1, r.observeParcels(archived = false).first().size)
    }
```

Fix `ThrowingSource`'s descriptor (drop the `SourceKind.UNIVERSAL` arg):

```kotlin
/** Always throws instead of returning a Failure — exercises the runCatching guard in refreshRow. */
class ThrowingSource : TrackingSource {
    override val descriptor = SourceDescriptor("boom", "Boom")
    override fun detectCarrier(trackingNumber: String): Carrier? = null
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> =
        throw IllegalStateException("source exploded")
}
```

Fix the four remaining `FakeSource("u", kind = SourceKind.UNIVERSAL, ...)` construction sites
(drop the `kind = SourceKind.UNIVERSAL,` argument from each, keeping the rest of each call
unchanged) in: `add_refreshes_immediately_when_source_available`,
`refresh_failure_keeps_existing_data`, `refreshAll_honors_staleness_and_force`,
`refreshAll_does_not_count_no_source_parcels_as_failures`. For example:

```kotlin
        val src = FakeSource("u",
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 15))))
```

and the three simpler ones each become:

```kotlin
        val src = FakeSource("u")
```

- [ ] **Step 10: Fix `ListViewModelTest.kt`**

Drop the `SourceKind.UNIVERSAL` arg from the local `FakeSource`'s descriptor:

```kotlin
    override val descriptor = SourceDescriptor("fake", "Fake")
```

- [ ] **Step 11: Fix the three source test files' `SourceKind` assertions**

In `UpsSourceTest.kt`, `UspsSourceTest.kt`, and `AmazonSourceTest.kt`, delete the
`assertEquals(SourceKind.CARRIER, src.descriptor.kind)` line from each descriptor test (added
back in Task 3 — now removed since `kind` no longer exists on `SourceDescriptor`). Rename each
test from Task 3's kind-focused name to `descriptor_id_and_implemented_flag`, since that's all it
asserts now:

`UpsSourceTest.kt`:
```kotlin
    @Test fun descriptor_id_and_implemented_flag() {
        assertEquals("ups", src.descriptor.id)
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }
```

`UspsSourceTest.kt`:
```kotlin
    @Test fun descriptor_id_and_implemented_flag() {
        assertEquals("usps", src.descriptor.id)
        assertFalse(src.descriptor.implemented)           // NoWebScraper => not implemented
    }
```

`AmazonSourceTest.kt`:
```kotlin
    @Test fun descriptor_id_and_implemented_flag() {
        assertEquals("amazon", src.descriptor.id)
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }
```

If `AmazonSourceTest.kt` has an explicit `import com.shiphappens.source.api.SourceKind` line
(added in Task 3's step, or pre-existing), delete it now — `SourceKind` no longer exists.

- [ ] **Step 12: Run the full verification sweep**

Run:
```bash
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest :source:webview:jvmTest :source:ups:jvmTest :source:usps:jvmTest :source:amazon:jvmTest :ui:testAndroidHostTest 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 13: Commit**

```bash
git add source/api source/webview source/ups source/usps source/amazon data/src ui/src/commonMain/kotlin/com/shiphappens/ui/settings ui/src/androidHostTest/kotlin/com/shiphappens/ui/list/ListViewModelTest.kt
git commit -m "[source] Remove seeding hooks and SourceKind/UNIVERSAL fallback"
```

---

### Task 5: Rework `SettingsViewModelTest` onto the real sources

**Files:**
- Modify: `ui/src/androidHostTest/kotlin/com/shiphappens/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `SettingsViewModel`/`SettingsUiState` final shape from Task 4 (single `carriers` list,
  no `universal`/`fields`/`endpointText`); `UpsWebSource`, `UspsWebSource`, `AmazonWebSource` (all
  constructed with `NoWebScraper`).
- Produces: a rewritten test file with no remaining reference to `demo`/`fedex`/`extraCarriers`.

- [ ] **Step 1: Write the complete rewritten test file**

Replace the entire content of `SettingsViewModelTest.kt`:

```kotlin
package com.shiphappens.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.*
import com.shiphappens.data.db.ShipHappensDb
import com.shiphappens.data.settings.RefreshFrequency
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.source.amazon.AmazonWebSource
import com.shiphappens.source.ups.UpsWebSource
import com.shiphappens.source.usps.UspsWebSource
import com.shiphappens.source.webview.NoOpCookieJar
import com.shiphappens.source.webview.NoWebScraper
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.test.*

/**
 * Synchronization note: mirrors ListViewModelTest — Room 3's Flow-returning DAO methods deliver
 * emissions from the database's own real threads, invisible to the virtual-time scheduler, so
 * `advanceUntilIdle(); vm.state.value` is racy under androidHostTest. Instead, every state read
 * AWAITS the stable post-condition of the action (real-time timeout via awaitState), and the
 * transient toast (which the ViewModel auto-dismisses on a virtual 2.6s timer) is asserted
 * against the full RECORDED sequence of states (awaitRecorded).
 */
class SettingsViewModelTest {
    private class FixedClock : AppClock {
        override fun now() = Instant.fromEpochMilliseconds(1_752_148_800_000)
        override fun today() = LocalDate(2026, 7, 10)
    }

    private lateinit var db: ShipHappensDb
    private lateinit var settings: SettingsRepository
    private lateinit var repo: ParcelRepository
    private lateinit var vm: SettingsViewModel

    /** Every state the ViewModel ever emitted, in order; replay lets awaiters see past states. */
    private val recordedStates = MutableSharedFlow<SettingsUiState>(replay = Int.MAX_VALUE)

    /**
     * Awaits the first current-or-future state matching [predicate] — for STABLE post-conditions
     * (card lists, status text, sync settings). Runs on Dispatchers.Default so the timeout is
     * real time: a virtual-time timeout would auto-fire the moment the test scheduler goes idle
     * while Room's real threads are still working.
     */
    private suspend fun awaitState(timeoutMs: Long = 10_000, predicate: (SettingsUiState) -> Boolean): SettingsUiState =
        withContext(Dispatchers.Default) { withTimeout(timeoutMs) { vm.state.first(predicate) } }

    /**
     * Awaits a state matching [predicate] anywhere in the recorded sequence, past or future —
     * for the TRANSIENT toast, which may already have auto-dismissed (virtual delay) by the time
     * a stable await returns. Predicates must be unique to the step under test.
     */
    private suspend fun awaitRecorded(timeoutMs: Long = 10_000, predicate: (SettingsUiState) -> Boolean): SettingsUiState =
        withContext(Dispatchers.Default) { withTimeout(timeoutMs) { recordedStates.first(predicate) } }

    private suspend fun TestScope.vm(): SettingsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = kotlin.io.path.createTempDirectory("settingsvm").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        // The three real web-scraping sources over NoWebScraper: empty configSpec doesn't exist
        // any more (Task 3), and none are `implemented` without a real scraper, so every card
        // shows "Coming soon" once enabled — exactly what production shows on iOS/JVM.
        val registry = SourceRegistry(
            listOf(UpsWebSource(NoWebScraper), UspsWebSource(NoWebScraper), AmazonWebSource(NoWebScraper)),
            settings,
        )
        repo = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        vm = SettingsViewModel(registry, settings, repo, NoOpCookieJar)
        // Records every emission and keeps WhileSubscribed alive for the whole test.
        backgroundScope.launch { vm.state.collect { check(recordedStates.tryEmit(it)) } }
        // Prime the pipeline: the first combined emission requires settings.settings' initial load.
        awaitState { it.carriers.isNotEmpty() }
        return vm
    }

    @AfterTest fun tearDown() {
        // `state` combines settings.settings (real DataStore dispatcher) with other sources via
        // WhileSubscribed(5_000) on viewModelScope, same shape as WebDetailViewModelTest's
        // documented flake: since ViewModel.clear() is never invoked here, that scope would
        // otherwise leak past this test, and a late real-thread emission resuming on it after
        // resetMain() crashes with "platform dispatcher absent", misattributed to whichever test
        // runs next. Cancelling viewModelScope alone isn't enough — Job.cancel() doesn't wait
        // for an already in-flight blocking Room query (repo also observes Room) to finish, so
        // it can still resume after resetMain(). Closing the (never-otherwise-closed) Room db
        // shuts down its invalidation-tracker threads at the source, which is what actually
        // stops the race deterministically; cancelling the scope first avoids any in-flight
        // collector seeing a "database closed" failure as a surprise.
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    @Test fun sources_default_disabled() = runTest {
        val vm = vm()
        val s = awaitState { it.carriers.size == 3 }
        assertEquals(listOf("ups", "usps", "amazon"), s.carriers.map { it.id })
        assertTrue(s.carriers.all { it.statusText == "Not connected" })
    }

    @Test fun toggling_an_unimplemented_source_shows_coming_soon() = runTest {
        val vm = vm()
        vm.onToggle("ups")
        val s = awaitState { it.carriers.firstOrNull { c -> c.id == "ups" }?.enabled == true }
        // NoWebScraper => WebViewBasedSource.descriptor.implemented == false for every web source.
        assertEquals("Coming soon", s.carriers.first { it.id == "ups" }.statusText)
    }

    @Test fun sync_settings_roundtrip() = runTest {
        val vm = vm()
        vm.onAutoImport(false)
        vm.onFrequency(RefreshFrequency.ONE_HOUR)
        val s = awaitState { !it.autoImport && it.frequency == RefreshFrequency.ONE_HOUR }
        assertFalse(s.autoImport)
        assertEquals(RefreshFrequency.ONE_HOUR, s.frequency)
    }

    @Test fun ups_card_is_web_capable_and_sign_out_clears_flag() = runTest {
        val vm = vm()
        settings.updateSourceConfig("ups") { it.copy(enabled = true, values = mapOf("loggedIn" to "true")) }
        val signedIn = awaitState { s -> s.carriers.firstOrNull { it.id == "ups" }?.signedIn == true }
        assertTrue(signedIn.carriers.first { it.id == "ups" }.webCapable)
        val job = vm.onSignOut("ups")
        awaitState { s -> s.carriers.firstOrNull { it.id == "ups" }?.signedIn == false }
        // Join the write coroutine before the test can return — otherwise it may still be
        // resuming onto Dispatchers.Main after tearDown's resetMain(), crashing a later test
        // (same hazard WebDetailViewModelTest documents for onPayload).
        withContext(Dispatchers.Default) { withTimeout(10_000) { assertNotNull(job).join() } }
    }
}
```

(`vm` param unused warning check: `recordedStates`/`toast` machinery is used by `awaitRecorded`,
which is now unused since no test asserts a toast — Kotlin does not error on an unused private
function, so leaving it is fine and it documents the pattern for future tests; if a stricter lint
flags it, delete `awaitRecorded` and the `recordedStates` collection line — but do not do so
speculatively, only if the build actually reports it.)

- [ ] **Step 2: Run tests to verify green**

Run: `./gradlew :ui:testAndroidHostTest 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL, 4 tests in `SettingsViewModelTest`.

- [ ] **Step 3: Commit**

```bash
git add ui/src/androidHostTest/kotlin/com/shiphappens/ui/settings/SettingsViewModelTest.kt
git commit -m "[ui] Rework SettingsViewModelTest onto the real UPS/USPS/Amazon sources"
```

---

### Task 6: Full verification sweep

**Files:** none (verification only).

- [ ] **Step 1: Run the full cross-module sweep**

Run:
```bash
./gradlew :domain:jvmTest :data:jvmTest :source:api:jvmTest :source:webview:jvmTest :source:ups:jvmTest :source:usps:jvmTest :source:amazon:jvmTest :ui:testAndroidHostTest :source:webview:compileAndroidMain :ui:compileKotlinIosSimulatorArm64 --rerun-tasks 2>&1 | tail -10
```
Expected: BUILD SUCCESSFUL — every module's tests pass fresh (not cached), Android and iOS both
compile.

- [ ] **Step 2: Spot-check the untouched FedEx carrier identity**

Confirm (no code changes expected — this is a manual grep check, not an edit):

```bash
grep -n "FEDEX" domain/src/commonMain/kotlin/com/shiphappens/domain/Carrier.kt
grep -n "FEDEX" data/src/commonMain/kotlin/com/shiphappens/data/source/BuiltInCarrierDetection.kt
grep -n "fedex" ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListScreen.kt
```
Expected: `WellKnownCarriers.FEDEX` still defined and in `.all`; `BuiltInCarrierDetection` still
detects FedEx shapes; `ListScreen.kt`'s manual-add carrier options and preview cards still show
"FedEx" — none of these were touched by Tasks 1–5, so this step should find them unchanged.

- [ ] **Step 3: Confirm no leftover references**

```bash
grep -rln "source.demo\|source.fedex\|DemoSource\|FedexSource\|SeedingSource\|SeedParcel\|SourceConfigProvider\|ConfigField\|SourceKind" --include="*.kt" --include="*.kts" . | grep -v "/build/"
```
Expected: no output.

- [ ] **Step 4: Commit (only if Step 2/3 required any fix; otherwise nothing to commit)**

If everything is clean, this task produces no diff — the prior five commits are the deliverable.
If a stray reference or a `gradle-daemon-jvm.properties` file turns up, fix/exclude it and commit
with `[source] Clean up leftover demo/FedEx references`.

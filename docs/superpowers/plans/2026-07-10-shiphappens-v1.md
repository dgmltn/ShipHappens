# Ship Happens v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Ship Happens parcel-tracking app (Android + iOS) per the approved spec at `docs/superpowers/specs/2026-07-10-shiphappens-design.md`, with pluggable tracking-source modules.

**Architecture:** Kotlin Multiplatform with Compose Multiplatform UI. Google layered architecture: `:ui` (Compose + ViewModels, UDF) → `:core:data` (Room 3 + DataStore repository as single source of truth) → `:source:api` (pluggable `TrackingSource` contract) → `:core:model` (pure domain). Sources (`trackingmore` real; `demo`, `ups`, `usps`, `fedex` stubs) plug in via Koin modules only.

**Tech Stack:** Kotlin 2.4.x, Compose Multiplatform 1.11.x, Navigation 3, Ktor 3.5.x client, Koin 4.2.x, Room 3.0.x (`androidx.room3`), DataStore Preferences, kotlinx-{coroutines, serialization, datetime}, XcodeGen for the iOS shell.

## Global Constraints

- Read the spec first: `docs/superpowers/specs/2026-07-10-shiphappens-design.md`.
- Package root: `com.shiphappens`. Android `applicationId`: `com.shiphappens`. App display name: **Ship Happens**.
- Version floors (pin latest stable ≥ floor in Task 1; never lower): Kotlin 2.4.0, Compose Multiplatform 1.11.0, Ktor 3.5.0, Koin 4.2.1, Room 3.0.0 (`androidx.room3`), Navigation 3 (JetBrains KMP artifacts).
- Android minSdk 26; compile/target latest stable SDK. iOS targets: `iosArm64`, `iosSimulatorArm64`.
- Dependency rule: `ui → core:data → source:api → core:model`. Source impl modules depend on `:source:api` (+ Ktor if needed) and are referenced ONLY in DI assembly (`ui/di/AppModules.kt`) and app entries — never by other modules.
- Every KMP module adds targets `androidTarget()`, `jvm()`, `iosArm64()`, `iosSimulatorArm64()` (jvm exists so tests run fast on JVM). `:ui` is the exception: no `jvm()` (Compose UI tests run via Android unit tests).
- All timestamps `kotlin.time.Instant`; dates `kotlinx.datetime.LocalDate`; times `kotlinx.datetime.LocalTime`. Every module's build file opts in: `sourceSets.all { languageSettings.optIn("kotlin.time.ExperimentalTime") }` (harmless warning if already stable).
- Carrier brand colors (exact): USPS `#1E3A8F`, UPS `#5A3A22`, FedEx `#5A1B9A`. TrackingMore teal `#0F766E`. Palette: bg `#F7F6F3`, ink `#17150F`, muted `#8A857C`, faint `#A8A296`, hairline `#ECEAE3`, urgent `#C2410C`, delivered `#1F7A4D` on `#E7F3EC`.
- API keys stored UNENCRYPTED in DataStore (explicit spec decision — do not "improve" this).
- Refresh is foreground-only (no WorkManager/BGTaskScheduler). Out of scope (do not add): real maps, photos, notifications, dark theme, UPS/USPS/FedEx real HTTP.
- Commits: subject starts with a one-word bracketed tag, e.g. `[gradle]`, `[model]`, `[data]`, `[source]`, `[ui]`, `[android]`, `[iOS]`. End commit messages with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- TDD for all logic (model, data, sources, ViewModels). Pure UI composables and build scaffolding are verified by compilation + manual run in Tasks 18–20.
- If a pinned artifact or API named in this plan does not exist at execution time (e.g. Room 3 artifact names differ), STOP and report — do not improvise coordinates.

---

### Task 1: Gradle scaffold, version catalog, wrapper

**Files:**
- Create: `.gitignore`, `gradle.properties`, `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `scripts/latest-versions.sh`
- Create (generated): `gradlew`, `gradlew.bat`, `gradle/wrapper/*`

**Interfaces:**
- Produces: the version catalog aliases used by every later task (`libs.plugins.kotlinMultiplatform`, `libs.plugins.androidLibrary`, `libs.plugins.composeMultiplatform`, `libs.plugins.composeCompiler`, `libs.plugins.kotlinSerialization`, `libs.plugins.ksp`, `libs.kotlinx.*`, `libs.ktor.*`, `libs.koin.*`, `libs.room3.*`, `libs.sqlite.bundled`, `libs.datastore.preferences.core`, `libs.lifecycle.*`, `libs.navigation3.*`, `libs.kotlin.test`) and module paths registered in `settings.gradle.kts`.

- [ ] **Step 1: Write and run the version-discovery script**

```bash
mkdir -p scripts
cat > scripts/latest-versions.sh <<'SH'
#!/usr/bin/env bash
set -euo pipefail
mc() { curl -sf "https://repo.maven.apache.org/maven2/$1/maven-metadata.xml" | sed -n 's/.*<release>\(.*\)<\/release>.*/\1/p'; }
gm() { curl -sf "https://dl.google.com/android/maven2/$1/group-index.xml"; }
echo "kotlin                 $(mc org/jetbrains/kotlin/kotlin-stdlib)"
echo "compose-multiplatform  $(mc org/jetbrains/compose/compose-gradle-plugin)"
echo "ktor                   $(mc io/ktor/ktor-client-core)"
echo "koin-bom               $(mc io/insert-koin/koin-bom)"
echo "coroutines             $(mc org/jetbrains/kotlinx/kotlinx-coroutines-core)"
echo "serialization          $(mc org/jetbrains/kotlinx/kotlinx-serialization-json)"
echo "datetime               $(mc org/jetbrains/kotlinx/kotlinx-datetime)"
echo "ksp                    $(mc com/google/devtools/ksp/symbol-processing-api)"
echo "lifecycle-jb           $(mc org/jetbrains/androidx/lifecycle/lifecycle-viewmodel)"
echo "navigation3-jb         $(mc org/jetbrains/androidx/navigation3/navigation3-runtime || echo 'NOT FOUND - STOP AND REPORT')"
echo "--- google maven: pick highest STABLE (no -alpha/-beta/-rc) from each list ---"
echo "room3:";     gm androidx/room3
echo; echo "sqlite:";    gm androidx/sqlite | grep -o 'sqlite-bundled versions="[^"]*"'
echo; echo "datastore:"; gm androidx/datastore | grep -o 'datastore-preferences-core versions="[^"]*"'
echo; echo "agp:";       gm com/android/tools/build | grep -o '<gradle versions="[^"]*"'
SH
chmod +x scripts/latest-versions.sh && ./scripts/latest-versions.sh
```

Expected: a version per line. Rules for pinning: take the latest STABLE. Kotlin must be 2.4.x; KSP must start with the chosen Kotlin version (e.g. `2.4.0-1.0.34`); Room3 ≥ 3.0.0; navigation3 line must not say NOT FOUND (if it does: STOP, report). Record the numbers — Step 3 writes them into the catalog.

- [ ] **Step 2: Write `.gitignore` and `gradle.properties`**

```gitignore
.gradle/
build/
*/build/
**/build/
local.properties
.kotlin/
.idea/
*.iml
xcuserdata/
app-ios/ShipHappens.xcodeproj/
Pods/
DerivedData/
captures/
.DS_Store
```

```properties
# gradle.properties
org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
kotlin.code.style=official
kotlin.daemon.jvmargs=-Xmx3g
```

- [ ] **Step 3: Write `gradle/libs.versions.toml`**

Replace every version below with the number recorded in Step 1 (values shown are the floors verified 2026-07-10):

```toml
[versions]
kotlin = "2.4.0"
agp = "8.13.0"
composeMultiplatform = "1.11.0"
ksp = "2.4.0-1.0.34"
ktor = "3.5.0"
koinBom = "4.2.1"
room3 = "3.0.0"
sqlite = "2.6.1"
datastore = "1.2.0"
coroutines = "1.10.2"
serialization = "1.9.0"
datetime = "0.7.1"
lifecycle = "2.9.4"
navigation3 = "1.0.0"
minSdk = "26"
compileSdk = "36"
targetSdk = "36"

[libraries]
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "datetime" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-darwin = { module = "io.ktor:ktor-client-darwin", version.ref = "ktor" }
ktor-client-java = { module = "io.ktor:ktor-client-java", version.ref = "ktor" }
ktor-client-mock = { module = "io.ktor:ktor-client-mock", version.ref = "ktor" }
koin-bom = { module = "io.insert-koin:koin-bom", version.ref = "koinBom" }
koin-core = { module = "io.insert-koin:koin-core" }
koin-android = { module = "io.insert-koin:koin-android" }
koin-compose = { module = "io.insert-koin:koin-compose" }
koin-compose-viewmodel = { module = "io.insert-koin:koin-compose-viewmodel" }
room3-runtime = { module = "androidx.room3:room3-runtime", version.ref = "room3" }
room3-compiler = { module = "androidx.room3:room3-compiler", version.ref = "room3" }
sqlite-bundled = { module = "androidx.sqlite:sqlite-bundled", version.ref = "sqlite" }
datastore-preferences-core = { module = "androidx.datastore:datastore-preferences-core", version.ref = "datastore" }
lifecycle-viewmodel = { module = "org.jetbrains.androidx.lifecycle:lifecycle-viewmodel", version.ref = "lifecycle" }
lifecycle-runtime-compose = { module = "org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
navigation3-runtime = { module = "org.jetbrains.androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
navigation3-ui = { module = "org.jetbrains.androidx.navigation3:navigation3-ui", version.ref = "navigation3" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlinAndroid = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidLibrary = { id = "com.android.library", version.ref = "agp" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

Note on `navigation3`: use the release version printed in Step 1 for `org.jetbrains.androidx.navigation3:navigation3-runtime` (the `1.0.0` above is a placeholder floor for the JetBrains KMP artifact, whose versioning may differ — whatever Step 1 printed is correct).

- [ ] **Step 4: Write `settings.gradle.kts` and root `build.gradle.kts`**

```kotlin
// settings.gradle.kts
rootProject.name = "ShipHappens"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google { mavenContent { includeGroupAndSubgroups("androidx"); includeGroupAndSubgroups("com.android"); includeGroupAndSubgroups("com.google") } }
        mavenCentral()
    }
}

include(":core:model")
include(":core:data")
include(":source:api")
include(":source:trackingmore")
include(":source:demo")
include(":source:ups")
include(":source:usps")
include(":source:fedex")
include(":ui")
include(":app-android")
```

```kotlin
// build.gradle.kts (root)
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 5: Generate the Gradle wrapper**

```bash
command -v gradle >/dev/null || brew install gradle
GV=$(curl -s https://services.gradle.org/versions/current | python3 -c 'import json,sys;print(json.load(sys.stdin)["version"])')
gradle wrapper --gradle-version "$GV"
```

- [ ] **Step 6: Verify Gradle evaluates the build**

Run: `./gradlew projects --console=plain`
Expected: BUILD SUCCESSFUL; lists `:core:model`, `:core:data`, `:source:api`, `:source:trackingmore`, `:source:demo`, `:source:ups`, `:source:usps`, `:source:fedex`, `:ui`, `:app-android` (module dirs may not exist yet — Gradle treats them as empty projects).

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "[gradle] Scaffold build: version catalog, settings, wrapper

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `:core:model` — domain models

**Files:**
- Create: `core/model/build.gradle.kts`
- Create: `core/model/src/commonMain/kotlin/com/shiphappens/core/model/Carrier.kt`
- Create: `core/model/src/commonMain/kotlin/com/shiphappens/core/model/Tracking.kt`
- Create: `core/model/src/commonMain/kotlin/com/shiphappens/core/model/Parcel.kt`
- Test: `core/model/src/commonTest/kotlin/com/shiphappens/core/model/ModelTest.kt`

**Interfaces:**
- Produces (used by every later task):
  - `data class Carrier(val code: String, val displayName: String, val accentColorHex: String? = null)`
  - `object WellKnownCarriers { val UPS: Carrier; val USPS: Carrier; val FEDEX: Carrier; val all: List<Carrier>; fun byCode(code: String): Carrier? }`
  - `enum class TrackingStatus { LABEL_CREATED, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, UNKNOWN }`
  - `data class TrackingEvent(val timestamp: Instant, val description: String, val location: String? = null, val status: TrackingStatus? = null)`
  - `data class TrackingSnapshot(val status: TrackingStatus, val events: List<TrackingEvent> = emptyList(), val etaDate: LocalDate? = null, val etaTime: LocalTime? = null, val latestLocation: String? = null)`
  - `data class Parcel(id, name, trackingNumber, carrier, sourceId, status, etaDate, etaTime, events, latestLocation, isArchived, createdAt, lastRefreshedAt)` with `val normalizedTracking: String`
  - `fun normalizeTracking(raw: String): String`
  - `fun fallbackAccentColor(code: String): String`
  - `val TrackingStatus.stepIndex: Int` (LABEL_CREATED=0 … DELIVERED=4; EXCEPTION/UNKNOWN=-1)

- [ ] **Step 1: Write `core/model/build.gradle.kts`** (this exact shape is the template for all KMP library modules)

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

kotlin {
    androidTarget()
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.shiphappens.core.model"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.shiphappens.core.model

import kotlin.test.*

class ModelTest {
    @Test fun normalize_strips_whitespace_and_dashes_and_uppercases() {
        assertEquals("1Z999AA10123456784", normalizeTracking("1z 999-aa1 01 2345 6784"))
    }

    @Test fun wellKnown_carriers_have_brand_colors() {
        assertEquals("#1E3A8F", WellKnownCarriers.USPS.accentColorHex)
        assertEquals("#5A3A22", WellKnownCarriers.UPS.accentColorHex)
        assertEquals("#5A1B9A", WellKnownCarriers.FEDEX.accentColorHex)
        assertEquals(WellKnownCarriers.FEDEX, WellKnownCarriers.byCode("FEDEX"))
        assertNull(WellKnownCarriers.byCode("dhl"))
    }

    @Test fun fallback_color_is_deterministic_and_from_palette() {
        assertEquals(fallbackAccentColor("dhl"), fallbackAccentColor("dhl"))
        assertTrue(fallbackAccentColor("dhl").startsWith("#"))
        assertNotEquals(fallbackAccentColor("dhl"), fallbackAccentColor("royal-mail"))
    }

    @Test fun status_step_index() {
        assertEquals(0, TrackingStatus.LABEL_CREATED.stepIndex)
        assertEquals(4, TrackingStatus.DELIVERED.stepIndex)
        assertEquals(-1, TrackingStatus.EXCEPTION.stepIndex)
    }

    @Test fun parcel_exposes_normalized_tracking() {
        val p = Parcel(
            id = "p1", name = "Cap", trackingNumber = "94 001-118", carrier = WellKnownCarriers.USPS,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        )
        assertEquals("94001118", p.normalizedTracking)
    }
}
```

- [ ] **Step 3: Run tests, verify failure**

Run: `./gradlew :core:model:jvmTest --console=plain`
Expected: FAIL — unresolved references (`normalizeTracking`, `WellKnownCarriers`, …).

- [ ] **Step 4: Implement the models**

```kotlin
// Carrier.kt
package com.shiphappens.core.model

data class Carrier(
    val code: String,
    val displayName: String,
    val accentColorHex: String? = null,
)

object WellKnownCarriers {
    val UPS = Carrier("ups", "UPS", "#5A3A22")
    val USPS = Carrier("usps", "USPS", "#1E3A8F")
    val FEDEX = Carrier("fedex", "FedEx", "#5A1B9A")
    val all = listOf(UPS, USPS, FEDEX)
    fun byCode(code: String): Carrier? = all.firstOrNull { it.code == code.trim().lowercase() }
}

private val FALLBACK_PALETTE = listOf(
    "#0F766E", "#B45309", "#4338CA", "#9D174D", "#166534", "#7C2D12", "#1D4ED8", "#6B21A8",
)

/** Deterministic accent for carriers we don't have brand colors for. */
fun fallbackAccentColor(code: String): String {
    val h = code.lowercase().fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return FALLBACK_PALETTE[h % FALLBACK_PALETTE.size]
}
```

```kotlin
// Tracking.kt
package com.shiphappens.core.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

enum class TrackingStatus { LABEL_CREATED, SHIPPED, IN_TRANSIT, OUT_FOR_DELIVERY, DELIVERED, EXCEPTION, UNKNOWN }

/** Position on the 5-step timeline; -1 for statuses that don't advance the timeline. */
val TrackingStatus.stepIndex: Int
    get() = when (this) {
        TrackingStatus.LABEL_CREATED -> 0
        TrackingStatus.SHIPPED -> 1
        TrackingStatus.IN_TRANSIT -> 2
        TrackingStatus.OUT_FOR_DELIVERY -> 3
        TrackingStatus.DELIVERED -> 4
        TrackingStatus.EXCEPTION, TrackingStatus.UNKNOWN -> -1
    }

data class TrackingEvent(
    val timestamp: Instant,
    val description: String,
    val location: String? = null,
    val status: TrackingStatus? = null,
)

data class TrackingSnapshot(
    val status: TrackingStatus,
    val events: List<TrackingEvent> = emptyList(),
    val etaDate: LocalDate? = null,
    val etaTime: LocalTime? = null,
    val latestLocation: String? = null,
)
```

```kotlin
// Parcel.kt
package com.shiphappens.core.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

fun normalizeTracking(raw: String): String =
    raw.filterNot { it.isWhitespace() || it == '-' }.uppercase()

data class Parcel(
    val id: String,
    val name: String,
    val trackingNumber: String,
    val carrier: Carrier,
    val sourceId: String? = null,
    val status: TrackingStatus = TrackingStatus.UNKNOWN,
    val etaDate: LocalDate? = null,
    val etaTime: LocalTime? = null,
    val events: List<TrackingEvent> = emptyList(),
    val latestLocation: String? = null,
    val isArchived: Boolean = false,
    val createdAt: Instant,
    val lastRefreshedAt: Instant? = null,
) {
    val normalizedTracking: String get() = normalizeTracking(trackingNumber)
}
```

- [ ] **Step 5: Run tests, verify pass**

Run: `./gradlew :core:model:jvmTest --console=plain`
Expected: BUILD SUCCESSFUL, 5 tests passed.

- [ ] **Step 6: Commit**

```bash
git add core/model && git commit -m "[model] Add domain models: Parcel, Carrier, TrackingStatus/Event/Snapshot

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `:source:api` — the pluggable source contract

**Files:**
- Create: `source/api/build.gradle.kts`
- Create: `source/api/src/commonMain/kotlin/com/shiphappens/source/api/TrackingSource.kt`
- Create: `source/api/src/commonMain/kotlin/com/shiphappens/source/api/SourceConfig.kt`
- Test: `source/api/src/commonTest/kotlin/com/shiphappens/source/api/SourceConfigTest.kt`

**Interfaces:**
- Consumes: `Carrier`, `TrackingSnapshot`, `SeedParcel`-related model types from Task 2.
- Produces (implemented by every source module; consumed by `:core:data`):
  - `interface TrackingSource { val descriptor: SourceDescriptor; fun detectCarrier(trackingNumber: String): Carrier?; suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot>; suspend fun testConnection(config: SourceConfig): SourceResult<Unit> }`
  - `data class SourceDescriptor(val id: String, val displayName: String, val kind: SourceKind, val accentColorHex: String?, val configSpec: List<ConfigField>)`
  - `enum class SourceKind { UNIVERSAL, CARRIER }`
  - `data class ConfigField(val key: String, val label: String, val placeholder: String, val isSecret: Boolean = false)`
  - `@Serializable data class SourceConfig(val enabled: Boolean = false, val values: Map<String, String> = emptyMap())`
  - `sealed interface SourceResult<out T>` with `data class Success<T>(val value: T)` and `data class Failure(val reason: FailureReason, val message: String? = null)`
  - `enum class FailureReason { AUTH, NETWORK, NOT_FOUND, RATE_LIMITED, UNKNOWN }`
  - `interface SourceConfigProvider { suspend fun current(sourceId: String): SourceConfig }`
  - `data class SeedParcel(val name: String, val trackingNumber: String, val carrier: Carrier)`
  - `interface SeedingSource { fun seeds(): List<SeedParcel> }` (optional capability, checked with `is`)

- [ ] **Step 1: Write `source/api/build.gradle.kts`**

Same shape as Task 2's build file with these changes: add `alias(libs.plugins.kotlinSerialization)` to `plugins`, set `namespace = "com.shiphappens.source.api"`, and `commonMain.dependencies` becomes:

```kotlin
commonMain.dependencies {
    api(projects.core.model)
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.shiphappens.source.api

import kotlinx.serialization.json.Json
import kotlin.test.*

class SourceConfigTest {
    @Test fun sourceConfig_roundtrips_through_json() {
        val cfg = SourceConfig(enabled = true, values = mapOf("apiKey" to "tm-123"))
        val json = Json.encodeToString(SourceConfig.serializer(), cfg)
        assertEquals(cfg, Json.decodeFromString(SourceConfig.serializer(), json))
    }

    @Test fun failure_carries_reason() {
        val f: SourceResult<Unit> = SourceResult.Failure(FailureReason.AUTH, "bad key")
        assertTrue(f is SourceResult.Failure && f.reason == FailureReason.AUTH)
    }
}
```

- [ ] **Step 3: Run, verify failure** — `./gradlew :source:api:jvmTest --console=plain` → FAIL (unresolved references).

- [ ] **Step 4: Implement the contract**

```kotlin
// SourceConfig.kt
package com.shiphappens.source.api

import kotlinx.serialization.Serializable

@Serializable
data class SourceConfig(
    val enabled: Boolean = false,
    val values: Map<String, String> = emptyMap(),
) {
    operator fun get(key: String): String? = values[key]?.takeIf { it.isNotBlank() }
}

/** How core hands a source its user-entered settings at call time. */
interface SourceConfigProvider {
    suspend fun current(sourceId: String): SourceConfig
}
```

```kotlin
// TrackingSource.kt
package com.shiphappens.source.api

import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.TrackingSnapshot

enum class SourceKind { UNIVERSAL, CARRIER }

data class ConfigField(
    val key: String,
    val label: String,
    val placeholder: String,
    val isSecret: Boolean = false,
)

data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val kind: SourceKind,
    val accentColorHex: String? = null,
    val configSpec: List<ConfigField> = emptyList(),
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
    suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot>
    suspend fun testConnection(config: SourceConfig): SourceResult<Unit>
}

data class SeedParcel(val name: String, val trackingNumber: String, val carrier: Carrier)

/** Optional capability: a source that seeds parcels when enabled (the demo source). */
interface SeedingSource {
    fun seeds(): List<SeedParcel>
}
```

- [ ] **Step 5: Run, verify pass** — `./gradlew :source:api:jvmTest --console=plain` → 2 tests pass.

- [ ] **Step 6: Commit**

```bash
git add source/api && git commit -m "[source] Add TrackingSource contract, SourceConfig, SourceResult

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: `:core:data` module + settings (DataStore)

**Files:**
- Create: `core/data/build.gradle.kts`
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/settings/SettingsRepository.kt`
- Test: `core/data/src/jvmTest/kotlin/com/shiphappens/core/data/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `SourceConfig`, `SourceConfigProvider` (Task 3).
- Produces:
  - `enum class RefreshFrequency(val staleAfterMinutes: Long?) { FIFTEEN_MIN(15), ONE_HOUR(60), MANUAL(null) }`
  - `data class AppSettings(val sourceConfigs: Map<String, SourceConfig> = emptyMap(), val autoClipboardImport: Boolean = true, val refreshFrequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN)`
  - `class SettingsRepository(dataStore: DataStore<Preferences>) : SourceConfigProvider` with `val settings: Flow<AppSettings>`, `suspend fun setSourceConfig(sourceId: String, config: SourceConfig)`, `suspend fun setAutoClipboardImport(enabled: Boolean)`, `suspend fun setRefreshFrequency(freq: RefreshFrequency)`, `override suspend fun current(sourceId: String): SourceConfig`

- [ ] **Step 1: Write `core/data/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
}

kotlin {
    androidTarget()
    jvm()
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(projects.source.api)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.room3.runtime)
            implementation(libs.sqlite.bundled)
            api(libs.datastore.preferences.core)
            api(project.dependencies.platform(libs.koin.bom))
            api(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    add("kspAndroid", libs.room3.compiler)
    add("kspJvm", libs.room3.compiler)
    add("kspIosArm64", libs.room3.compiler)
    add("kspIosSimulatorArm64", libs.room3.compiler)
}

android {
    namespace = "com.shiphappens.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}
```

(If `ksp` complains about a missing `room.schemaLocation`, it is a warning — ignore; schema export is not used in v1.)

- [ ] **Step 2: Write the failing test**

```kotlin
package com.shiphappens.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shiphappens.source.api.SourceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okio.Path.Companion.toPath
import kotlin.test.*

class SettingsRepositoryTest {
    private fun repo(scope: CoroutineScope): SettingsRepository {
        val dir = kotlin.io.path.createTempDirectory("settings").toString()
        val ds = PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/app.preferences_pb".toPath() }
        return SettingsRepository(ds)
    }

    @Test fun defaults_are_spec_defaults() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val s = r.settings.first()
        assertTrue(s.autoClipboardImport)
        assertEquals(RefreshFrequency.FIFTEEN_MIN, s.refreshFrequency)
        assertTrue(s.sourceConfigs.isEmpty())
        scope.cancel()
    }

    @Test fun source_config_roundtrips_and_provider_returns_it() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val cfg = SourceConfig(enabled = true, values = mapOf("apiKey" to "tm-1"))
        r.setSourceConfig("trackingmore", cfg)
        assertEquals(cfg, r.settings.first().sourceConfigs["trackingmore"])
        assertEquals(cfg, r.current("trackingmore"))
        assertEquals(SourceConfig(), r.current("never-set"))
        scope.cancel()
    }

    @Test fun frequency_and_clipboard_persist() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        r.setRefreshFrequency(RefreshFrequency.MANUAL)
        r.setAutoClipboardImport(false)
        val s = r.settings.first()
        assertEquals(RefreshFrequency.MANUAL, s.refreshFrequency)
        assertFalse(s.autoClipboardImport)
        scope.cancel()
    }
}
```

- [ ] **Step 3: Run, verify failure** — `./gradlew :core:data:jvmTest --console=plain` → FAIL (unresolved `SettingsRepository`).

- [ ] **Step 4: Implement `SettingsRepository`**

```kotlin
package com.shiphappens.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.shiphappens.source.api.SourceConfig
import com.shiphappens.source.api.SourceConfigProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class RefreshFrequency(val staleAfterMinutes: Long?) {
    FIFTEEN_MIN(15), ONE_HOUR(60), MANUAL(null)
}

data class AppSettings(
    val sourceConfigs: Map<String, SourceConfig> = emptyMap(),
    val autoClipboardImport: Boolean = true,
    val refreshFrequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN,
)

private val KEY_SOURCE_CONFIGS = stringPreferencesKey("source_configs")
private val KEY_AUTO_CLIPBOARD = booleanPreferencesKey("auto_clipboard_import")
private val KEY_FREQUENCY = stringPreferencesKey("refresh_frequency")
private val configsSerializer = MapSerializer(String.serializer(), SourceConfig.serializer())

class SettingsRepository(private val dataStore: DataStore<Preferences>) : SourceConfigProvider {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            sourceConfigs = prefs[KEY_SOURCE_CONFIGS]
                ?.let { runCatching { json.decodeFromString(configsSerializer, it) }.getOrNull() }
                ?: emptyMap(),
            autoClipboardImport = prefs[KEY_AUTO_CLIPBOARD] ?: true,
            refreshFrequency = prefs[KEY_FREQUENCY]
                ?.let { runCatching { RefreshFrequency.valueOf(it) }.getOrNull() }
                ?: RefreshFrequency.FIFTEEN_MIN,
        )
    }

    suspend fun setSourceConfig(sourceId: String, config: SourceConfig) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SOURCE_CONFIGS]
                ?.let { runCatching { json.decodeFromString(configsSerializer, it) }.getOrNull() }
                ?: emptyMap()
            prefs[KEY_SOURCE_CONFIGS] = json.encodeToString(configsSerializer, current + (sourceId to config))
        }
    }

    suspend fun setAutoClipboardImport(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_CLIPBOARD] = enabled }
    }

    suspend fun setRefreshFrequency(freq: RefreshFrequency) {
        dataStore.edit { it[KEY_FREQUENCY] = freq.name }
    }

    override suspend fun current(sourceId: String): SourceConfig =
        settings.first().sourceConfigs[sourceId] ?: SourceConfig()
}
```

- [ ] **Step 5: Run, verify pass** — `./gradlew :core:data:jvmTest --console=plain` → 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add core/data && git commit -m "[data] Add SettingsRepository on DataStore Preferences

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: `:core:data` — Room 3 database

**Files:**
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/db/Entities.kt`
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/db/ParcelDao.kt`
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/db/ShipHappensDb.kt`
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/db/Mappers.kt`
- Test: `core/data/src/jvmTest/kotlin/com/shiphappens/core/data/db/ParcelDaoTest.kt`

**Interfaces:**
- Consumes: domain models (Task 2).
- Produces (used by `ParcelRepository` in Task 7):
  - `ShipHappensDb : RoomDatabase` with `abstract fun parcelDao(): ParcelDao`
  - `ParcelDao`: `fun observe(archived: Boolean): Flow<List<ParcelWithEvents>>`, `fun observeById(id: String): Flow<ParcelWithEvents?>`, `suspend fun getById(id: String): ParcelWithEvents?`, `suspend fun allActive(): List<ParcelEntity>`, `suspend fun normalizedNumbers(): List<String>`, `suspend fun upsertParcel(p: ParcelEntity)`, `suspend fun replaceEvents(parcelId: String, events: List<TrackingEventEntity>)`, `suspend fun archive(id: String, at: Long)`, `suspend fun restore(id: String)`
  - Mappers: `fun ParcelWithEvents.toDomain(): Parcel`, `fun Parcel.toEntity(archivedAt: Long? = null): ParcelEntity`, `fun TrackingEvent.toEntity(parcelId: String): TrackingEventEntity`

- [ ] **Step 1: Write the failing DAO test**

```kotlin
package com.shiphappens.core.data.db

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.*

class ParcelDaoTest {
    private fun db(): ShipHappensDb =
        Room.inMemoryDatabaseBuilder<ShipHappensDb>()
            .setDriver(BundledSQLiteDriver())
            .build()

    private fun parcel(id: String, archived: Boolean = false) = Parcel(
        id = id, name = "Parcel $id", trackingNumber = "1Z-$id", carrier = WellKnownCarriers.UPS,
        status = TrackingStatus.IN_TRANSIT, isArchived = archived,
        createdAt = Instant.fromEpochMilliseconds(1000),
    )

    @Test fun upsert_and_observe_by_archived_flag() = runTest {
        val dao = db().parcelDao()
        dao.upsertParcel(parcel("a").toEntity())
        dao.upsertParcel(parcel("b", archived = true).toEntity(archivedAt = 5))
        assertEquals(listOf("a"), dao.observe(archived = false).first().map { it.parcel.id })
        assertEquals(listOf("b"), dao.observe(archived = true).first().map { it.parcel.id })
    }

    @Test fun events_roundtrip_and_replace() = runTest {
        val dao = db().parcelDao()
        dao.upsertParcel(parcel("a").toEntity())
        val ev = TrackingEvent(Instant.fromEpochMilliseconds(2000), "Departed facility", "Memphis, TN", TrackingStatus.IN_TRANSIT)
        dao.replaceEvents("a", listOf(ev.toEntity("a")))
        dao.replaceEvents("a", listOf(ev.toEntity("a")))  // replace, not append
        val domain = dao.getById("a")!!.toDomain()
        assertEquals(1, domain.events.size)
        assertEquals("Departed facility", domain.events[0].description)
        assertEquals("Memphis, TN", domain.events[0].location)
    }

    @Test fun archive_and_restore() = runTest {
        val dao = db().parcelDao()
        dao.upsertParcel(parcel("a").toEntity())
        dao.archive("a", at = 99)
        assertTrue(dao.observe(archived = true).first().single().parcel.isArchived)
        dao.restore("a")
        assertFalse(dao.observe(archived = false).first().single().parcel.isArchived)
    }

    @Test fun domain_mapper_roundtrip() = runTest {
        val dao = db().parcelDao()
        val original = parcel("a").copy(
            etaDate = kotlinx.datetime.LocalDate(2026, 7, 15),
            etaTime = kotlinx.datetime.LocalTime(20, 0),
            latestLocation = "Louisville, KY",
            sourceId = "trackingmore",
        )
        dao.upsertParcel(original.toEntity())
        assertEquals(original, dao.getById("a")!!.toDomain())
    }
}
```

- [ ] **Step 2: Run, verify failure** — `./gradlew :core:data:jvmTest --console=plain` → FAIL (unresolved `ShipHappensDb` etc.).

- [ ] **Step 3: Implement entities, DAO, database, mappers**

```kotlin
// Entities.kt
package com.shiphappens.core.data.db

import androidx.room3.*

@Entity(tableName = "parcels")
data class ParcelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val trackingNumber: String,
    val normalizedTracking: String,
    val carrierCode: String,
    val carrierName: String,
    val carrierColor: String?,
    val sourceId: String?,
    val status: String,
    val etaDate: String?,       // ISO-8601 LocalDate
    val etaTime: String?,       // ISO-8601 LocalTime
    val latestLocation: String?,
    val isArchived: Boolean,
    val archivedAt: Long?,      // epoch millis, for archived-tab ordering
    val createdAt: Long,
    val lastRefreshedAt: Long?,
)

@Entity(
    tableName = "tracking_events",
    foreignKeys = [ForeignKey(
        entity = ParcelEntity::class,
        parentColumns = ["id"], childColumns = ["parcelId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("parcelId")],
)
data class TrackingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parcelId: String,
    val timestamp: Long,
    val description: String,
    val location: String?,
    val status: String?,
)

data class ParcelWithEvents(
    @Embedded val parcel: ParcelEntity,
    @Relation(parentColumn = "id", entityColumn = "parcelId")
    val events: List<TrackingEventEntity>,
)
```

```kotlin
// ParcelDao.kt
package com.shiphappens.core.data.db

import androidx.room3.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ParcelDao {
    @Transaction
    @Query("SELECT * FROM parcels WHERE isArchived = :archived")
    fun observe(archived: Boolean): Flow<List<ParcelWithEvents>>

    @Transaction
    @Query("SELECT * FROM parcels WHERE id = :id")
    fun observeById(id: String): Flow<ParcelWithEvents?>

    @Transaction
    @Query("SELECT * FROM parcels WHERE id = :id")
    suspend fun getById(id: String): ParcelWithEvents?

    @Query("SELECT * FROM parcels WHERE isArchived = 0")
    suspend fun allActive(): List<ParcelEntity>

    @Query("SELECT normalizedTracking FROM parcels")
    suspend fun normalizedNumbers(): List<String>

    @Upsert
    suspend fun upsertParcel(p: ParcelEntity)

    @Query("DELETE FROM tracking_events WHERE parcelId = :parcelId")
    suspend fun deleteEvents(parcelId: String)

    @Insert
    suspend fun insertEvents(events: List<TrackingEventEntity>)

    @Transaction
    suspend fun replaceEvents(parcelId: String, events: List<TrackingEventEntity>) {
        deleteEvents(parcelId)
        insertEvents(events)
    }

    @Query("UPDATE parcels SET isArchived = 1, archivedAt = :at WHERE id = :id")
    suspend fun archive(id: String, at: Long)

    @Query("UPDATE parcels SET isArchived = 0, archivedAt = NULL WHERE id = :id")
    suspend fun restore(id: String)
}
```

```kotlin
// ShipHappensDb.kt
package com.shiphappens.core.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

@Database(entities = [ParcelEntity::class, TrackingEventEntity::class], version = 1)
@ConstructedBy(ShipHappensDbConstructor::class)
abstract class ShipHappensDb : RoomDatabase() {
    abstract fun parcelDao(): ParcelDao
}

// Room's KSP processor generates the actual implementations per platform.
@Suppress("KotlinNoActualForExpect", "NO_ACTUAL_FOR_EXPECT")
expect object ShipHappensDbConstructor : RoomDatabaseConstructor<ShipHappensDb> {
    override fun initialize(): ShipHappensDb
}
```

```kotlin
// Mappers.kt
package com.shiphappens.core.data.db

import com.shiphappens.core.model.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

fun Parcel.toEntity(archivedAt: Long? = null) = ParcelEntity(
    id = id, name = name, trackingNumber = trackingNumber, normalizedTracking = normalizedTracking,
    carrierCode = carrier.code, carrierName = carrier.displayName, carrierColor = carrier.accentColorHex,
    sourceId = sourceId, status = status.name,
    etaDate = etaDate?.toString(), etaTime = etaTime?.toString(),
    latestLocation = latestLocation, isArchived = isArchived, archivedAt = archivedAt,
    createdAt = createdAt.toEpochMilliseconds(), lastRefreshedAt = lastRefreshedAt?.toEpochMilliseconds(),
)

fun TrackingEvent.toEntity(parcelId: String) = TrackingEventEntity(
    parcelId = parcelId, timestamp = timestamp.toEpochMilliseconds(),
    description = description, location = location, status = status?.name,
)

fun ParcelWithEvents.toDomain(): Parcel = Parcel(
    id = parcel.id, name = parcel.name, trackingNumber = parcel.trackingNumber,
    carrier = Carrier(parcel.carrierCode, parcel.carrierName, parcel.carrierColor),
    sourceId = parcel.sourceId,
    status = runCatching { TrackingStatus.valueOf(parcel.status) }.getOrDefault(TrackingStatus.UNKNOWN),
    etaDate = parcel.etaDate?.let(LocalDate::parse),
    etaTime = parcel.etaTime?.let(LocalTime::parse),
    events = events.sortedBy { it.timestamp }.map {
        TrackingEvent(
            timestamp = Instant.fromEpochMilliseconds(it.timestamp),
            description = it.description, location = it.location,
            status = it.status?.let { s -> runCatching { TrackingStatus.valueOf(s) }.getOrNull() },
        )
    },
    latestLocation = parcel.latestLocation, isArchived = parcel.isArchived,
    createdAt = Instant.fromEpochMilliseconds(parcel.createdAt),
    lastRefreshedAt = parcel.lastRefreshedAt?.let(Instant::fromEpochMilliseconds),
)
```

- [ ] **Step 4: Run, verify pass** — `./gradlew :core:data:jvmTest --console=plain` → all `:core:data` tests pass (Task 4's 3 + these 4).

- [ ] **Step 5: Commit**

```bash
git add core/data && git commit -m "[data] Add Room 3 database: parcels, tracking events, DAO, mappers

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: `:core:data` — built-in carrier detection + SourceRegistry

**Files:**
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/source/BuiltInCarrierDetection.kt`
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/source/SourceRegistry.kt`
- Test: `core/data/src/commonTest/kotlin/com/shiphappens/core/data/source/BuiltInCarrierDetectionTest.kt`
- Test: `core/data/src/commonTest/kotlin/com/shiphappens/core/data/source/SourceRegistryTest.kt`
- Test helper: `core/data/src/commonTest/kotlin/com/shiphappens/core/data/source/FakeSource.kt`

**Interfaces:**
- Consumes: `TrackingSource`, `SourceDescriptor`, `SourceKind`, `SourceConfig` (Task 3); `SettingsRepository` (Task 4).
- Produces:
  - `object BuiltInCarrierDetection { fun detect(raw: String): Carrier? }` — the design's regexes: UPS `^1Z[0-9A-Z]{10,}$`; USPS `^(94|93|92|95|82)\d{14,24}$` or `^[A-Z]{2}\d{9}US$`; FedEx `^\d{12}$|^\d{15}$|^\d{20,22}$`; input normalized first; strings shorter than 10 chars → null.
  - `class SourceRegistry(private val sources: List<TrackingSource>, private val settingsRepository: SettingsRepository)` with `fun all(): List<TrackingSource>`, `suspend fun enabled(): List<TrackingSource>`, `suspend fun sourceFor(parcel: Parcel): TrackingSource?`, `suspend fun detectCarrier(trackingNumber: String): Carrier?`
  - Resolution order for `sourceFor`: parcel's pinned `sourceId` if that source exists AND is enabled → first enabled source whose `detectCarrier(parcel.trackingNumber) != null` → first enabled UNIVERSAL source → null.
  - `detectCarrier`: `BuiltInCarrierDetection.detect` first, then each enabled source's `detectCarrier`.
- Test helper produced (reused in Task 7's tests — copy it there if module test source sets don't share; both live in `:core:data` commonTest so it IS shared):

```kotlin
// FakeSource.kt
package com.shiphappens.core.data.source

import com.shiphappens.core.model.*
import com.shiphappens.source.api.*

class FakeSource(
    id: String,
    kind: SourceKind = SourceKind.CARRIER,
    private val detects: Carrier? = null,
    var trackResult: SourceResult<TrackingSnapshot> = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)),
) : TrackingSource {
    override val descriptor = SourceDescriptor(id, id.uppercase(), kind)
    val trackedNumbers = mutableListOf<String>()
    override fun detectCarrier(trackingNumber: String): Carrier? = detects
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        trackedNumbers += trackingNumber
        return trackResult
    }
    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> = SourceResult.Success(Unit)
}
```

- [ ] **Step 1: Write the failing detection tests**

```kotlin
package com.shiphappens.core.data.source

import com.shiphappens.core.model.WellKnownCarriers
import kotlin.test.*

class BuiltInCarrierDetectionTest {
    @Test fun detects_ups() {
        assertEquals(WellKnownCarriers.UPS, BuiltInCarrierDetection.detect("1Z 999 AA1 01 2345 6784"))
    }
    @Test fun detects_usps_numeric_and_intl() {
        assertEquals(WellKnownCarriers.USPS, BuiltInCarrierDetection.detect("9400 1118 9922 3300 1122"))
        assertEquals(WellKnownCarriers.USPS, BuiltInCarrierDetection.detect("LK123456789US"))
    }
    @Test fun detects_fedex_12_15_20_22_digits() {
        assertEquals(WellKnownCarriers.FEDEX, BuiltInCarrierDetection.detect("123456789012"))
        assertEquals(WellKnownCarriers.FEDEX, BuiltInCarrierDetection.detect("123456789012345"))
        assertEquals(WellKnownCarriers.FEDEX, BuiltInCarrierDetection.detect("12345678901234567890"))
    }
    @Test fun rejects_short_and_garbage() {
        assertNull(BuiltInCarrierDetection.detect("123"))
        assertNull(BuiltInCarrierDetection.detect("hello world, meeting at 3pm"))
        assertNull(BuiltInCarrierDetection.detect(""))
    }
}
```

- [ ] **Step 2: Write the failing registry tests**

```kotlin
package com.shiphappens.core.data.source

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.test.*

class SourceRegistryTest {
    private fun settings(scope: CoroutineScope): SettingsRepository {
        val dir = kotlin.io.path.createTempDirectory("reg").toString()
        return SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
    }
    private fun parcel(sourceId: String? = null, tracking: String = "1Z999AA10123456784") = Parcel(
        id = "p", name = "P", trackingNumber = tracking, carrier = WellKnownCarriers.UPS,
        sourceId = sourceId, createdAt = Instant.fromEpochMilliseconds(0),
    )

    @Test fun pinned_source_wins_when_enabled() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val s = settings(scope)
        val pinned = FakeSource("pinned")
        val other = FakeSource("other", detects = WellKnownCarriers.UPS)
        s.setSourceConfig("pinned", SourceConfig(enabled = true))
        s.setSourceConfig("other", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(other, pinned), s)
        assertEquals("pinned", reg.sourceFor(parcel(sourceId = "pinned"))!!.descriptor.id)
        scope.cancel()
    }

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

    @Test fun no_enabled_source_returns_null() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val reg = SourceRegistry(listOf(FakeSource("x")), settings(scope))
        assertNull(reg.sourceFor(parcel()))
        scope.cancel()
    }

    @Test fun detectCarrier_uses_builtins_before_sources() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val s = settings(scope)
        val dhl = Carrier("dhl", "DHL")
        val src = FakeSource("u", kind = SourceKind.UNIVERSAL, detects = dhl)
        s.setSourceConfig("u", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(src), s)
        assertEquals(WellKnownCarriers.UPS, reg.detectCarrier("1Z999AA10123456784"))
        assertEquals(dhl, reg.detectCarrier("XX99887766554433"))
        scope.cancel()
    }
}
```

- [ ] **Step 3: Run, verify failure** — `./gradlew :core:data:jvmTest --console=plain` → FAIL.

- [ ] **Step 4: Implement**

```kotlin
// BuiltInCarrierDetection.kt
package com.shiphappens.core.data.source

import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.WellKnownCarriers
import com.shiphappens.core.model.normalizeTracking

object BuiltInCarrierDetection {
    private val UPS = Regex("^1Z[0-9A-Z]{10,}$")
    private val USPS_NUM = Regex("^(94|93|92|95|82)\\d{14,24}$")
    private val USPS_INTL = Regex("^[A-Z]{2}\\d{9}US$")
    private val FEDEX = Regex("^\\d{12}$|^\\d{15}$|^\\d{20,22}$")

    fun detect(raw: String): Carrier? {
        val norm = normalizeTracking(raw.trim())
        if (norm.length < 10) return null
        return when {
            UPS.matches(norm) -> WellKnownCarriers.UPS
            USPS_NUM.matches(norm) || USPS_INTL.matches(norm) -> WellKnownCarriers.USPS
            FEDEX.matches(norm) -> WellKnownCarriers.FEDEX
            else -> null
        }
    }
}
```

```kotlin
// SourceRegistry.kt
package com.shiphappens.core.data.source

import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.Parcel
import com.shiphappens.source.api.SourceKind
import com.shiphappens.source.api.TrackingSource
import kotlinx.coroutines.flow.first

class SourceRegistry(
    private val sources: List<TrackingSource>,
    private val settingsRepository: SettingsRepository,
) {
    fun all(): List<TrackingSource> = sources

    suspend fun enabled(): List<TrackingSource> {
        val configs = settingsRepository.settings.first().sourceConfigs
        return sources.filter { configs[it.descriptor.id]?.enabled == true }
    }

    suspend fun sourceFor(parcel: Parcel): TrackingSource? {
        val enabled = enabled()
        parcel.sourceId?.let { pinned -> enabled.firstOrNull { it.descriptor.id == pinned }?.let { return it } }
        enabled.firstOrNull { it.detectCarrier(parcel.trackingNumber) != null }?.let { return it }
        return enabled.firstOrNull { it.descriptor.kind == SourceKind.UNIVERSAL }
    }

    suspend fun detectCarrier(trackingNumber: String): Carrier? =
        BuiltInCarrierDetection.detect(trackingNumber)
            ?: enabled().firstNotNullOfOrNull { it.detectCarrier(trackingNumber) }
}
```

- [ ] **Step 5: Run, verify pass** — `./gradlew :core:data:jvmTest --console=plain` → all pass.

- [ ] **Step 6: Commit**

```bash
git add core/data && git commit -m "[data] Add built-in carrier detection and SourceRegistry resolution

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: `:core:data` — ParcelRepository + RefreshCoordinator

**Files:**
- Modify: `core/data/build.gradle.kts` (add uuid opt-in)
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/AppClock.kt`
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/ParcelRepository.kt`
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/RefreshCoordinator.kt`
- Test: `core/data/src/jvmTest/kotlin/com/shiphappens/core/data/ParcelRepositoryTest.kt`

**Interfaces:**
- Consumes: `ParcelDao`, `ShipHappensDb`, mappers (Task 5); `SourceRegistry` (Task 6); `SettingsRepository`, `RefreshFrequency` (Task 4); `SeedingSource`, `SourceResult`, `FailureReason` (Task 3); `FakeSource` (Task 6 test helper).
- Produces (consumed by ViewModels):
  - `interface AppClock { fun now(): Instant; fun today(): LocalDate }`, `class SystemClock : AppClock`
  - `sealed interface AddResult { data class Added(val parcel: Parcel) : AddResult; data object Duplicate : AddResult; data object NoCarrier : AddResult }`
  - `data class RefreshSummary(val attempted: Int, val failed: Int, val firstFailureReason: FailureReason? = null)`
  - `class ParcelRepository(dao: ParcelDao, registry: SourceRegistry, settings: SettingsRepository, clock: AppClock)`:
    - `fun observeParcels(archived: Boolean): Flow<List<Parcel>>` — active tab sorted: undelivered first by etaDate (nulls last) then createdAt; delivered last. Archived tab: newest archivedAt first.
    - `fun observeParcel(id: String): Flow<Parcel?>`
    - `suspend fun addParcel(name: String, trackingNumber: String, carrier: Carrier?): AddResult`
    - `suspend fun archive(id: String)` / `suspend fun restore(id: String)`
    - `suspend fun refresh(id: String): Boolean` (false = failed or no source)
    - `suspend fun refreshAll(force: Boolean): RefreshSummary` — seeds enabled `SeedingSource`s first; skips DELIVERED; honors staleness unless `force`
  - `class RefreshCoordinator(repository: ParcelRepository, scope: CoroutineScope)` with `fun onAppForeground()` and `val summaries: SharedFlow<RefreshSummary>`

- [ ] **Step 1: Add uuid opt-in to `core/data/build.gradle.kts`**

In the `sourceSets` block change the `all { … }` line to:

```kotlin
all {
    languageSettings.optIn("kotlin.time.ExperimentalTime")
    languageSettings.optIn("kotlin.uuid.ExperimentalUuidApi")
}
```

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.shiphappens.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.data.db.ShipHappensDb
import com.shiphappens.core.data.settings.RefreshFrequency
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.FakeSource
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.test.*

class FixedClock(var instant: Instant = Instant.fromEpochMilliseconds(1_752_148_800_000), // 2026-07-10T12:00Z
                 var date: LocalDate = LocalDate(2026, 7, 10)) : AppClock {
    override fun now() = instant
    override fun today() = date
}

class SeedingFake : TrackingSource, SeedingSource {
    override val descriptor = SourceDescriptor("seeder", "Seeder", SourceKind.UNIVERSAL)
    override fun detectCarrier(trackingNumber: String): Carrier? = null
    override suspend fun track(trackingNumber: String, carrier: Carrier?) =
        SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT))
    override suspend fun testConnection(config: SourceConfig) = SourceResult.Success(Unit)
    override fun seeds() = listOf(SeedParcel("Baseball cap", "1ZW463200377332024", WellKnownCarriers.USPS))
}

class ParcelRepositoryTest {
    private lateinit var settings: SettingsRepository
    private lateinit var clock: FixedClock

    private fun repo(scope: CoroutineScope, vararg sources: TrackingSource): ParcelRepository {
        val dir = kotlin.io.path.createTempDirectory("repo").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        clock = FixedClock()
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        return ParcelRepository(db.parcelDao(), SourceRegistry(sources.toList(), settings), settings, clock)
    }

    @Test fun add_detects_carrier_and_dedupes() = runTest {
        val r = repo(CoroutineScope(coroutineContext + SupervisorJob()))
        val added = r.addParcel("Keyboard", "1Z 999 AA1 01 2345 6784", carrier = null)
        assertIs<AddResult.Added>(added)
        assertEquals(WellKnownCarriers.UPS, added.parcel.carrier)
        assertIs<AddResult.Duplicate>(r.addParcel("Again", "1z999aa101 2345-6784", null))
        assertIs<AddResult.NoCarrier>(r.addParcel("Mystery", "ZZZZZZZZZZZZ!!", null))
    }

    @Test fun add_refreshes_immediately_when_source_available() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", kind = SourceKind.UNIVERSAL,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 15))))
        val r = repo(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        val p = r.observeParcel(added.parcel.id).first()!!
        assertEquals(TrackingStatus.IN_TRANSIT, p.status)
        assertEquals(LocalDate(2026, 7, 15), p.etaDate)
        assertEquals("u", p.sourceId)
    }

    @Test fun refresh_failure_keeps_existing_data() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", kind = SourceKind.UNIVERSAL)
        val r = repo(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        src.trackResult = SourceResult.Failure(FailureReason.AUTH, "bad key")
        val summary = r.refreshAll(force = true)
        assertEquals(1, summary.failed)
        assertEquals(FailureReason.AUTH, summary.firstFailureReason)
        val p = r.observeParcel(added.parcel.id).first()!!
        assertEquals(TrackingStatus.IN_TRANSIT, p.status)  // from the add-time refresh
    }

    @Test fun refreshAll_honors_staleness_and_force() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", kind = SourceKind.UNIVERSAL)
        val r = repo(scope, src)
        settings.setSourceConfig("u", SourceConfig(enabled = true))
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
        src.trackedNumbers.clear()
        r.refreshAll(force = false)                          // just refreshed -> not stale
        assertTrue(src.trackedNumbers.isEmpty())
        clock.instant += 16.minutes                          // past FIFTEEN_MIN threshold
        r.refreshAll(force = false)
        assertEquals(1, src.trackedNumbers.size)
        settings.setRefreshFrequency(RefreshFrequency.MANUAL)
        clock.instant += 16.minutes
        r.refreshAll(force = false)                          // MANUAL: never automatic
        assertEquals(1, src.trackedNumbers.size)
        r.refreshAll(force = true)                           // force works even on MANUAL
        assertEquals(2, src.trackedNumbers.size)
    }

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

    @Test fun archive_restore_and_sorting() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val a = r.addParcel("A", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        r.addParcel("B", "9400111899223300112", WellKnownCarriers.USPS)
        r.archive(a.parcel.id)
        assertEquals(listOf("B"), r.observeParcels(false).first().map { it.name })
        assertEquals(listOf("A"), r.observeParcels(true).first().map { it.name })
        r.restore(a.parcel.id)
        assertEquals(2, r.observeParcels(false).first().size)
    }
}
```

- [ ] **Step 3: Run, verify failure** — `./gradlew :core:data:jvmTest --console=plain` → FAIL (unresolved `ParcelRepository`, `AppClock`, `AddResult`).

- [ ] **Step 4: Implement**

```kotlin
// AppClock.kt
package com.shiphappens.core.data

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

interface AppClock {
    fun now(): Instant
    fun today(): LocalDate
}

class SystemClock : AppClock {
    override fun now(): Instant = Clock.System.now()
    override fun today(): LocalDate = now().toLocalDateTime(TimeZone.currentSystemDefault()).date
}
```

```kotlin
// ParcelRepository.kt
package com.shiphappens.core.data

import com.shiphappens.core.data.db.*
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

sealed interface AddResult {
    data class Added(val parcel: Parcel) : AddResult
    data object Duplicate : AddResult
    data object NoCarrier : AddResult
}

data class RefreshSummary(val attempted: Int, val failed: Int, val firstFailureReason: FailureReason? = null)

class ParcelRepository(
    private val dao: ParcelDao,
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val clock: AppClock,
) {
    fun observeParcels(archived: Boolean): Flow<List<Parcel>> =
        dao.observe(archived).map { rows ->
            val sorted = if (archived) {
                rows.sortedByDescending { it.parcel.archivedAt ?: 0L }
            } else {
                rows.sortedWith(
                    compareBy<ParcelWithEvents> { it.parcel.status == TrackingStatus.DELIVERED.name }
                        .thenBy { it.parcel.etaDate ?: "9999-12-31" }
                        .thenBy { it.parcel.createdAt }
                )
            }
            sorted.map { it.toDomain() }
        }

    fun observeParcel(id: String): Flow<Parcel?> = dao.observeById(id).map { it?.toDomain() }

    suspend fun addParcel(name: String, trackingNumber: String, carrier: Carrier?): AddResult {
        val trimmed = trackingNumber.trim()
        val norm = normalizeTracking(trimmed)
        if (norm.isEmpty()) return AddResult.NoCarrier
        if (dao.normalizedNumbers().contains(norm)) return AddResult.Duplicate
        val resolved = carrier ?: registry.detectCarrier(trimmed) ?: return AddResult.NoCarrier
        val parcel = Parcel(
            id = Uuid.random().toString(),
            name = name.trim().ifEmpty { "New package" },
            trackingNumber = trimmed,
            carrier = resolved,
            createdAt = clock.now(),
        )
        dao.upsertParcel(parcel.toEntity())
        refresh(parcel.id)  // best effort; failure leaves status UNKNOWN
        return AddResult.Added(parcel)
    }

    suspend fun archive(id: String) = dao.archive(id, clock.now().toEpochMilliseconds())
    suspend fun restore(id: String) = dao.restore(id)

    suspend fun refresh(id: String): Boolean = refreshRow(id) == null

    /** @return null on success, failure reason otherwise (NO source resolves to UNKNOWN). */
    private suspend fun refreshRow(id: String): FailureReason? {
        val row = dao.getById(id) ?: return FailureReason.UNKNOWN
        val parcel = row.toDomain()
        val source = registry.sourceFor(parcel) ?: return FailureReason.UNKNOWN
        return when (val result = source.track(parcel.trackingNumber, parcel.carrier)) {
            is SourceResult.Failure -> result.reason
            is SourceResult.Success -> {
                val snap = result.value
                val updated = row.parcel.copy(
                    status = if (snap.status == TrackingStatus.UNKNOWN) row.parcel.status else snap.status.name,
                    etaDate = snap.etaDate?.toString() ?: row.parcel.etaDate,
                    etaTime = snap.etaTime?.toString() ?: row.parcel.etaTime,
                    latestLocation = snap.latestLocation ?: row.parcel.latestLocation,
                    sourceId = source.descriptor.id,
                    lastRefreshedAt = clock.now().toEpochMilliseconds(),
                )
                dao.upsertParcel(updated)
                if (snap.events.isNotEmpty()) dao.replaceEvents(id, snap.events.map { it.toEntity(id) })
                null
            }
        }
    }

    suspend fun refreshAll(force: Boolean): RefreshSummary {
        seedEnabledSources()
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
            val reason = refreshRow(e.id)
            if (reason != null) { failed++; if (firstReason == null) firstReason = reason }
        }
        return RefreshSummary(candidates.size, failed, firstReason)
    }

    private suspend fun seedEnabledSources() {
        registry.enabled().filterIsInstance<SeedingSource>().forEach { seeder ->
            seeder.seeds().forEach { addParcel(it.name, it.trackingNumber, it.carrier) }
        }
    }
}
```

```kotlin
// RefreshCoordinator.kt
package com.shiphappens.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Foreground-only refresh (spec: no background schedulers in v1). */
class RefreshCoordinator(
    private val repository: ParcelRepository,
    private val scope: CoroutineScope,
) {
    private val _summaries = MutableSharedFlow<RefreshSummary>(extraBufferCapacity = 4)
    val summaries: SharedFlow<RefreshSummary> = _summaries

    fun onAppForeground() {
        scope.launch { _summaries.emit(repository.refreshAll(force = false)) }
    }
}
```

Note on `seedEnabledSources` + `addParcel` recursion: `addParcel` calls `refresh`, not `refreshAll` — no loop.

- [ ] **Step 5: Run, verify pass** — `./gradlew :core:data:jvmTest --console=plain` → all pass.

- [ ] **Step 6: Commit**

```bash
git add core/data && git commit -m "[data] Add ParcelRepository with staleness-aware refresh and seeding

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: `:core:data` — clipboard import + Koin DI modules

**Files:**
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/clipboard/ClipboardImportManager.kt`
- Create: `core/data/src/commonMain/kotlin/com/shiphappens/core/data/di/CoreDataModule.kt`
- Create: `core/data/src/androidMain/kotlin/com/shiphappens/core/data/di/PlatformDataModule.android.kt`
- Create: `core/data/src/iosMain/kotlin/com/shiphappens/core/data/di/PlatformDataModule.ios.kt`
- Create: `core/data/src/jvmMain/kotlin/com/shiphappens/core/data/di/PlatformDataModule.jvm.kt`
- Test: `core/data/src/jvmTest/kotlin/com/shiphappens/core/data/clipboard/ClipboardImportManagerTest.kt`

**Interfaces:**
- Consumes: `SourceRegistry.detectCarrier` (Task 6), `ParcelDao.normalizedNumbers` (Task 5), `SettingsRepository` (Task 4).
- Produces:
  - `interface ClipboardReader { suspend fun readText(): String? }`
  - `data class PendingImport(val carrier: Carrier, val trackingNumber: String)`
  - `class ClipboardImportManager(reader, registry, dao, settings)` with `val pending: StateFlow<PendingImport?>`, `suspend fun checkClipboard()`, `fun dismiss()` (remembers the dismissed number so it doesn't re-surface)
  - `val coreDataModule: org.koin.core.module.Module` and `expect fun platformDataModule(): Module` (provides `DataStore<Preferences>`, `ShipHappensDb`, `ClipboardReader`)

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shiphappens.core.data.clipboard

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.data.db.ShipHappensDb
import com.shiphappens.core.data.db.toEntity
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.core.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.test.*

class FakeClipboard(var text: String?) : ClipboardReader {
    override suspend fun readText(): String? = text
}

class ClipboardImportManagerTest {
    private lateinit var settings: SettingsRepository
    private lateinit var db: ShipHappensDb

    private fun manager(scope: CoroutineScope, clip: FakeClipboard): ClipboardImportManager {
        val dir = kotlin.io.path.createTempDirectory("clip").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        return ClipboardImportManager(clip, SourceRegistry(emptyList(), settings), db.parcelDao(), settings)
    }

    @Test fun detects_tracking_number_on_clipboard() = runTest {
        val m = manager(CoroutineScope(coroutineContext + SupervisorJob()), FakeClipboard("1Z 999 AA1 01 2345 6784"))
        m.checkClipboard()
        assertEquals(WellKnownCarriers.UPS, m.pending.value?.carrier)
    }

    @Test fun ignores_garbage_and_respects_setting() = runTest {
        val clip = FakeClipboard("see you at 5pm!")
        val m = manager(CoroutineScope(coroutineContext + SupervisorJob()), clip)
        m.checkClipboard()
        assertNull(m.pending.value)
        clip.text = "1Z999AA10123456784"
        settings.setAutoClipboardImport(false)
        m.checkClipboard()
        assertNull(m.pending.value)
    }

    @Test fun dedupes_against_existing_parcels() = runTest {
        val m = manager(CoroutineScope(coroutineContext + SupervisorJob()), FakeClipboard("1Z999AA10123456784"))
        db.parcelDao().upsertParcel(
            Parcel(id = "x", name = "Existing", trackingNumber = "1Z 999AA101 23456784",
                   carrier = WellKnownCarriers.UPS, createdAt = Instant.fromEpochMilliseconds(0)).toEntity()
        )
        m.checkClipboard()
        assertNull(m.pending.value)
    }

    @Test fun dismissal_is_remembered() = runTest {
        val m = manager(CoroutineScope(coroutineContext + SupervisorJob()), FakeClipboard("1Z999AA10123456784"))
        m.checkClipboard()
        assertNotNull(m.pending.value)
        m.dismiss()
        m.checkClipboard()
        assertNull(m.pending.value)
    }
}
```

- [ ] **Step 2: Run, verify failure** — `./gradlew :core:data:jvmTest --console=plain` → FAIL.

- [ ] **Step 3: Implement manager + DI**

```kotlin
// ClipboardImportManager.kt
package com.shiphappens.core.data.clipboard

import com.shiphappens.core.data.db.ParcelDao
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.normalizeTracking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

interface ClipboardReader {
    suspend fun readText(): String?
}

data class PendingImport(val carrier: Carrier, val trackingNumber: String)

class ClipboardImportManager(
    private val reader: ClipboardReader,
    private val registry: SourceRegistry,
    private val dao: ParcelDao,
    private val settings: SettingsRepository,
) {
    private val _pending = MutableStateFlow<PendingImport?>(null)
    val pending: StateFlow<PendingImport?> = _pending
    private var dismissedNorm: String? = null

    suspend fun checkClipboard() {
        if (!settings.settings.first().autoClipboardImport) return
        val text = reader.readText()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val carrier = registry.detectCarrier(text) ?: return
        val norm = normalizeTracking(text)
        if (norm == dismissedNorm) return
        if (dao.normalizedNumbers().contains(norm)) return
        _pending.value = PendingImport(carrier, text)
    }

    fun dismiss() {
        dismissedNorm = _pending.value?.let { normalizeTracking(it.trackingNumber) } ?: dismissedNorm
        _pending.value = null
    }
}
```

```kotlin
// CoreDataModule.kt
package com.shiphappens.core.data.di

import com.shiphappens.core.data.*
import com.shiphappens.core.data.clipboard.ClipboardImportManager
import com.shiphappens.core.data.db.ShipHappensDb
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.source.api.SourceConfigProvider
import com.shiphappens.source.api.TrackingSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

val coreDataModule = module {
    single<AppClock> { SystemClock() }
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { SettingsRepository(get()) } bind SourceConfigProvider::class
    single { SourceRegistry(getAll<TrackingSource>(), get()) }
    single { get<ShipHappensDb>().parcelDao() }
    single { ParcelRepository(get(), get(), get(), get()) }
    single { RefreshCoordinator(get(), get()) }
    single { ClipboardImportManager(get(), get(), get(), get()) }
}

/** Provides DataStore<Preferences>, ShipHappensDb, ClipboardReader per platform. */
expect fun platformDataModule(): Module
```

```kotlin
// PlatformDataModule.android.kt  (androidMain)
package com.shiphappens.core.data.di

import android.content.ClipboardManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.data.clipboard.ClipboardReader
import com.shiphappens.core.data.db.ShipHappensDb
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

private class AndroidClipboardReader(private val context: Context) : ClipboardReader {
    override suspend fun readText(): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
    }
}

actual fun platformDataModule(): Module = module {
    single {
        PreferenceDataStoreFactory.createWithPath {
            androidContext().filesDir.resolve("shiphappens.preferences_pb").absolutePath.toPath()
        }
    }
    single {
        Room.databaseBuilder<ShipHappensDb>(
            androidContext(),
            androidContext().getDatabasePath("shiphappens.db").absolutePath,
        ).setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    }
    single<ClipboardReader> { AndroidClipboardReader(androidContext()) }
}
```

```kotlin
// PlatformDataModule.ios.kt  (iosMain)
package com.shiphappens.core.data.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.data.clipboard.ClipboardReader
import com.shiphappens.core.data.db.ShipHappensDb
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIPasteboard

@OptIn(ExperimentalForeignApi::class)
private fun documentsDir(): String {
    val url = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory, inDomain = NSUserDomainMask,
        appropriateForURL = null, create = true, error = null,
    )
    return requireNotNull(url?.path)
}

private class IosClipboardReader : ClipboardReader {
    override suspend fun readText(): String? {
        val pb = UIPasteboard.generalPasteboard
        if (!pb.hasStrings) return null  // avoids the paste prompt when there's no text
        return pb.string
    }
}

actual fun platformDataModule(): Module = module {
    single { PreferenceDataStoreFactory.createWithPath { "${documentsDir()}/shiphappens.preferences_pb".toPath() } }
    single {
        Room.databaseBuilder<ShipHappensDb>("${documentsDir()}/shiphappens.db")
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    }
    single<ClipboardReader> { IosClipboardReader() }
}
```

```kotlin
// PlatformDataModule.jvm.kt  (jvmMain — exists only so the jvm target compiles; tests build their own graph)
package com.shiphappens.core.data.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.data.clipboard.ClipboardReader
import com.shiphappens.core.data.db.ShipHappensDb
import kotlinx.coroutines.Dispatchers
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformDataModule(): Module = module {
    single {
        val dir = System.getProperty("java.io.tmpdir")
        PreferenceDataStoreFactory.createWithPath { "$dir/shiphappens.preferences_pb".toPath() }
    }
    single {
        Room.inMemoryDatabaseBuilder<ShipHappensDb>()
            .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
    }
    single<ClipboardReader> { object : ClipboardReader { override suspend fun readText(): String? = null } }
}
```

(If the iOS `Room.databaseBuilder` overload differs in Room 3 — e.g. requires a `name` parameter label — match the actual signature; the intent is "file at documents dir/shiphappens.db, bundled driver, IO context".)

- [ ] **Step 4: Run, verify pass** — `./gradlew :core:data:jvmTest --console=plain` → all pass. Also compile iOS + Android: `./gradlew :core:data:compileKotlinIosSimulatorArm64 :core:data:compileDebugKotlinAndroid --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add core/data && git commit -m "[data] Add clipboard import manager and Koin DI modules

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: `:source:demo` — demo source

**Files:**
- Create: `source/demo/build.gradle.kts`
- Create: `source/demo/src/commonMain/kotlin/com/shiphappens/source/demo/DemoSource.kt`
- Create: `source/demo/src/commonMain/kotlin/com/shiphappens/source/demo/DemoModule.kt`
- Test: `source/demo/src/commonTest/kotlin/com/shiphappens/source/demo/DemoSourceTest.kt`

**Interfaces:**
- Consumes: everything from `:source:api`; `AppClock`-like time is NOT available here (`:source:*` cannot depend on `:core:data`) — DemoSource takes `today: () -> LocalDate` and `now: () -> Instant` lambdas instead.
- Produces: `class DemoSource(today: () -> LocalDate, now: () -> Instant) : TrackingSource, SeedingSource` with descriptor `id = "demo"`, `displayName = "Demo data"`, `kind = UNIVERSAL`, `accentColorHex = "#17150F"`, empty configSpec; `val demoSourceModule: Module`.

- [ ] **Step 1: Write `source/demo/build.gradle.kts`** — same template as Task 2's build file with `namespace = "com.shiphappens.source.demo"` and:

```kotlin
commonMain.dependencies {
    api(projects.source.api)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
}
commonTest.dependencies {
    implementation(libs.kotlin.test)
    implementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.shiphappens.source.demo

import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.test.*

class DemoSourceTest {
    private val src = DemoSource(
        today = { LocalDate(2026, 7, 10) },
        now = { Instant.fromEpochMilliseconds(1_752_148_800_000) },
    )

    @Test fun seeds_the_seven_design_parcels() {
        val seeds = src.seeds()
        assertEquals(7, seeds.size)
        assertTrue(seeds.any { it.name == "Baseball cap" })
        assertTrue(seeds.any { it.name == "Trail running shoes" })
    }

    @Test fun tracks_known_numbers_with_relative_etas() = runTest {
        val shoes = src.seeds().first { it.name == "Trail running shoes" }
        val r = src.track(shoes.trackingNumber, shoes.carrier)
        assertIs<SourceResult.Success<TrackingSnapshot>>(r)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, r.value.status)
        assertEquals(LocalDate(2026, 7, 11), r.value.etaDate)   // today + 1
        assertTrue(r.value.events.isNotEmpty())
    }

    @Test fun delivered_seed_is_delivered() = runTest {
        val beans = src.seeds().first { it.name == "Oat-blend coffee beans" }
        val r = src.track(beans.trackingNumber, beans.carrier) as SourceResult.Success
        assertEquals(TrackingStatus.DELIVERED, r.value.status)
    }

    @Test fun unknown_number_is_not_found_and_detect_covers_seeds() = runTest {
        assertIs<SourceResult.Failure>(src.track("nope-123456789", null))
        val cap = src.seeds().first { it.name == "Baseball cap" }
        assertEquals(cap.carrier, src.detectCarrier(cap.trackingNumber))
        assertNull(src.detectCarrier("nope-123456789"))
    }
}
```

- [ ] **Step 3: Run, verify failure** — `./gradlew :source:demo:jvmTest --console=plain` → FAIL.

- [ ] **Step 4: Implement**

```kotlin
// DemoSource.kt
package com.shiphappens.source.demo

import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import kotlin.time.Instant
import kotlin.time.Duration.Companion.days
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod

private data class DemoEntry(
    val name: String, val tracking: String, val carrier: Carrier,
    val step: Int,           // 0..4 as in the design (4 = delivered)
    val etaOffsetDays: Int,  // relative to "today"
    val time: LocalTime,
)

// The design's seven sample parcels, dates made relative to today.
private fun entries(): List<DemoEntry> = listOf(
    DemoEntry("Baseball cap", "1ZW463200377332024", WellKnownCarriers.USPS, 2, 2, LocalTime(20, 0)),
    DemoEntry("Trail running shoes", "FX 8823 0199 4422", WellKnownCarriers.FEDEX, 3, 1, LocalTime(21, 0)),
    DemoEntry("Mechanical keyboard", "1Z 999 AA1 01 2345 6784", WellKnownCarriers.UPS, 2, 5, LocalTime(20, 0)),
    DemoEntry("Ceramic desk lamp", "1Z 88E 033 03 9876 5432", WellKnownCarriers.UPS, 3, 0, LocalTime(20, 0)),
    DemoEntry("Clear phone case", "9400 1118 9922 3300 1122", WellKnownCarriers.USPS, 1, 4, LocalTime(20, 0)),
    DemoEntry("Oat-blend coffee beans", "9400 1118 9922 3197 4284", WellKnownCarriers.USPS, 4, -2, LocalTime(14, 14)),
    DemoEntry("Paperback — The Overstory", "FX 7711 2058 3366", WellKnownCarriers.FEDEX, 4, -1, LocalTime(11, 42)),
)

private val STEP_STATUS = listOf(
    TrackingStatus.LABEL_CREATED, TrackingStatus.SHIPPED, TrackingStatus.IN_TRANSIT,
    TrackingStatus.OUT_FOR_DELIVERY, TrackingStatus.DELIVERED,
)
private val STEP_LABEL = listOf("Label created", "Shipped", "In transit", "Out for delivery", "Delivered")
private val STEP_LOCATION = listOf("Origin facility", "Departed origin", "Memphis, TN", "On vehicle for delivery", "Front porch")

class DemoSource(
    private val today: () -> LocalDate,
    private val now: () -> Instant,
) : TrackingSource, SeedingSource {

    override val descriptor = SourceDescriptor(
        id = "demo", displayName = "Demo data", kind = SourceKind.UNIVERSAL,
        accentColorHex = "#17150F", configSpec = emptyList(),
    )

    override fun seeds(): List<SeedParcel> = entries().map { SeedParcel(it.name, it.tracking, it.carrier) }

    override fun detectCarrier(trackingNumber: String): Carrier? =
        entries().firstOrNull { normalizeTracking(it.tracking) == normalizeTracking(trackingNumber) }?.carrier

    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val e = entries().firstOrNull { normalizeTracking(it.tracking) == normalizeTracking(trackingNumber) }
            ?: return SourceResult.Failure(FailureReason.NOT_FOUND, "Not a demo parcel")
        val events = (0..e.step).map { i ->
            TrackingEvent(
                timestamp = now() - (e.step - i).days,
                description = STEP_LABEL[i],
                location = STEP_LOCATION[i],
                status = STEP_STATUS[i],
            )
        }
        return SourceResult.Success(
            TrackingSnapshot(
                status = STEP_STATUS[e.step],
                events = events,
                etaDate = today().plus(DatePeriod(days = e.etaOffsetDays)),
                etaTime = e.time,
                latestLocation = STEP_LOCATION[e.step],
            )
        )
    }

    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> = SourceResult.Success(Unit)
}
```

```kotlin
// DemoModule.kt
package com.shiphappens.source.demo

import com.shiphappens.source.api.TrackingSource
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

val demoSourceModule: Module = module {
    single {
        DemoSource(
            today = { Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date },
            now = { Clock.System.now() },
        )
    } bind TrackingSource::class
}
```

- [ ] **Step 5: Run, verify pass** — `./gradlew :source:demo:jvmTest --console=plain` → 4 tests pass.

- [ ] **Step 6: Commit**

```bash
git add source/demo && git commit -m "[source] Add demo source seeding the design's sample parcels

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: `:source:trackingmore` — real TrackingMore v4 integration

**Files:**
- Create: `source/trackingmore/build.gradle.kts`
- Create: `source/trackingmore/src/commonMain/kotlin/com/shiphappens/source/trackingmore/TrackingMoreDtos.kt`
- Create: `source/trackingmore/src/commonMain/kotlin/com/shiphappens/source/trackingmore/TrackingMoreSource.kt`
- Create: `source/trackingmore/src/commonMain/kotlin/com/shiphappens/source/trackingmore/TrackingMoreModule.kt`
- Create: `source/trackingmore/src/androidMain/kotlin/com/shiphappens/source/trackingmore/Engine.android.kt`
- Create: `source/trackingmore/src/iosMain/kotlin/com/shiphappens/source/trackingmore/Engine.ios.kt`
- Create: `source/trackingmore/src/jvmMain/kotlin/com/shiphappens/source/trackingmore/Engine.jvm.kt`
- Test: `source/trackingmore/src/commonTest/kotlin/com/shiphappens/source/trackingmore/TrackingMoreSourceTest.kt`

**Interfaces:**
- Consumes: `:source:api` contract; `SourceConfigProvider`.
- Produces: `class TrackingMoreSource(engine: HttpClientEngine, configProvider: SourceConfigProvider, baseUrl: String = "https://api.trackingmore.com") : TrackingSource` — descriptor `id = "trackingmore"`, `displayName = "TrackingMore"`, `kind = UNIVERSAL`, accent `#0F766E`, configSpec `[ConfigField("apiKey", "API key", "Paste your TrackingMore API key", isSecret = true)]`; `val trackingMoreSourceModule: Module`; `expect fun trackingMoreEngine(): HttpClientEngine`.
- API contract used (TrackingMore v4, header `Tracking-Api-Key`):
  - `GET {base}/v4/trackings/get?tracking_numbers={num}` → `{"meta":{"code":200},"data":[{"tracking_number":..,"courier_code":..,"delivery_status":..,"expected_delivery":..,"latest_checkpoint_time":..,"origin_info":{"trackinfo":[{"checkpoint_date":..,"tracking_detail":..,"location":..,"checkpoint_delivery_status":..}]}}]}`
  - `POST {base}/v4/trackings/create` body `{"tracking_number":"..","courier_code":".."}` (courier_code omitted → TrackingMore auto-detects)
  - `GET {base}/v4/couriers/all` → 200 with valid key (used by `testConnection`)
- Note: `detectCarrier` returns null (contract requires cheap/local detection; TrackingMore's server-side auto-detect happens implicitly via `create` without `courier_code`). Registry still routes unrecognized numbers here because the source is UNIVERSAL.
- Status mapping (delivery_status → TrackingStatus): `pending`/`inforeceived` → LABEL_CREATED; `transit` → IN_TRANSIT; `pickup` → OUT_FOR_DELIVERY; `delivered` → DELIVERED; `undelivered`/`exception` → EXCEPTION; anything else → UNKNOWN.
- Error mapping: HTTP 401/403 or `meta.code` 401 → AUTH; HTTP 429 → RATE_LIMITED; HTTP 404 → NOT_FOUND; IO exceptions → NETWORK; other non-2xx → UNKNOWN. Missing/blank API key → `Failure(AUTH, "Enter your TrackingMore API key first")` without any HTTP call.
- Track flow: `get` first; if `data` empty → `create` (then return `TrackingSnapshot(UNKNOWN)` — "waiting for first update"); else map the first data item.

- [ ] **Step 1: Write `source/trackingmore/build.gradle.kts`** — Task 2 template with `alias(libs.plugins.kotlinSerialization)` added, `namespace = "com.shiphappens.source.trackingmore"`, and:

```kotlin
commonMain.dependencies {
    api(projects.source.api)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
}
commonTest.dependencies {
    implementation(libs.kotlin.test)
    implementation(libs.kotlinx.coroutines.test)
    implementation(libs.ktor.client.mock)
}
androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
iosMain.dependencies { implementation(libs.ktor.client.darwin) }
jvmMain.dependencies { implementation(libs.ktor.client.java) }
```

- [ ] **Step 2: Write the failing tests (Ktor MockEngine + fixtures)**

```kotlin
package com.shiphappens.source.trackingmore

import com.shiphappens.core.model.TrackingStatus
import com.shiphappens.core.model.WellKnownCarriers
import com.shiphappens.source.api.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.*

private class FixedConfig(private val cfg: SourceConfig) : SourceConfigProvider {
    override suspend fun current(sourceId: String) = cfg
}

private val WITH_KEY = FixedConfig(SourceConfig(enabled = true, values = mapOf("apiKey" to "tm-key")))

private const val TRANSIT_FIXTURE = """
{"meta":{"code":200,"message":"Request response is successful"},
 "data":[{"tracking_number":"9400111899223300112",
          "courier_code":"usps",
          "delivery_status":"transit",
          "expected_delivery":"2026-07-14",
          "latest_checkpoint_time":"2026-07-10T08:12:00-05:00",
          "origin_info":{"trackinfo":[
            {"checkpoint_date":"2026-07-10T08:12:00-05:00","tracking_detail":"In transit to next facility","location":"Des Moines, IA","checkpoint_delivery_status":"transit"},
            {"checkpoint_date":"2026-07-09T18:03:00-05:00","tracking_detail":"Accepted at USPS origin facility","location":"Seattle, WA","checkpoint_delivery_status":"pending"}]}}]}
"""

private const val EMPTY_FIXTURE = """{"meta":{"code":200,"message":"ok"},"data":[]}"""
private const val CREATED_FIXTURE = """{"meta":{"code":200,"message":"ok"},"data":{"tracking_number":"1Z999AA10123456784","courier_code":"ups"}}"""

class TrackingMoreSourceTest {
    private fun source(handler: MockRequestHandler): TrackingMoreSource =
        TrackingMoreSource(MockEngine(handler), WITH_KEY)

    @Test fun maps_transit_response_to_snapshot() = runTest {
        val src = source { request ->
            assertEquals("tm-key", request.headers["Tracking-Api-Key"])
            respond(TRANSIT_FIXTURE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val r = src.track("9400111899223300112", WellKnownCarriers.USPS)
        assertIs<SourceResult.Success<*>>(r)
        val snap = (r as SourceResult.Success).value
        assertEquals(TrackingStatus.IN_TRANSIT, snap.status)
        assertEquals(LocalDate(2026, 7, 14), snap.etaDate)
        assertEquals(2, snap.events.size)
        assertEquals("Des Moines, IA", snap.latestLocation)
        assertEquals("Accepted at USPS origin facility", snap.events.first().description) // sorted oldest first
    }

    @Test fun empty_get_creates_tracking_then_returns_unknown() = runTest {
        var created = false
        val src = source { request ->
            when {
                request.url.encodedPath.endsWith("/v4/trackings/get") ->
                    respond(EMPTY_FIXTURE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/v4/trackings/create") -> {
                    created = true
                    respond(CREATED_FIXTURE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val r = src.track("1Z999AA10123456784", null)
        assertTrue(created)
        assertIs<SourceResult.Success<*>>(r)
        assertEquals(TrackingStatus.UNKNOWN, (r as SourceResult.Success).value.status)
    }

    @Test fun http_401_maps_to_auth_and_429_to_rate_limited() = runTest {
        val auth = source { respondError(HttpStatusCode.Unauthorized) }.track("9400111899223300112", null)
        assertEquals(FailureReason.AUTH, (auth as SourceResult.Failure).reason)
        val rate = source { respondError(HttpStatusCode.TooManyRequests) }.track("9400111899223300112", null)
        assertEquals(FailureReason.RATE_LIMITED, (rate as SourceResult.Failure).reason)
    }

    @Test fun missing_key_fails_fast_without_http() = runTest {
        val src = TrackingMoreSource(
            MockEngine { fail("no HTTP call expected") },
            FixedConfig(SourceConfig(enabled = true)),
        )
        val r = src.track("9400111899223300112", null)
        assertEquals(FailureReason.AUTH, (r as SourceResult.Failure).reason)
    }

    @Test fun testConnection_ok_and_auth_failure() = runTest {
        val ok = source { respond("""{"meta":{"code":200},"data":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertIs<SourceResult.Success<Unit>>(ok.testConnection(SourceConfig(enabled = true, values = mapOf("apiKey" to "k"))))
        val bad = source { respondError(HttpStatusCode.Unauthorized) }
        val r = bad.testConnection(SourceConfig(enabled = true, values = mapOf("apiKey" to "wrong")))
        assertEquals(FailureReason.AUTH, (r as SourceResult.Failure).reason)
    }
}
```

- [ ] **Step 3: Run, verify failure** — `./gradlew :source:trackingmore:jvmTest --console=plain` → FAIL.

- [ ] **Step 4: Implement DTOs, source, engine expects, Koin module**

```kotlin
// TrackingMoreDtos.kt
package com.shiphappens.source.trackingmore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class TmMeta(val code: Int = 0, val message: String? = null)

@Serializable data class TmGetResponse(val meta: TmMeta = TmMeta(), val data: List<TmTracking> = emptyList())

// create returns `data` as an object; we only care about meta, so keep it loose.
@Serializable data class TmCreateResponse(val meta: TmMeta = TmMeta(), val data: JsonElement? = null)

@Serializable data class TmTracking(
    @SerialName("tracking_number") val trackingNumber: String = "",
    @SerialName("courier_code") val courierCode: String? = null,
    @SerialName("delivery_status") val deliveryStatus: String? = null,
    @SerialName("expected_delivery") val expectedDelivery: String? = null,
    @SerialName("origin_info") val originInfo: TmOriginInfo? = null,
    @SerialName("destination_info") val destinationInfo: TmOriginInfo? = null,
)

@Serializable data class TmOriginInfo(@SerialName("trackinfo") val trackInfo: List<TmCheckpoint> = emptyList())

@Serializable data class TmCheckpoint(
    @SerialName("checkpoint_date") val checkpointDate: String? = null,
    @SerialName("tracking_detail") val trackingDetail: String? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("checkpoint_delivery_status") val checkpointDeliveryStatus: String? = null,
)

@Serializable data class TmCreateRequest(
    @SerialName("tracking_number") val trackingNumber: String,
    @SerialName("courier_code") val courierCode: String? = null,
)
```

```kotlin
// TrackingMoreSource.kt
package com.shiphappens.source.trackingmore

import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json

private fun mapStatus(s: String?): TrackingStatus = when (s?.lowercase()) {
    "pending", "inforeceived" -> TrackingStatus.LABEL_CREATED
    "transit" -> TrackingStatus.IN_TRANSIT
    "pickup" -> TrackingStatus.OUT_FOR_DELIVERY
    "delivered" -> TrackingStatus.DELIVERED
    "undelivered", "exception" -> TrackingStatus.EXCEPTION
    else -> TrackingStatus.UNKNOWN
}

class TrackingMoreSource(
    engine: HttpClientEngine,
    private val configProvider: SourceConfigProvider,
    private val baseUrl: String = "https://api.trackingmore.com",
) : TrackingSource {

    override val descriptor = SourceDescriptor(
        id = "trackingmore", displayName = "TrackingMore", kind = SourceKind.UNIVERSAL,
        accentColorHex = "#0F766E",
        configSpec = listOf(ConfigField("apiKey", "API key", "Paste your TrackingMore API key", isSecret = true)),
    )

    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
        expectSuccess = false
    }

    override fun detectCarrier(trackingNumber: String): Carrier? = null  // server-side auto-detect via create

    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val key = apiKey() ?: return SourceResult.Failure(FailureReason.AUTH, "Enter your TrackingMore API key first")
        return runCatching<SourceResult<TrackingSnapshot>> {
            val resp = client.get("$baseUrl/v4/trackings/get") {
                header("Tracking-Api-Key", key)
                parameter("tracking_numbers", normalizeTracking(trackingNumber))
            }
            httpFailure(resp.status)?.let { return it }
            val body: TmGetResponse = resp.body()
            if (body.meta.code == 401) return SourceResult.Failure(FailureReason.AUTH, body.meta.message)
            val item = body.data.firstOrNull() ?: return createTracking(key, trackingNumber, carrier)
            SourceResult.Success(item.toSnapshot())
        }.getOrElse { SourceResult.Failure(FailureReason.NETWORK, it.message) }
    }

    private suspend fun createTracking(key: String, trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val resp = client.post("$baseUrl/v4/trackings/create") {
            header("Tracking-Api-Key", key)
            contentType(ContentType.Application.Json)
            setBody(TmCreateRequest(normalizeTracking(trackingNumber), carrier?.code))
        }
        httpFailure(resp.status)?.let { return it }
        val body: TmCreateResponse = resp.body()
        if (body.meta.code == 401) return SourceResult.Failure(FailureReason.AUTH, body.meta.message)
        // Registered; carrier data arrives on the next refresh.
        return SourceResult.Success(TrackingSnapshot(TrackingStatus.UNKNOWN))
    }

    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> {
        val key = config["apiKey"] ?: return SourceResult.Failure(FailureReason.AUTH, "Enter your TrackingMore API key first")
        return runCatching<SourceResult<Unit>> {
            val resp = client.get("$baseUrl/v4/couriers/all") { header("Tracking-Api-Key", key) }
            httpFailure(resp.status)?.let { return it }
            SourceResult.Success(Unit)
        }.getOrElse { SourceResult.Failure(FailureReason.NETWORK, it.message) }
    }

    private suspend fun apiKey(): String? = configProvider.current(descriptor.id)["apiKey"]

    private fun httpFailure(status: HttpStatusCode): SourceResult.Failure? = when {
        status.isSuccess() -> null
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
            SourceResult.Failure(FailureReason.AUTH, "TrackingMore rejected the API key")
        status == HttpStatusCode.TooManyRequests -> SourceResult.Failure(FailureReason.RATE_LIMITED, "Rate limited")
        status == HttpStatusCode.NotFound -> SourceResult.Failure(FailureReason.NOT_FOUND, "Not found")
        else -> SourceResult.Failure(FailureReason.UNKNOWN, "HTTP ${status.value}")
    }
}

private fun TmTracking.toSnapshot(): TrackingSnapshot {
    val checkpoints = (originInfo?.trackInfo.orEmpty() + destinationInfo?.trackInfo.orEmpty())
    val events = checkpoints.mapNotNull { cp ->
        val ts = cp.checkpointDate?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
        TrackingEvent(ts, cp.trackingDetail ?: "Update", cp.location, mapStatus(cp.checkpointDeliveryStatus))
    }.sortedBy { it.timestamp }
    return TrackingSnapshot(
        status = mapStatus(deliveryStatus),
        events = events,
        etaDate = expectedDelivery?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        etaTime = expectedDelivery?.drop(11)?.take(8)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        latestLocation = events.lastOrNull()?.location,
    )
}
```

```kotlin
// TrackingMoreModule.kt
package com.shiphappens.source.trackingmore

import com.shiphappens.source.api.TrackingSource
import io.ktor.client.engine.HttpClientEngine
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

expect fun trackingMoreEngine(): HttpClientEngine

val trackingMoreSourceModule: Module = module {
    single { TrackingMoreSource(trackingMoreEngine(), get()) } bind TrackingSource::class
}
```

```kotlin
// Engine.android.kt (androidMain)
package com.shiphappens.source.trackingmore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
actual fun trackingMoreEngine(): HttpClientEngine = OkHttp.create()
```

```kotlin
// Engine.ios.kt (iosMain)
package com.shiphappens.source.trackingmore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
actual fun trackingMoreEngine(): HttpClientEngine = Darwin.create()
```

```kotlin
// Engine.jvm.kt (jvmMain)
package com.shiphappens.source.trackingmore
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java
actual fun trackingMoreEngine(): HttpClientEngine = Java.create()
```

- [ ] **Step 5: Run, verify pass** — `./gradlew :source:trackingmore:jvmTest --console=plain` → 5 tests pass. Also `./gradlew :source:trackingmore:compileKotlinIosSimulatorArm64 --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add source/trackingmore && git commit -m "[source] Add TrackingMore v4 source with Ktor client and fixtures

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 11: `:source:ups`, `:source:usps`, `:source:fedex` — stub carrier sources

**Files (same pattern ×3, substituting the carrier):**
- Create: `source/ups/build.gradle.kts`, `source/ups/src/commonMain/kotlin/com/shiphappens/source/ups/UpsSource.kt`
- Create: `source/usps/build.gradle.kts`, `source/usps/src/commonMain/kotlin/com/shiphappens/source/usps/UspsSource.kt`
- Create: `source/fedex/build.gradle.kts`, `source/fedex/src/commonMain/kotlin/com/shiphappens/source/fedex/FedexSource.kt`
- Test: `source/ups/src/commonTest/kotlin/com/shiphappens/source/ups/UpsSourceTest.kt` (ups only — the three are structurally identical; one test proves the pattern)

**Interfaces:**
- Consumes: `:source:api`.
- Produces: `val upsSourceModule: Module`, `val uspsSourceModule: Module`, `val fedexSourceModule: Module`, each binding its source `bind TrackingSource::class`. Config specs (from the design's settings screen, exact labels/placeholders):
  - UPS: `ConfigField("clientId", "Client ID", "UPS OAuth client ID")`, `ConfigField("clientSecret", "Client secret", "••••••••", isSecret = true)`
  - USPS: `ConfigField("consumerKey", "Consumer key", "USPS consumer key")`, `ConfigField("consumerSecret", "Consumer secret", "••••••••", isSecret = true)`
  - FedEx: `ConfigField("apiKey", "API key", "FedEx API key")`, `ConfigField("secretKey", "Secret key", "••••••••", isSecret = true)`

- [ ] **Step 1: Write the failing test (ups)**

```kotlin
package com.shiphappens.source.ups

import com.shiphappens.core.model.WellKnownCarriers
import com.shiphappens.source.api.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class UpsSourceTest {
    private val src = UpsSource()

    @Test fun descriptor_declares_oauth_fields() {
        assertEquals("ups", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertEquals(listOf("clientId", "clientSecret"), src.descriptor.configSpec.map { it.key })
    }
    @Test fun detects_1z_numbers_only() {
        assertEquals(WellKnownCarriers.UPS, src.detectCarrier("1Z 999 AA1 01 2345 6784"))
        assertNull(src.detectCarrier("9400111899223300112"))
    }
    @Test fun track_is_not_implemented_and_test_connection_validates_fields() = runTest {
        assertIs<SourceResult.Failure>(src.track("1Z999AA10123456784", null))
        val empty = src.testConnection(SourceConfig(enabled = true))
        assertEquals(FailureReason.AUTH, (empty as SourceResult.Failure).reason)
        assertIs<SourceResult.Success<Unit>>(
            src.testConnection(SourceConfig(enabled = true, values = mapOf("clientId" to "a", "clientSecret" to "b")))
        )
    }
}
```

- [ ] **Step 2: Run, verify failure** — `./gradlew :source:ups:jvmTest --console=plain` → FAIL.

- [ ] **Step 3: Implement all three stubs**

Each `build.gradle.kts` = Task 9's demo build file with the namespace changed (`com.shiphappens.source.ups` / `.usps` / `.fedex`).

```kotlin
// UpsSource.kt
package com.shiphappens.source.ups

import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.WellKnownCarriers
import com.shiphappens.core.model.normalizeTracking
import com.shiphappens.core.model.TrackingSnapshot
import com.shiphappens.source.api.*
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

class UpsSource : TrackingSource {
    override val descriptor = SourceDescriptor(
        id = "ups", displayName = "UPS", kind = SourceKind.CARRIER,
        accentColorHex = WellKnownCarriers.UPS.accentColorHex,
        configSpec = listOf(
            ConfigField("clientId", "Client ID", "UPS OAuth client ID"),
            ConfigField("clientSecret", "Client secret", "••••••••", isSecret = true),
        ),
    )
    override fun detectCarrier(trackingNumber: String): Carrier? =
        WellKnownCarriers.UPS.takeIf { Regex("^1Z[0-9A-Z]{10,}$").matches(normalizeTracking(trackingNumber)) }
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> =
        SourceResult.Failure(FailureReason.UNKNOWN, "UPS direct API not implemented yet")
    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> =
        if (descriptor.configSpec.all { config[it.key] != null }) SourceResult.Success(Unit)
        else SourceResult.Failure(FailureReason.AUTH, "Enter UPS credentials first")
}

val upsSourceModule: Module = module { single { UpsSource() } bind TrackingSource::class }
```

`UspsSource.kt` and `FedexSource.kt` are the same shape with these substitutions:
- USPS: id `usps`, name `USPS`, accent from `WellKnownCarriers.USPS`, fields `consumerKey`/`consumerSecret` (labels above), detection `Regex("^(94|93|92|95|82)\\d{14,24}$")` or `Regex("^[A-Z]{2}\\d{9}US$")` matches, messages "USPS direct API not implemented yet" / "Enter USPS credentials first", module val `uspsSourceModule`.
- FedEx: id `fedex`, name `FedEx`, accent from `WellKnownCarriers.FEDEX`, fields `apiKey`/`secretKey` (labels above), detection `Regex("^\\d{12}$|^\\d{15}$|^\\d{20,22}$")`, messages "FedEx direct API not implemented yet" / "Enter FedEx credentials first", module val `fedexSourceModule`.

- [ ] **Step 4: Run, verify pass** — `./gradlew :source:ups:jvmTest :source:usps:compileKotlinJvm :source:fedex:compileKotlinJvm --console=plain` → BUILD SUCCESSFUL, ups tests pass.

- [ ] **Step 5: Commit**

```bash
git add source/ups source/usps source/fedex && git commit -m "[source] Add UPS/USPS/FedEx stub sources proving the plugin contract

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 12: `:ui` scaffold — module, fonts, theme

**Files:**
- Create: `ui/build.gradle.kts`
- Create (downloaded): `ui/src/commonMain/composeResources/font/hanken_400.ttf`, `hanken_500.ttf`, `hanken_600.ttf`, `hanken_700.ttf`, `hanken_800.ttf`, `mono_400.ttf`, `mono_500.ttf`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/theme/Theme.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/util/Formatters.kt`

**Interfaces:**
- Consumes: everything below `:ui` in the graph.
- Produces:
  - `object ShipColors` (see code), `@Composable fun ShipTheme(content: @Composable () -> Unit)`, `@Composable fun hankenFamily(): FontFamily`, `@Composable fun monoFamily(): FontFamily`
  - `fun colorFromHex(hex: String?): Color` (falls back to `ShipColors.ink`)
  - `fun Carrier.accentHex(): String` = `accentColorHex ?: fallbackAccentColor(code)`
  - `fun LocalDate.designFormat(): String` → `"Fri, Jul 10"`; `fun LocalTime.design12h(): String` → `"8:00 PM"`
  - iOS framework `SharedUI` exported from this module.

- [ ] **Step 1: Write `ui/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SharedUI"
            isStatic = true
        }
    }
    sourceSets {
        all { languageSettings.optIn("kotlin.time.ExperimentalTime") }
        commonMain.dependencies {
            api(projects.core.data)
            implementation(projects.source.trackingmore)
            implementation(projects.source.demo)
            implementation(projects.source.ups)
            implementation(projects.source.usps)
            implementation(projects.source.fedex)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.lifecycle.viewmodel)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigation3.runtime)
            implementation(libs.navigation3.ui)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.kotlinx.serialization.json)
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.resources { packageOfResClass = "com.shiphappens.ui.res" }

android {
    namespace = "com.shiphappens.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt() }
}
```

(Note the DI-assembly exception from Global Constraints: `:ui` depends on the source modules ONLY for `di/AppModules.kt` in Task 17 — Swift cannot compose Koin modules, so `:ui` is the iOS composition root.)

- [ ] **Step 2: Download the design's fonts (Fontsource static TTFs)**

```bash
mkdir -p ui/src/commonMain/composeResources/font
for w in 400 500 600 700 800; do
  curl -sfL "https://cdn.jsdelivr.net/fontsource/fonts/hanken-grotesk@latest/latin-$w-normal.ttf" \
    -o "ui/src/commonMain/composeResources/font/hanken_$w.ttf"
done
for w in 400 500; do
  curl -sfL "https://cdn.jsdelivr.net/fontsource/fonts/jetbrains-mono@latest/latin-$w-normal.ttf" \
    -o "ui/src/commonMain/composeResources/font/mono_$w.ttf"
done
ls -la ui/src/commonMain/composeResources/font/
```

Expected: 7 files, each > 10 KB. If the CDN is unreachable, STOP and report (don't ship an empty font dir).

- [ ] **Step 3: Write the theme + formatters**

```kotlin
// Theme.kt
package com.shiphappens.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.fallbackAccentColor
import com.shiphappens.ui.res.Res
import com.shiphappens.ui.res.hanken_400
import com.shiphappens.ui.res.hanken_500
import com.shiphappens.ui.res.hanken_600
import com.shiphappens.ui.res.hanken_700
import com.shiphappens.ui.res.hanken_800
import com.shiphappens.ui.res.mono_400
import com.shiphappens.ui.res.mono_500
import org.jetbrains.compose.resources.Font

object ShipColors {
    val bg = Color(0xFFF7F6F3)
    val card = Color(0xFFFFFFFF)
    val cardAlt = Color(0xFFFBFAF7)
    val ink = Color(0xFF17150F)
    val muted = Color(0xFF8A857C)
    val faint = Color(0xFFA8A296)
    val hairline = Color(0xFFECEAE3)
    val hairlineStrong = Color(0xFFE2DFD8)
    val segmentBg = Color(0xFFEAE7E0)
    val urgent = Color(0xFFC2410C)
    val delivered = Color(0xFF1F7A4D)
    val deliveredBg = Color(0xFFE7F3EC)
    val toggleOff = Color(0xFFDAD6CE)
}

fun colorFromHex(hex: String?): Color {
    val h = hex?.removePrefix("#") ?: return ShipColors.ink
    val v = h.toLongOrNull(16) ?: return ShipColors.ink
    return Color(0xFF000000 or v)
}

fun Carrier.accentHex(): String = accentColorHex ?: fallbackAccentColor(code)

@Composable
fun hankenFamily() = FontFamily(
    Font(Res.font.hanken_400, FontWeight.Normal),
    Font(Res.font.hanken_500, FontWeight.Medium),
    Font(Res.font.hanken_600, FontWeight.SemiBold),
    Font(Res.font.hanken_700, FontWeight.Bold),
    Font(Res.font.hanken_800, FontWeight.ExtraBold),
)

@Composable
fun monoFamily() = FontFamily(
    Font(Res.font.mono_400, FontWeight.Normal),
    Font(Res.font.mono_500, FontWeight.Medium),
)

@Composable
fun ShipTheme(content: @Composable () -> Unit) {
    val hanken = hankenFamily()
    val base = Typography()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = ShipColors.ink,
            background = ShipColors.bg,
            surface = ShipColors.card,
            surfaceVariant = ShipColors.cardAlt,
            onBackground = ShipColors.ink,
            onSurface = ShipColors.ink,
            outline = ShipColors.hairline,
        ),
        typography = Typography(
            displaySmall = base.displaySmall.copy(fontFamily = hanken, fontWeight = FontWeight.ExtraBold),
            headlineMedium = base.headlineMedium.copy(fontFamily = hanken, fontWeight = FontWeight.ExtraBold),
            titleLarge = base.titleLarge.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
            bodyLarge = base.bodyLarge.copy(fontFamily = hanken),
            bodyMedium = base.bodyMedium.copy(fontFamily = hanken),
            bodySmall = base.bodySmall.copy(fontFamily = hanken),
            labelLarge = base.labelLarge.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
            labelMedium = base.labelMedium.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
            labelSmall = base.labelSmall.copy(fontFamily = hanken, fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}
```

```kotlin
// Formatters.kt
package com.shiphappens.ui.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber

private val WD = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MO = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

// (kotlinx-datetime 0.7: if `dayOfMonth`/`monthNumber` are gone, use `.day` / `.month.number`.)
fun LocalDate.designFormat(): String = "${WD[dayOfWeek.isoDayNumber - 1]}, ${MO[monthNumber - 1]} $dayOfMonth"

fun LocalTime.design12h(): String {
    val h12 = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
    val ampm = if (hour < 12) "AM" else "PM"
    return "$h12:${minute.toString().padStart(2, '0')} $ampm"
}
```

- [ ] **Step 4: Verify it compiles both platforms**

Run: `./gradlew :ui:compileDebugKotlinAndroid :ui:compileKotlinIosSimulatorArm64 --console=plain`
Expected: BUILD SUCCESSFUL (Compose resource accessors generate on first compile).

- [ ] **Step 5: Commit**

```bash
git add ui && git commit -m "[ui] Scaffold Compose module with design theme and bundled fonts

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 13: `:ui` — ListViewModel (TDD)

**Files:**
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListUiState.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListViewModel.kt`
- Test: `ui/src/androidUnitTest/kotlin/com/shiphappens/ui/list/ListViewModelTest.kt`

**Interfaces:**
- Consumes: `ParcelRepository`, `AddResult`, `RefreshSummary`, `RefreshCoordinator`, `ClipboardImportManager`, `AppClock`, `BuiltInCarrierDetection` (Tasks 6–8); `WellKnownCarriers`, `TrackingStatus.stepIndex` (Task 2); `accentHex`, `designFormat` (Task 12).
- Produces (consumed by Task 14's screen):

```kotlin
// ListUiState.kt — full file, written first
package com.shiphappens.ui.list

enum class ListTab { ACTIVE, ARCHIVED }

data class RingUi(val number: String, val fraction: Float)

data class ParcelCardUi(
    val id: String,
    val name: String,
    val carrierName: String,
    val accentHex: String,
    val statusText: String,
    val delivered: Boolean,
    val swipeable: Boolean,
    val showRestore: Boolean,
    val ring: RingUi?,
    val urgent: Boolean,
)

data class PendingImportUi(
    val carrierName: String,
    val accentHex: String,
    val tracking: String,
    val name: String,
)

data class CarrierOption(val code: String?, val label: String, val accentHex: String?)

data class ManualAddUi(
    val name: String = "",
    val tracking: String = "",
    val pickedCarrierCode: String? = null,
    val pickerOpen: Boolean = false,
    val effectiveCarrierName: String? = null,
    val effectiveAccentHex: String? = null,
    val options: List<CarrierOption> = emptyList(),
)

data class ToastUi(val message: String, val showUndo: Boolean = false)

data class ListUiState(
    val dateLabel: String = "",
    val headerSub: String = "",
    val tab: ListTab = ListTab.ACTIVE,
    val cards: List<ParcelCardUi> = emptyList(),
    val emptyText: String? = null,
    val pendingImport: PendingImportUi? = null,
    val manualAdd: ManualAddUi = ManualAddUi(),
    val toast: ToastUi? = null,
    val isRefreshing: Boolean = false,
)
```

- ViewModel events (exact signatures): `onTabSelect(tab: ListTab)`, `onManualName(v: String)`, `onManualTracking(v: String)`, `onPickerToggle()`, `onPickCarrier(code: String?)`, `onAddManual()`, `onClearManual()`, `onPendingName(v: String)`, `onAcceptPending()`, `onDismissPending()`, `onArchive(id: String)`, `onUndo()`, `onRestore(id: String)`, `onRefresh()`, `onForeground()`, plus `val state: StateFlow<ListUiState>`.

- [ ] **Step 1: Write `ListUiState.kt`** (code above, verbatim).

- [ ] **Step 2: Write the failing tests**

```kotlin
package com.shiphappens.ui.list

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.data.*
import com.shiphappens.core.data.clipboard.ClipboardImportManager
import com.shiphappens.core.data.clipboard.ClipboardReader
import com.shiphappens.core.data.db.ShipHappensDb
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.test.*

private class FixedClock : AppClock {
    var instant: Instant = Instant.fromEpochMilliseconds(1_752_148_800_000)
    var date: LocalDate = LocalDate(2026, 7, 10)
    override fun now() = instant
    override fun today() = date
}

private class FakeClipboard(var text: String? = null) : ClipboardReader {
    override suspend fun readText(): String? = text
}

private class FakeSource(
    var snapshot: TrackingSnapshot = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 12)),
) : TrackingSource {
    override val descriptor = SourceDescriptor("fake", "Fake", SourceKind.UNIVERSAL)
    override fun detectCarrier(trackingNumber: String): Carrier? = null
    override suspend fun track(trackingNumber: String, carrier: Carrier?) = SourceResult.Success(snapshot)
    override suspend fun testConnection(config: SourceConfig) = SourceResult.Success(Unit)
}

class ListViewModelTest {
    private lateinit var repo: ParcelRepository
    private lateinit var clipboard: FakeClipboard
    private lateinit var manager: ClipboardImportManager
    private lateinit var settings: SettingsRepository
    private lateinit var clock: FixedClock
    private lateinit var source: FakeSource

    private fun TestScope.vm(): ListViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = kotlin.io.path.createTempDirectory("listvm").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        clock = FixedClock()
        source = FakeSource()
        val registry = SourceRegistry(listOf(source), settings)
        repo = ParcelRepository(db.parcelDao(), registry, settings, clock)
        clipboard = FakeClipboard()
        manager = ClipboardImportManager(clipboard, registry, db.parcelDao(), settings)
        val coordinator = RefreshCoordinator(repo, backgroundScope)
        val vm = ListViewModel(repo, manager, coordinator, clock)
        backgroundScope.launch { vm.state.collect() }  // keep WhileSubscribed alive
        return vm
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun cards_show_ring_days_and_status() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        repo.addParcel("Keyboard", "1Z999AA10123456784", WellKnownCarriers.UPS)
        advanceUntilIdle()
        val card = vm.state.value.cards.single()
        assertEquals("Keyboard", card.name)
        assertEquals("UPS", card.carrierName)
        assertEquals("In transit", card.statusText)
        assertEquals("2", card.ring?.number)                 // eta 7/12, today 7/10
        assertEquals(2f / 4f, card.ring!!.fraction, 0.001f)  // IN_TRANSIT = step 2
        assertFalse(card.urgent)
        assertEquals("Fri, Jul 10", vm.state.value.dateLabel)
        assertEquals("1 arriving soon", vm.state.value.headerSub)
    }

    @Test fun out_for_delivery_today_is_urgent_with_status_override() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        source.snapshot = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, etaDate = LocalDate(2026, 7, 10))
        repo.addParcel("Lamp", "1Z88E0330398765432", WellKnownCarriers.UPS)
        advanceUntilIdle()
        val card = vm.state.value.cards.single()
        assertTrue(card.urgent)
        assertEquals("Out for delivery today", card.statusText)
        assertEquals("0", card.ring?.number)
    }

    @Test fun archive_shows_undo_toast_and_undo_restores() = runTest {
        val vm = vm()
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        advanceUntilIdle()
        vm.onArchive(added.parcel.id)
        advanceUntilIdle()
        assertTrue(vm.state.value.cards.isEmpty())
        assertEquals("Package archived", vm.state.value.toast?.message)
        assertTrue(vm.state.value.toast!!.showUndo)
        vm.onUndo()
        advanceUntilIdle()
        assertEquals(1, vm.state.value.cards.size)
        assertNull(vm.state.value.toast)
    }

    @Test fun archived_tab_shows_restore_and_empty_texts() = runTest {
        val vm = vm()
        vm.onTabSelect(ListTab.ARCHIVED)
        advanceUntilIdle()
        assertNotNull(vm.state.value.emptyText)
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        repo.archive(added.parcel.id)
        advanceUntilIdle()
        assertTrue(vm.state.value.cards.single().showRestore)
        assertEquals("1 package archived", vm.state.value.headerSub)
    }

    @Test fun manual_add_validates_then_adds() = runTest {
        val vm = vm()
        vm.onAddManual()
        advanceUntilIdle()
        assertEquals("Enter a tracking number", vm.state.value.toast?.message)
        vm.onManualTracking("ZZ!!ZZ!!ZZ!!")
        vm.onAddManual()
        advanceUntilIdle()
        assertEquals("Tap the icon to choose a carrier", vm.state.value.toast?.message)
        vm.onManualTracking("1Z999AA10123456784")   // auto-detected UPS
        vm.onManualName("Keyboard")
        vm.onAddManual()
        advanceUntilIdle()
        assertEquals("Delivery added", vm.state.value.toast?.message)
        assertEquals("Keyboard", vm.state.value.cards.single().name)
        assertEquals("", vm.state.value.manualAdd.tracking)
        vm.onManualTracking("1z 999 aa1 01 2345 6784")
        vm.onAddManual()
        advanceUntilIdle()
        assertEquals("That package is already in your list", vm.state.value.toast?.message)
    }

    @Test fun clipboard_pending_import_accept_flow() = runTest {
        val vm = vm()
        clipboard.text = "9400 1118 9922 3300 1122"
        vm.onForeground()
        advanceUntilIdle()
        assertEquals("USPS", vm.state.value.pendingImport?.carrierName)
        vm.onPendingName("Phone case")
        vm.onAcceptPending()
        advanceUntilIdle()
        assertNull(vm.state.value.pendingImport)
        assertEquals("Phone case", vm.state.value.cards.single().name)
    }

    @Test fun manual_picker_effective_carrier_follows_pick_then_detection() = runTest {
        val vm = vm()
        advanceUntilIdle()
        assertEquals(listOf(null, "ups", "usps", "fedex"), vm.state.value.manualAdd.options.map { it.code })
        vm.onManualTracking("1Z999AA10123456784")
        advanceUntilIdle()
        assertEquals("UPS", vm.state.value.manualAdd.effectiveCarrierName)
        vm.onPickCarrier("fedex")
        advanceUntilIdle()
        assertEquals("FedEx", vm.state.value.manualAdd.effectiveCarrierName)
    }
}
```

- [ ] **Step 3: Run, verify failure** — `./gradlew :ui:testDebugUnitTest --console=plain` → FAIL (unresolved `ListViewModel`).

- [ ] **Step 4: Implement `ListViewModel`**

```kotlin
// ListViewModel.kt
package com.shiphappens.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.core.data.*
import com.shiphappens.core.data.clipboard.ClipboardImportManager
import com.shiphappens.core.data.clipboard.PendingImport
import com.shiphappens.core.data.source.BuiltInCarrierDetection
import com.shiphappens.core.model.*
import com.shiphappens.source.api.FailureReason
import com.shiphappens.ui.theme.accentHex
import com.shiphappens.ui.util.designFormat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.daysUntil

private val STATUS_TEXT = mapOf(
    TrackingStatus.LABEL_CREATED to "Label created",
    TrackingStatus.SHIPPED to "Shipped",
    TrackingStatus.IN_TRANSIT to "In transit",
    TrackingStatus.OUT_FOR_DELIVERY to "Out for delivery",
    TrackingStatus.DELIVERED to "Delivered",
    TrackingStatus.EXCEPTION to "Delivery exception",
    TrackingStatus.UNKNOWN to "Waiting for first update",
)

class ListViewModel(
    private val repository: ParcelRepository,
    private val clipboard: ClipboardImportManager,
    private val coordinator: RefreshCoordinator,
    private val clock: AppClock,
) : ViewModel() {

    private val tab = MutableStateFlow(ListTab.ACTIVE)
    private val manual = MutableStateFlow(ManualAddUi(options = carrierOptions()))
    private val pendingName = MutableStateFlow("")
    private val toast = MutableStateFlow<ToastUi?>(null)
    private val refreshing = MutableStateFlow(false)
    private var lastArchivedId: String? = null
    private var toastJob: Job? = null

    init {
        viewModelScope.launch {
            coordinator.summaries.collect { s -> if (s.failed > 0) flash(failureMessage(s.firstFailureReason)) }
        }
    }

    private data class Content(
        val tab: ListTab, val active: List<Parcel>, val archived: List<Parcel>,
        val pending: PendingImport?, val manual: ManualAddUi,
    )

    private val content = combine(
        tab, repository.observeParcels(false), repository.observeParcels(true),
        clipboard.pending, manual,
    ) { t, act, arc, pend, man -> Content(t, act, arc, pend, man) }

    val state: StateFlow<ListUiState> =
        combine(content, pendingName, toast, refreshing) { c, pName, t, r ->
            val parcels = if (c.tab == ListTab.ACTIVE) c.active else c.archived
            val arriving = c.active.count { it.status != TrackingStatus.DELIVERED }
            ListUiState(
                dateLabel = clock.today().designFormat(),
                headerSub = if (c.tab == ListTab.ACTIVE) "$arriving arriving soon"
                    else "${c.archived.size} package${if (c.archived.size == 1) "" else "s"} archived",
                tab = c.tab,
                cards = parcels.map { it.toCard(c.tab) },
                emptyText = if (parcels.isNotEmpty()) null
                    else if (c.tab == ListTab.ACTIVE) "No active deliveries right now."
                    else "Nothing archived yet. Swipe a delivered package left to archive it.",
                pendingImport = if (c.tab == ListTab.ACTIVE) c.pending?.let {
                    PendingImportUi(it.carrier.displayName, it.carrier.accentHex(), it.trackingNumber, pName)
                } else null,
                manualAdd = c.manual,
                toast = t,
                isRefreshing = r,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    private fun Parcel.toCard(tab: ListTab): ParcelCardUi {
        val delivered = status == TrackingStatus.DELIVERED
        val days = etaDate?.let { clock.today().daysUntil(it) }
        val urgent = !delivered && days != null && days <= 1
        val statusText = if (!delivered && days != null && days <= 0 && status != TrackingStatus.EXCEPTION)
            "Out for delivery today" else STATUS_TEXT.getValue(status)
        return ParcelCardUi(
            id = id, name = name, carrierName = carrier.displayName, accentHex = carrier.accentHex(),
            statusText = statusText, delivered = delivered,
            swipeable = delivered && tab == ListTab.ACTIVE,
            showRestore = tab == ListTab.ARCHIVED,
            ring = if (delivered) null else RingUi(
                number = (days?.coerceAtLeast(0) ?: 0).toString(),
                fraction = (status.stepIndex.coerceAtLeast(0)) / 4f,
            ),
            urgent = urgent,
        )
    }

    private fun carrierOptions() = listOf(CarrierOption(null, "Auto-detect", null)) +
        WellKnownCarriers.all.map { CarrierOption(it.code, it.displayName, it.accentHex()) }

    fun onTabSelect(t: ListTab) { tab.value = t }

    fun onManualName(v: String) = manual.update { it.copy(name = v) }
    fun onManualTracking(v: String) = manual.update { it.withEffective(tracking = v) }
    fun onPickerToggle() = manual.update { it.copy(pickerOpen = !it.pickerOpen) }
    fun onPickCarrier(code: String?) = manual.update { it.withEffective(picked = code, closePicker = true) }
    fun onClearManual() = manual.update { ManualAddUi(options = it.options) }

    private fun ManualAddUi.withEffective(
        tracking: String = this.tracking, picked: String? = this.pickedCarrierCode, closePicker: Boolean = false,
    ): ManualAddUi {
        val effective = picked?.let { WellKnownCarriers.byCode(it) } ?: BuiltInCarrierDetection.detect(tracking)
        return copy(
            tracking = tracking, pickedCarrierCode = picked,
            pickerOpen = if (closePicker) false else pickerOpen,
            effectiveCarrierName = effective?.displayName,
            effectiveAccentHex = effective?.accentHex(),
        )
    }

    fun onAddManual() {
        val m = manual.value
        if (m.tracking.isBlank()) return flash("Enter a tracking number")
        val carrier = m.pickedCarrierCode?.let { WellKnownCarriers.byCode(it) }
        viewModelScope.launch {
            when (repository.addParcel(m.name, m.tracking, carrier)) {
                is AddResult.Added -> { onClearManual(); flash("Delivery added") }
                AddResult.Duplicate -> flash("That package is already in your list")
                AddResult.NoCarrier -> flash("Tap the icon to choose a carrier")
            }
        }
    }

    fun onPendingName(v: String) { pendingName.value = v }
    fun onDismissPending() { clipboard.dismiss(); pendingName.value = "" }
    fun onAcceptPending() {
        val p = clipboard.pending.value ?: return
        viewModelScope.launch {
            repository.addParcel(pendingName.value, p.trackingNumber, p.carrier)
            clipboard.dismiss()
            pendingName.value = ""
            flash("Delivery added")
        }
    }

    fun onArchive(id: String) {
        lastArchivedId = id
        viewModelScope.launch { repository.archive(id); flash("Package archived", undo = true, ms = 3_800) }
    }
    fun onUndo() {
        val id = lastArchivedId ?: return
        toastJob?.cancel(); toast.value = null
        viewModelScope.launch { repository.restore(id) }
    }
    fun onRestore(id: String) { viewModelScope.launch { repository.restore(id) } }

    fun onRefresh() {
        viewModelScope.launch {
            refreshing.value = true
            val s = repository.refreshAll(force = true)
            refreshing.value = false
            if (s.failed > 0) flash(failureMessage(s.firstFailureReason))
        }
    }

    fun onForeground() {
        viewModelScope.launch { clipboard.checkClipboard() }
        coordinator.onAppForeground()
    }

    private fun failureMessage(reason: FailureReason?) = when (reason) {
        FailureReason.AUTH -> "Couldn't refresh — check your source API keys"
        FailureReason.RATE_LIMITED -> "Couldn't refresh — rate limited, try later"
        else -> "Couldn't refresh — network error"
    }

    private fun flash(message: String, undo: Boolean = false, ms: Long = 2_600) {
        toastJob?.cancel()
        toast.value = ToastUi(message, undo)
        toastJob = viewModelScope.launch { delay(ms); toast.value = null }
    }
}
```

- [ ] **Step 5: Run, verify pass** — `./gradlew :ui:testDebugUnitTest --console=plain` → 7 tests pass.

- [ ] **Step 6: Commit**

```bash
git add ui && git commit -m "[ui] Add ListViewModel with tabs, ring math, clipboard and manual add

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 14: `:ui` — List screen composables

**Files:**
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/list/ListScreen.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/components/Toast.kt`

**Interfaces:**
- Consumes: `ListViewModel`, `ListUiState` and sub-types (Task 13); theme (Task 12).
- Produces: `@Composable fun ListScreen(onOpenDetail: (String) -> Unit, onOpenSettings: () -> Unit, vm: ListViewModel = koinViewModel())`, `@Composable fun ToastOverlay(toast: ToastUi?, onUndo: () -> Unit, modifier: Modifier)` (reused by Settings via a String overload `ToastOverlay(message: String?, modifier)`).
- Visual reference: `design/Parcels.dc.html` LIST SCREEN section. Layout: header (date label, "Ship Happens" title, subtitle, settings button) → Active/Archived segmented tabs → scrollable card list (pending-import card, manual-add card, parcel rows) inside `PullToRefreshBox` → toast overlay bottom-center.

- [ ] **Step 1: Implement the screen**

```kotlin
// ListScreen.kt
package com.shiphappens.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shiphappens.ui.components.ToastOverlay
import com.shiphappens.ui.theme.*
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onOpenDetail: (String) -> Unit,
    onOpenSettings: () -> Unit,
    vm: ListViewModel = koinViewModel(),
) {
    val state by vm.state.collectAsState()

    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.currentStateFlow.collect { if (it == Lifecycle.State.RESUMED) vm.onForeground() }
    }

    Box(Modifier.fillMaxSize().background(ShipColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            Header(state, onOpenSettings)
            Tabs(state.tab, vm::onTabSelect)
            PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = vm::onRefresh, modifier = Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 26.dp, top = 2.dp),
                ) {
                    state.pendingImport?.let { p ->
                        item(key = "pending") {
                            PendingImportCard(p, vm::onPendingName, vm::onAcceptPending, vm::onDismissPending)
                        }
                    }
                    if (state.tab == ListTab.ACTIVE && state.pendingImport == null) {
                        item(key = "manual") {
                            ManualAddCard(state.manualAdd, vm::onManualName, vm::onManualTracking,
                                vm::onPickerToggle, vm::onPickCarrier, vm::onAddManual, vm::onClearManual)
                        }
                    }
                    items(state.cards, key = { it.id }) { card ->
                        ParcelRow(card, onClick = { onOpenDetail(card.id) },
                            onArchive = { vm.onArchive(card.id) }, onRestore = { vm.onRestore(card.id) })
                    }
                    state.emptyText?.let { item(key = "empty") { EmptyState(it) } }
                }
            }
        }
        ToastOverlay(state.toast, vm::onUndo, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
    }
}

@Composable
private fun Header(state: ListUiState, onOpenSettings: () -> Unit) {
    Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 30.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(state.dateLabel.uppercase(), color = ShipColors.faint,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp))
            Text("Ship Happens", color = ShipColors.ink, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold,
                fontFamily = hankenFamily())
            Text(state.headerSub, color = ShipColors.muted, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedIconButton(
            onClick = onOpenSettings, shape = RoundedCornerShape(13.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, ShipColors.hairlineStrong),
            colors = IconButtonDefaults.outlinedIconButtonColors(containerColor = ShipColors.card),
        ) { Text("⚙", fontSize = 17.sp) }
    }
}

@Composable
private fun Tabs(tab: ListTab, onSelect: (ListTab) -> Unit) {
    Row(
        Modifier.padding(horizontal = 22.dp, vertical = 14.dp).fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)).background(ShipColors.segmentBg).padding(4.dp),
    ) {
        listOf(ListTab.ACTIVE to "Active", ListTab.ARCHIVED to "Archived").forEach { (t, label) ->
            val selected = tab == t
            Text(
                label, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (selected) ShipColors.ink else ShipColors.muted,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                fontFamily = hankenFamily(),
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                    .background(if (selected) ShipColors.card else Color.Transparent)
                    .clickable { onSelect(t) }.padding(vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun CarrierBadge(accentHex: String, size: Int = 46) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.3).dp)).background(colorFromHex(accentHex)),
        contentAlignment = Alignment.Center,
    ) { Text("📦", fontSize = (size * 0.42).sp) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParcelRow(card: ParcelCardUi, onClick: () -> Unit, onArchive: () -> Unit, onRestore: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ShipColors.card)
                .border(1.dp, ShipColors.hairline, RoundedCornerShape(20.dp))
                .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CarrierBadge(card.accentHex)
            Column(Modifier.weight(1f)) {
                Text(card.name, color = ShipColors.ink, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = hankenFamily())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(card.carrierName, color = colorFromHex(card.accentHex), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Box(Modifier.size(3.dp).clip(CircleShape).background(ShipColors.hairlineStrong))
                    Text(card.statusText, color = ShipColors.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                card.delivered && !card.showRestore -> Text(
                    "Delivered", color = ShipColors.delivered, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(ShipColors.deliveredBg)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                )
                card.showRestore -> OutlinedButton(onClick = onRestore, shape = RoundedCornerShape(9.dp)) {
                    Text("Restore", fontSize = 11.sp, color = ShipColors.muted)
                }
                card.ring != null -> DaysRing(card.ring, colorFromHex(card.accentHex), card.urgent)
            }
        }
    }

    Box(Modifier.padding(vertical = 9.dp)) {
        if (card.swipeable) {
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { v ->
                    if (v == SwipeToDismissBoxValue.EndToStart) { onArchive(); true } else false
                },
            )
            SwipeToDismissBox(
                state = dismissState, enableDismissFromStartToEnd = false,
                backgroundContent = {
                    Row(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(ShipColors.urgent)
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
                    ) { Text("Archive", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                },
            ) { content() }
        } else content()
    }
}

@Composable
private fun DaysRing(ring: RingUi, accent: Color, urgent: Boolean) {
    Box(Modifier.size(50.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            val inset = 4.dp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(ShipColors.hairline, 0f, 360f, false, Offset(inset, inset), arcSize, style = stroke)
            drawArc(accent, -90f, 360f * ring.fraction, false, Offset(inset, inset), arcSize, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ring.number, color = if (urgent) ShipColors.urgent else ShipColors.ink,
                fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, fontFamily = hankenFamily())
            Text("DAYS", color = ShipColors.faint, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DashedCard(borderColor: Color, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp).clip(RoundedCornerShape(20.dp))
            .background(ShipColors.cardAlt).border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(13.dp),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(13.dp),
        content = content,
    )
}

@Composable
private fun SmallActionButton(bg: Color, label: String, onClick: () -> Unit, outlined: Boolean = false) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
            .background(if (outlined) ShipColors.card else bg)
            .then(if (outlined) Modifier.border(1.dp, ShipColors.hairlineStrong, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (outlined) ShipColors.faint else Color.White, fontWeight = FontWeight.ExtraBold) }
}

@Composable
private fun CardTextField(value: String, onChange: (String) -> Unit, placeholder: String, mono: Boolean = false) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = ShipColors.ink,
            fontFamily = if (mono) monoFamily() else hankenFamily(),
            fontWeight = if (mono) FontWeight.Normal else FontWeight.Bold,
            fontSize = if (mono) 12.sp else 16.sp,
        ),
        decorationBox = { inner ->
            Box { if (value.isEmpty()) Text(placeholder, color = ShipColors.faint, fontSize = if (mono) 12.sp else 16.sp); inner() }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PendingImportCard(p: PendingImportUi, onName: (String) -> Unit, onAccept: () -> Unit, onDismiss: () -> Unit) {
    val accent = colorFromHex(p.accentHex)
    DashedCard(accent) {
        CarrierBadge(p.accentHex, size = 44)
        Column(Modifier.weight(1f)) {
            Text("FROM CLIPBOARD · ${p.carrierName.uppercase()}", color = accent, fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
            CardTextField(p.name, onName, "Name this package")
            Text(p.tracking, color = ShipColors.muted, fontFamily = monoFamily(), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallActionButton(accent, "✓", onAccept)
            SmallActionButton(ShipColors.card, "✕", onDismiss, outlined = true)
        }
    }
}

@Composable
private fun ManualAddCard(
    m: ManualAddUi, onName: (String) -> Unit, onTracking: (String) -> Unit,
    onPickerToggle: () -> Unit, onPick: (String?) -> Unit, onAdd: () -> Unit, onClear: () -> Unit,
) {
    val accent = m.effectiveAccentHex?.let(::colorFromHex) ?: Color(0xFFC3BDB1)
    Box {
        DashedCard(if (m.effectiveAccentHex != null) accent else Color(0xFFCFC9BE)) {
            Box {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(accent)
                    .clickable(onClick = onPickerToggle), contentAlignment = Alignment.Center) {
                    Text(if (m.effectiveCarrierName != null) "📦" else "+", color = Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
                DropdownMenu(expanded = m.pickerOpen, onDismissRequest = onPickerToggle) {
                    m.options.forEach { opt ->
                        DropdownMenuItem(
                            leadingIcon = {
                                Box(Modifier.size(14.dp).clip(CircleShape)
                                    .background(opt.accentHex?.let(::colorFromHex) ?: ShipColors.cardAlt)
                                    .border(if (opt.accentHex == null) 2.dp else 0.dp, Color(0xFFC3BDB1), CircleShape))
                            },
                            text = { Text(opt.label, fontWeight = FontWeight.SemiBold) },
                            onClick = { onPick(opt.code) },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    m.effectiveCarrierName?.let { "NEW · ${it.uppercase()}" } ?: "ADD A PACKAGE",
                    color = if (m.effectiveCarrierName != null) accent else ShipColors.faint,
                    fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
                )
                CardTextField(m.name, onName, "Package name")
                CardTextField(m.tracking, onTracking, "Tracking number", mono = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallActionButton(if (m.effectiveAccentHex != null) accent else Color(0xFFD8D3CA), "✓", onAdd)
                SmallActionButton(ShipColors.card, "✕", onClear, outlined = true)
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 70.dp, horizontal = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFEDEBE4)),
            contentAlignment = Alignment.Center) { Text("📦", fontSize = 24.sp) }
        Spacer(Modifier.height(16.dp))
        Text(text, color = ShipColors.faint, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 21.sp)
    }
}
```

```kotlin
// Toast.kt
package com.shiphappens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiphappens.ui.list.ToastUi
import com.shiphappens.ui.theme.ShipColors

@Composable
fun ToastOverlay(toast: ToastUi?, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    if (toast == null) return
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(ShipColors.ink)
            .padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(toast.message, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (toast.showUndo) {
            Text("Undo", color = Color(0xFFF2A15A), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp,
                modifier = Modifier.clickable(onClick = onUndo).padding(horizontal = 4.dp, vertical = 2.dp))
        }
    }
}

@Composable
fun ToastOverlay(message: String?, modifier: Modifier = Modifier) {
    ToastOverlay(message?.let { ToastUi(it) }, onUndo = {}, modifier = modifier)
}
```

(Two pragmatic notes for the implementer: (1) if an import above doesn't resolve against the installed Compose version, fix the import — never the structure; (2) emoji placeholders `📦`/`⚙`/`✓`/`✕` stand in for the design's SVG icons; keep them — vector icon fidelity is not a v1 requirement.)

- [ ] **Step 2: Verify it compiles** — `./gradlew :ui:compileDebugKotlinAndroid :ui:compileKotlinIosSimulatorArm64 --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add ui && git commit -m "[ui] Add list screen: tabs, parcel rows, swipe-archive, add cards, toast

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 15: `:ui` — Detail (ViewModel TDD + screen)

**Files:**
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/detail/DetailViewModel.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/detail/DetailScreen.kt`
- Test: `ui/src/androidUnitTest/kotlin/com/shiphappens/ui/detail/DetailViewModelTest.kt`

**Interfaces:**
- Consumes: `ParcelRepository.observeParcel` (Task 7), `AppClock`, formatters/theme (Task 12).
- Produces:
  - `enum class StepState { DONE, CURRENT, TODO }`
  - `data class TimelineStepUi(val label: String, val time: String?, val state: StepState)`
  - `data class DetailUiState(val loaded: Boolean = false, val name: String = "", val carrierName: String = "", val accentHex: String = "#17150F", val headline: String = "", val windowLabel: String = "", val windowText: String = "", val locationText: String? = null, val trackingNumber: String = "", val timeline: List<TimelineStepUi> = emptyList())`
  - `class DetailViewModel(parcelId: String, repository: ParcelRepository, clock: AppClock) : ViewModel` with `val state: StateFlow<DetailUiState>`
  - `@Composable fun DetailScreen(parcelId: String, onBack: () -> Unit)` (obtains VM via `koinViewModel(key = parcelId) { parametersOf(parcelId) }`)
- Derivation rules (from design's `buildDetail`): headline = "Delivered" / "Arriving today" (d≤0) / "Arrives tomorrow" (d==1) / "Arrives in N days"; EXCEPTION status → headline "Delivery exception". windowLabel = "Delivered" when delivered else "Estimated delivery"; windowText = `etaDate.designFormat()` + (delivered ? " · $time" : " · by $time") when present. Timeline: labels `["Label created","Shipped","In transit","Out for delivery","Delivered"]`; effective step = `status.stepIndex`, or if negative the max `stepIndex` among event statuses, or 0; steps < effective → DONE, == effective → CURRENT (unless delivered: all DONE), > → TODO. Step time = latest event with that step's status → `date.designFormat()`; CURRENT appends " · latest update"; the DELIVERED step appends " · " + event time in 12h.

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shiphappens.ui.detail

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.data.*
import com.shiphappens.core.data.db.ShipHappensDb
import com.shiphappens.core.data.db.toEntity
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.test.*

class DetailViewModelTest {
    private class FixedClock : AppClock {
        override fun now() = Instant.fromEpochMilliseconds(1_752_148_800_000)
        override fun today() = LocalDate(2026, 7, 10)
    }

    private lateinit var db: ShipHappensDb

    private fun TestScope.vm(parcel: Parcel): DetailViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = kotlin.io.path.createTempDirectory("detail").toString()
        val settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        val repo = ParcelRepository(db.parcelDao(), SourceRegistry(emptyList(), settings), settings, FixedClock())
        val vm = DetailViewModel(parcel.id, repo, FixedClock())
        backgroundScope.launch { vm.state.collect() }
        return vm
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun base(status: TrackingStatus, eta: LocalDate?) = Parcel(
        id = "p1", name = "Trail running shoes", trackingNumber = "FX 8823 0199 4422",
        carrier = WellKnownCarriers.FEDEX, status = status, etaDate = eta, etaTime = LocalTime(21, 0),
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    @Test fun arrives_tomorrow_headline_and_window() = runTest {
        val p = base(TrackingStatus.OUT_FOR_DELIVERY, LocalDate(2026, 7, 11))
        val vm = vm(p); db.parcelDao().upsertParcel(p.toEntity()); advanceUntilIdle()
        val s = vm.state.value
        assertTrue(s.loaded)
        assertEquals("Arrives tomorrow", s.headline)
        assertEquals("Estimated delivery", s.windowLabel)
        assertEquals("Sat, Jul 11 · by 9:00 PM", s.windowText)
        assertEquals("FedEx", s.carrierName)
    }

    @Test fun timeline_marks_done_current_todo() = runTest {
        val ev = TrackingEvent(Instant.fromEpochMilliseconds(1_752_000_000_000), "In transit", "Memphis, TN", TrackingStatus.IN_TRANSIT)
        val p = base(TrackingStatus.IN_TRANSIT, LocalDate(2026, 7, 14)).copy(events = listOf(ev))
        val vm = vm(p)
        db.parcelDao().upsertParcel(p.toEntity())
        db.parcelDao().replaceEvents("p1", p.events.map { it.toEntity("p1") })
        advanceUntilIdle()
        val t = vm.state.value.timeline
        assertEquals(5, t.size)
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.CURRENT, StepState.TODO, StepState.TODO), t.map { it.state })
        assertTrue(t[2].time!!.endsWith("· latest update"))
        assertEquals("Memphis, TN", vm.state.value.locationText)
    }

    @Test fun delivered_shows_delivered_window_and_full_timeline() = runTest {
        val delivered = TrackingEvent(Instant.parse("2026-07-08T19:14:00Z"), "Delivered", "Front porch", TrackingStatus.DELIVERED)
        val p = base(TrackingStatus.DELIVERED, LocalDate(2026, 7, 8)).copy(events = listOf(delivered))
        val vm = vm(p)
        db.parcelDao().upsertParcel(p.toEntity())
        db.parcelDao().replaceEvents("p1", p.events.map { it.toEntity("p1") })
        advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Delivered", s.headline)
        assertEquals("Delivered", s.windowLabel)
        assertTrue(s.timeline.all { it.state == StepState.DONE })
    }

    @Test fun arriving_today_and_in_n_days() = runTest {
        val today = base(TrackingStatus.OUT_FOR_DELIVERY, LocalDate(2026, 7, 10))
        val vmA = vm(today); db.parcelDao().upsertParcel(today.toEntity()); advanceUntilIdle()
        assertEquals("Arriving today", vmA.state.value.headline)
        db.parcelDao().upsertParcel(base(TrackingStatus.IN_TRANSIT, LocalDate(2026, 7, 14)).toEntity())
        advanceUntilIdle()
        assertEquals("Arrives in 4 days", vmA.state.value.headline)
    }
}
```

- [ ] **Step 2: Run, verify failure** — `./gradlew :ui:testDebugUnitTest --console=plain` → FAIL.

- [ ] **Step 3: Implement ViewModel**

```kotlin
// DetailViewModel.kt
package com.shiphappens.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.core.data.AppClock
import com.shiphappens.core.data.ParcelRepository
import com.shiphappens.core.model.*
import com.shiphappens.ui.theme.accentHex
import com.shiphappens.ui.util.design12h
import com.shiphappens.ui.util.designFormat
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

enum class StepState { DONE, CURRENT, TODO }
data class TimelineStepUi(val label: String, val time: String?, val state: StepState)

data class DetailUiState(
    val loaded: Boolean = false,
    val name: String = "",
    val carrierName: String = "",
    val accentHex: String = "#17150F",
    val headline: String = "",
    val windowLabel: String = "",
    val windowText: String = "",
    val locationText: String? = null,
    val trackingNumber: String = "",
    val timeline: List<TimelineStepUi> = emptyList(),
)

private val STEP_LABELS = listOf("Label created", "Shipped", "In transit", "Out for delivery", "Delivered")

class DetailViewModel(
    parcelId: String,
    repository: ParcelRepository,
    private val clock: AppClock,
) : ViewModel() {

    val state: StateFlow<DetailUiState> = repository.observeParcel(parcelId)
        .map { parcel -> parcel?.toDetail() ?: DetailUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    private fun Parcel.toDetail(): DetailUiState {
        val delivered = status == TrackingStatus.DELIVERED
        val days = etaDate?.let { clock.today().daysUntil(it) }
        val headline = when {
            delivered -> "Delivered"
            status == TrackingStatus.EXCEPTION -> "Delivery exception"
            days == null -> "Waiting for first update"
            days <= 0 -> "Arriving today"
            days == 1 -> "Arrives tomorrow"
            else -> "Arrives in $days days"
        }
        val windowText = etaDate?.let { d ->
            val t = etaTime?.design12h()
            d.designFormat() + when { t == null -> ""; delivered -> " · $t"; else -> " · by $t" }
        } ?: "—"

        val effectiveStep = if (status.stepIndex >= 0) status.stepIndex
            else events.mapNotNull { it.status?.stepIndex }.filter { it >= 0 }.maxOrNull() ?: 0

        val tz = TimeZone.currentSystemDefault()
        val timeline = STEP_LABELS.mapIndexed { i, label ->
            val stepState = when {
                delivered || i < effectiveStep -> StepState.DONE
                i == effectiveStep -> StepState.CURRENT
                else -> StepState.TODO
            }
            val event = events.lastOrNull { it.status?.stepIndex == i }
            val time = event?.let {
                val ldt = it.timestamp.toLocalDateTime(tz)
                val base = ldt.date.designFormat()
                when {
                    i == 4 -> "$base · ${ldt.time.design12h()}"
                    stepState == StepState.CURRENT -> "$base · latest update"
                    else -> base
                }
            }
            TimelineStepUi(label, time, stepState)
        }

        return DetailUiState(
            loaded = true, name = name, carrierName = carrier.displayName, accentHex = carrier.accentHex(),
            headline = headline,
            windowLabel = if (delivered) "Delivered" else "Estimated delivery",
            windowText = windowText,
            locationText = latestLocation ?: events.lastOrNull()?.location,
            trackingNumber = trackingNumber,
            timeline = timeline,
        )
    }
}
```

- [ ] **Step 4: Run, verify pass** — `./gradlew :ui:testDebugUnitTest --console=plain` → all pass.

- [ ] **Step 5: Implement the screen**

```kotlin
// DetailScreen.kt
package com.shiphappens.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiphappens.ui.theme.*
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DetailScreen(parcelId: String, onBack: () -> Unit) {
    val vm: DetailViewModel = koinViewModel(key = parcelId) { parametersOf(parcelId) }
    val s by vm.state.collectAsState()
    val accent = colorFromHex(s.accentHex)

    Column(Modifier.fillMaxSize().background(ShipColors.bg)) {
        // Carrier-colored header
        Column(Modifier.fillMaxWidth().background(accent).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("‹ Back", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .16f))
                        .clickable(onClick = onBack).padding(horizontal = 14.dp, vertical = 8.dp))
                Text(s.carrierName, color = Color.White.copy(alpha = .9f), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(20.dp))
            Text(s.name, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, fontFamily = hankenFamily())
            Spacer(Modifier.height(9.dp))
            Text(s.headline, color = Color.White.copy(alpha = .92f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 34.dp)) {
            DetailCard {
                CardLabel(s.windowLabel)
                Text(s.windowText, color = ShipColors.ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, fontFamily = hankenFamily())
            }
            Spacer(Modifier.height(14.dp))
            // Map placeholder card (spec: decorative, with real location text)
            Box(Modifier.fillMaxWidth().height(152.dp).clip(RoundedCornerShape(18.dp))
                .background(Color(0xFFEEECE6)).border(1.dp, ShipColors.hairline, RoundedCornerShape(18.dp))) {
                Box(Modifier.align(Alignment.Center).size(20.dp).clip(CircleShape).background(accent))
                Text(s.locationText ?: "package location", color = ShipColors.muted, fontSize = 10.sp, fontFamily = monoFamily(),
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                        .clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = .72f)).padding(horizontal = 7.dp, vertical = 3.dp))
            }
            Spacer(Modifier.height(14.dp))
            DetailCard {
                CardLabel("Tracking history")
                Spacer(Modifier.height(8.dp))
                s.timeline.forEachIndexed { i, step ->
                    Row {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp)) {
                            val dotColor = when (step.state) {
                                StepState.DONE -> accent
                                StepState.CURRENT -> Color.White
                                StepState.TODO -> ShipColors.hairlineStrong
                            }
                            Box(Modifier.size(13.dp).clip(CircleShape).background(dotColor)
                                .then(if (step.state == StepState.CURRENT) Modifier.border(3.dp, accent, CircleShape) else Modifier))
                            if (i < s.timeline.lastIndex) {
                                Box(Modifier.width(2.dp).height(32.dp)
                                    .background(if (step.state == StepState.TODO) ShipColors.segmentBg else accent))
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.padding(bottom = 4.dp)) {
                            Text(step.label, fontSize = 14.sp,
                                fontWeight = if (step.state == StepState.TODO) FontWeight.SemiBold else FontWeight.Bold,
                                color = if (step.state == StepState.TODO) Color(0xFFB4AFA5) else ShipColors.ink)
                            step.time?.let { Text(it, fontSize = 12.sp, color = ShipColors.faint) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            DetailCard {
                CardLabel("${s.carrierName} tracking number")
                Text(s.trackingNumber, color = ShipColors.ink, fontFamily = monoFamily(), fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ShipColors.card)
        .border(1.dp, ShipColors.hairline, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        content = content)
}

@Composable
private fun CardLabel(text: String) {
    Text(text.uppercase(), color = ShipColors.faint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 6.dp))
}
```

- [ ] **Step 6: Verify compile** — `./gradlew :ui:compileDebugKotlinAndroid --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add ui && git commit -m "[ui] Add detail screen with timeline, delivery window, map placeholder

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 16: `:ui` — Settings (ViewModel TDD + screen)

**Files:**
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/settings/SettingsViewModel.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/settings/SettingsScreen.kt`
- Test: `ui/src/androidUnitTest/kotlin/com/shiphappens/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `SourceRegistry.all()` (Task 6), `SettingsRepository` (Task 4), `ParcelRepository.refreshAll` (Task 7), source descriptors (Tasks 9–11).
- Produces:
  - `data class FieldUi(val key: String, val label: String, val placeholder: String, val isSecret: Boolean, val value: String)`
  - `data class SourceCardUi(val id: String, val name: String, val accentHex: String, val enabled: Boolean, val statusText: String, val statusColorHex: String, val fields: List<FieldUi>, val endpointText: String?)`
  - `data class SettingsUiState(val universal: List<SourceCardUi> = emptyList(), val carriers: List<SourceCardUi> = emptyList(), val autoImport: Boolean = true, val frequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN, val toast: String? = null)`
  - `class SettingsViewModel(registry: SourceRegistry, settings: SettingsRepository, repository: ParcelRepository) : ViewModel` with `val state: StateFlow<SettingsUiState>` and events `onToggle(sourceId)`, `onField(sourceId, key, value)`, `onTest(sourceId)`, `onAutoImport(Boolean)`, `onFrequency(RefreshFrequency)`
  - `@Composable fun SettingsScreen(onBack: () -> Unit)`
- Status-line rules: disabled → "Not connected" (color `#A8A296`); enabled + all configSpec fields filled (or empty spec) → UNIVERSAL: "Connected · 1,000+ couriers", CARRIER: "Connected · syncing" (color = source accent or `#1F7A4D`); enabled but missing fields → "Enabled · add your credentials" (color `#C2410C`). endpointText: "api.trackingmore.com/v4" for id `trackingmore`, "Production endpoint" for CARRIER sources, null otherwise. Test-connection toasts: Success → "{name} credentials look valid"; Failure → its `message` if present, else "Couldn't reach {name}".
- Toggling a source ON also triggers `repository.refreshAll(force = false)` (seeds the demo source).

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.shiphappens.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.core.data.*
import com.shiphappens.core.data.db.ShipHappensDb
import com.shiphappens.core.data.settings.RefreshFrequency
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.source.demo.DemoSource
import com.shiphappens.source.ups.UpsSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.test.*

class SettingsViewModelTest {
    private class FixedClock : AppClock {
        override fun now() = Instant.fromEpochMilliseconds(1_752_148_800_000)
        override fun today() = LocalDate(2026, 7, 10)
    }

    private lateinit var settings: SettingsRepository
    private lateinit var repo: ParcelRepository

    private fun TestScope.vm(): SettingsViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = kotlin.io.path.createTempDirectory("settingsvm").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        val demo = DemoSource(today = { LocalDate(2026, 7, 10) }, now = { Instant.fromEpochMilliseconds(1_752_148_800_000) })
        val registry = SourceRegistry(listOf(demo, UpsSource()), settings)
        repo = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        val vm = SettingsViewModel(registry, settings, repo)
        backgroundScope.launch { vm.state.collect() }
        return vm
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun sources_are_grouped_and_default_disabled() = runTest {
        val vm = vm(); advanceUntilIdle()
        assertEquals(listOf("demo"), vm.state.value.universal.map { it.id })
        assertEquals(listOf("ups"), vm.state.value.carriers.map { it.id })
        assertEquals("Not connected", vm.state.value.carriers.single().statusText)
    }

    @Test fun toggle_enables_and_seeds_demo() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.onToggle("demo"); advanceUntilIdle()
        assertTrue(vm.state.value.universal.single().enabled)
        assertEquals("Connected · 1,000+ couriers", vm.state.value.universal.single().statusText)
        assertEquals(7, repo.observeParcels(false).first().size)  // demo seeds flowed through
    }

    @Test fun field_edits_persist_and_change_status() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.onToggle("ups"); advanceUntilIdle()
        assertEquals("Enabled · add your credentials", vm.state.value.carriers.single().statusText)
        vm.onField("ups", "clientId", "abc"); vm.onField("ups", "clientSecret", "shh"); advanceUntilIdle()
        assertEquals("Connected · syncing", vm.state.value.carriers.single().statusText)
        assertEquals("abc", settings.current("ups")["clientId"])
    }

    @Test fun test_connection_toasts() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.onTest("ups"); advanceUntilIdle()
        assertEquals("Enter UPS credentials first", vm.state.value.toast)
        vm.onField("ups", "clientId", "a"); vm.onField("ups", "clientSecret", "b")
        vm.onTest("ups"); advanceUntilIdle()
        assertEquals("UPS credentials look valid", vm.state.value.toast)
    }

    @Test fun sync_settings_roundtrip() = runTest {
        val vm = vm(); advanceUntilIdle()
        vm.onAutoImport(false); vm.onFrequency(RefreshFrequency.ONE_HOUR); advanceUntilIdle()
        assertFalse(vm.state.value.autoImport)
        assertEquals(RefreshFrequency.ONE_HOUR, vm.state.value.frequency)
    }
}
```

- [ ] **Step 2: Run, verify failure** — `./gradlew :ui:testDebugUnitTest --console=plain` → FAIL.

- [ ] **Step 3: Implement ViewModel**

```kotlin
// SettingsViewModel.kt
package com.shiphappens.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.core.data.ParcelRepository
import com.shiphappens.core.data.settings.RefreshFrequency
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.data.source.SourceRegistry
import com.shiphappens.source.api.*
import com.shiphappens.ui.theme.accentHex
import com.shiphappens.core.model.Carrier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class FieldUi(val key: String, val label: String, val placeholder: String, val isSecret: Boolean, val value: String)

data class SourceCardUi(
    val id: String, val name: String, val accentHex: String, val enabled: Boolean,
    val statusText: String, val statusColorHex: String, val fields: List<FieldUi>, val endpointText: String?,
)

data class SettingsUiState(
    val universal: List<SourceCardUi> = emptyList(),
    val carriers: List<SourceCardUi> = emptyList(),
    val autoImport: Boolean = true,
    val frequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN,
    val toast: String? = null,
)

class SettingsViewModel(
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val repository: ParcelRepository,
) : ViewModel() {

    private val toast = MutableStateFlow<String?>(null)
    private var toastJob: Job? = null

    val state: StateFlow<SettingsUiState> = combine(settings.settings, toast) { s, t ->
        val cards = registry.all().map { src ->
            val d = src.descriptor
            val cfg = s.sourceConfigs[d.id] ?: SourceConfig()
            val configured = d.configSpec.all { cfg[it.key] != null }
            val (statusText, statusColor) = when {
                !cfg.enabled -> "Not connected" to "#A8A296"
                !configured -> "Enabled · add your credentials" to "#C2410C"
                d.kind == SourceKind.UNIVERSAL -> "Connected · 1,000+ couriers" to (d.accentColorHex ?: "#1F7A4D")
                else -> "Connected · syncing" to "#1F7A4D"
            }
            SourceCardUi(
                id = d.id, name = d.displayName,
                accentHex = d.accentColorHex ?: Carrier(d.id, d.displayName).accentHex(),
                enabled = cfg.enabled, statusText = statusText, statusColorHex = statusColor,
                fields = d.configSpec.map { f -> FieldUi(f.key, f.label, f.placeholder, f.isSecret, cfg.values[f.key] ?: "") },
                endpointText = when {
                    d.id == "trackingmore" -> "api.trackingmore.com/v4"
                    d.kind == SourceKind.CARRIER -> "Production endpoint"
                    else -> null
                },
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

    fun onToggle(sourceId: String) {
        viewModelScope.launch {
            val cfg = settings.current(sourceId)
            settings.setSourceConfig(sourceId, cfg.copy(enabled = !cfg.enabled))
            if (!cfg.enabled) repository.refreshAll(force = false)  // just turned ON: seed + refresh
        }
    }

    fun onField(sourceId: String, key: String, value: String) {
        viewModelScope.launch {
            val cfg = settings.current(sourceId)
            settings.setSourceConfig(sourceId, cfg.copy(values = cfg.values + (key to value)))
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

    fun onAutoImport(enabled: Boolean) { viewModelScope.launch { settings.setAutoClipboardImport(enabled) } }
    fun onFrequency(freq: RefreshFrequency) { viewModelScope.launch { settings.setRefreshFrequency(freq) } }

    private fun flash(message: String) {
        toastJob?.cancel()
        toast.value = message
        toastJob = viewModelScope.launch { delay(2_600); toast.value = null }
    }
}
```

- [ ] **Step 4: Run, verify pass** — `./gradlew :ui:testDebugUnitTest --console=plain` → all pass.

- [ ] **Step 5: Implement the screen**

```kotlin
// SettingsScreen.kt
package com.shiphappens.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import com.shiphappens.core.data.settings.RefreshFrequency
import com.shiphappens.ui.components.ToastOverlay
import com.shiphappens.ui.theme.*
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(ShipColors.bg)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().background(ShipColors.card).padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("‹", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink,
                    modifier = Modifier.clip(RoundedCornerShape(13.dp)).border(1.dp, ShipColors.hairlineStrong, RoundedCornerShape(13.dp))
                        .clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 6.dp))
                Text("Settings", fontSize = 29.sp, fontWeight = FontWeight.ExtraBold, color = ShipColors.ink, fontFamily = hankenFamily())
            }

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 34.dp)) {
                SectionLabel("Universal API")
                s.universal.forEach { SourceCard(it, vm) }
                Spacer(Modifier.height(10.dp))
                SectionLabel("Direct carrier APIs")
                s.carriers.forEach { SourceCard(it, vm) }
                Spacer(Modifier.height(10.dp))
                SectionLabel("Sync")
                SyncCard(s, vm)
                Text(
                    "Keys are stored on this device only and used to fetch live tracking status directly from each carrier.",
                    color = ShipColors.faint, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 16.dp),
                )
            }
        }
        ToastOverlay(s.toast, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), color = ShipColors.faint, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp))
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(18.dp)).background(ShipColors.card)
            .border(1.dp, ShipColors.hairline, RoundedCornerShape(18.dp)).padding(16.dp),
        content = content,
    )
}

@Composable
private fun SourceCard(card: SourceCardUi, vm: SettingsViewModel) {
    val accent = colorFromHex(card.accentHex)
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent),
                contentAlignment = Alignment.Center,
            ) { Text(if (card.id == "trackingmore") "tm" else "📦", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
            Column(Modifier.weight(1f)) {
                Text(card.name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = ShipColors.ink, fontFamily = hankenFamily())
                Text(card.statusText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colorFromHex(card.statusColorHex))
            }
            Switch(
                checked = card.enabled, onCheckedChange = { vm.onToggle(card.id) },
                colors = SwitchDefaults.colors(checkedTrackColor = accent, uncheckedTrackColor = ShipColors.toggleOff),
            )
        }
        card.fields.forEach { f ->
            Spacer(Modifier.height(11.dp))
            Column {
                Text(f.label.uppercase(), color = ShipColors.faint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                Spacer(Modifier.height(5.dp))
                BasicTextField(
                    value = f.value, onValueChange = { vm.onField(card.id, f.key, it) }, singleLine = true,
                    visualTransformation = if (f.isSecret) PasswordVisualTransformation() else VisualTransformation.None,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ShipColors.ink, fontFamily = monoFamily(), fontSize = 13.sp),
                    decorationBox = { inner ->
                        androidx.compose.foundation.layout.Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(11.dp)).background(ShipColors.cardAlt)
                                .border(1.dp, ShipColors.hairline, RoundedCornerShape(11.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            if (f.value.isEmpty()) Text(f.placeholder, color = ShipColors.faint, fontSize = 13.sp, fontFamily = monoFamily())
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        card.endpointText?.let { endpoint ->
            Spacer(Modifier.height(13.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(endpoint, color = ShipColors.faint, fontSize = 11.sp, fontFamily = monoFamily())
                TextButton(onClick = { vm.onTest(card.id) }) {
                    Text("Test connection", color = if (card.id == "trackingmore") Color.White else ShipColors.ink,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (card.id == "trackingmore") accent else Color(0xFFF1EFE9))
                            .padding(horizontal = 13.dp, vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SyncCard(s: SettingsUiState, vm: SettingsViewModel) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-import from clipboard", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink)
                Text("Detect tracking numbers when you open the app", fontSize = 12.sp, color = ShipColors.muted)
            }
            Switch(checked = s.autoImport, onCheckedChange = vm::onAutoImport,
                colors = SwitchDefaults.colors(checkedTrackColor = ShipColors.ink, uncheckedTrackColor = ShipColors.toggleOff))
        }
        Spacer(Modifier.height(15.dp))
        Text("Refresh frequency", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = ShipColors.ink)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(RefreshFrequency.FIFTEEN_MIN to "15 min", RefreshFrequency.ONE_HOUR to "1 hour", RefreshFrequency.MANUAL to "Manual").forEach { (f, label) ->
                val active = s.frequency == f
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (active) Color.White else Color(0xFF6B665C),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(11.dp))
                        .background(if (active) ShipColors.ink else ShipColors.card)
                        .border(1.dp, if (active) ShipColors.ink else ShipColors.hairline, RoundedCornerShape(11.dp))
                        .clickable { vm.onFrequency(f) }.padding(vertical = 9.dp))
            }
        }
    }
}
```

- [ ] **Step 6: Verify compile** — `./gradlew :ui:compileDebugKotlinAndroid :ui:compileKotlinIosSimulatorArm64 --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add ui && git commit -m "[ui] Add settings screen with dynamic source cards and sync options

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 17: `:ui` — Navigation 3, Koin assembly, App root, iOS entry points

**Files:**
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/navigation/Routes.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/di/AppModules.kt`
- Create: `ui/src/commonMain/kotlin/com/shiphappens/ui/App.kt`
- Create: `ui/src/iosMain/kotlin/com/shiphappens/ui/MainViewController.kt`

**Interfaces:**
- Consumes: all three screens (Tasks 14–16); `coreDataModule`, `platformDataModule()` (Task 8); the five source Koin modules (Tasks 9–11).
- Produces (consumed by app entries in Tasks 18–19):
  - `fun appModules(): List<Module>`
  - `fun initKoin()` (iOS-style bootstrap; Android supplies its own `startKoin` with `androidContext`)
  - `@Composable fun App()`
  - iOS: `fun MainViewController(): UIViewController`

- [ ] **Step 1: Implement routes, DI, App**

```kotlin
// Routes.kt
package com.shiphappens.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object ListRoute : NavKey
@Serializable data class DetailRoute(val parcelId: String) : NavKey
@Serializable data object SettingsRoute : NavKey
```

```kotlin
// AppModules.kt
package com.shiphappens.ui.di

import com.shiphappens.core.data.di.coreDataModule
import com.shiphappens.core.data.di.platformDataModule
import com.shiphappens.source.demo.demoSourceModule
import com.shiphappens.source.fedex.fedexSourceModule
import com.shiphappens.source.trackingmore.trackingMoreSourceModule
import com.shiphappens.source.ups.upsSourceModule
import com.shiphappens.source.usps.uspsSourceModule
import com.shiphappens.ui.detail.DetailViewModel
import com.shiphappens.ui.list.ListViewModel
import com.shiphappens.ui.settings.SettingsViewModel
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val uiModule = module {
    viewModelOf(::ListViewModel)
    viewModelOf(::SettingsViewModel)
    viewModel { params -> DetailViewModel(params.get(), get(), get()) }
}

/** Adding a tracking source = implement TrackingSource in a new module + add its Koin module here. */
fun appModules(): List<Module> = listOf(
    platformDataModule(),
    coreDataModule,
    trackingMoreSourceModule,
    demoSourceModule,
    upsSourceModule,
    uspsSourceModule,
    fedexSourceModule,
    uiModule,
)

/** iOS bootstrap (Android calls startKoin itself to attach androidContext). */
fun initKoin() {
    startKoin { modules(appModules()) }
}
```

(If `viewModel { … }` needs an import in Koin 4.2 it is `org.koin.core.module.dsl.viewModel`; `viewModelOf` is `org.koin.core.module.dsl.viewModelOf`.)

```kotlin
// App.kt
package com.shiphappens.ui

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.entry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.shiphappens.ui.detail.DetailScreen
import com.shiphappens.ui.list.ListScreen
import com.shiphappens.ui.navigation.*
import com.shiphappens.ui.settings.SettingsScreen
import com.shiphappens.ui.theme.ShipTheme

@Composable
fun App() {
    ShipTheme {
        val backStack = rememberNavBackStack(ListRoute)
        NavDisplay(
            backStack = backStack,
            onBack = { backStack.removeLastOrNull() },
            entryProvider = entryProvider {
                entry<ListRoute> {
                    ListScreen(
                        onOpenDetail = { id -> backStack.add(DetailRoute(id)) },
                        onOpenSettings = { backStack.add(SettingsRoute) },
                    )
                }
                entry<DetailRoute> { route -> DetailScreen(route.parcelId, onBack = { backStack.removeLastOrNull() }) }
                entry<SettingsRoute> { SettingsScreen(onBack = { backStack.removeLastOrNull() }) }
            },
        )
    }
}
```

(Navigation 3 note: match the installed artifact's `NavDisplay`/`onBack` signature — in some versions `onBack` receives a count: `onBack = { repeat(it) { backStack.removeLastOrNull() } }`. The route/entry structure above is the stable part.)

```kotlin
// MainViewController.kt (iosMain)
package com.shiphappens.ui

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = ComposeUIViewController { App() }
```

- [ ] **Step 2: Verify compile** — `./gradlew :ui:compileDebugKotlinAndroid :ui:compileKotlinIosSimulatorArm64 --console=plain` → BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add ui && git commit -m "[ui] Wire Navigation 3, Koin app modules, App root and iOS entry

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 18: `:app-android` — Android application

**Files:**
- Modify: `gradle/libs.versions.toml` (add activity-compose)
- Create: `app-android/build.gradle.kts`
- Create: `app-android/src/main/AndroidManifest.xml`
- Create: `app-android/src/main/kotlin/com/shiphappens/android/ShipHappensApplication.kt`
- Create: `app-android/src/main/kotlin/com/shiphappens/android/MainActivity.kt`
- Create: `app-android/src/main/res/values/themes.xml`, `app-android/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `App()` and `appModules()` (Task 17).

- [ ] **Step 1: Add activity-compose to the catalog**

Check latest stable: `curl -sf "https://dl.google.com/android/maven2/androidx/activity/group-index.xml" | grep -o 'activity-compose versions="[^"]*"'` — pin the highest stable. Add to `[versions]`: `activityCompose = "1.12.0"` (replace with pinned) and to `[libraries]`:

```toml
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
```

- [ ] **Step 2: Write the module**

```kotlin
// app-android/build.gradle.kts
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.shiphappens.android"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "com.shiphappens"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(projects.ui)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
```

```xml
<!-- AndroidManifest.xml -->
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application
        android:name=".ShipHappensApplication"
        android:label="@string/app_name"
        android:theme="@style/Theme.ShipHappens"
        android:supportsRtl="true">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

```xml
<!-- res/values/strings.xml -->
<resources><string name="app_name">Ship Happens</string></resources>
```

```xml
<!-- res/values/themes.xml -->
<resources>
    <style name="Theme.ShipHappens" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">#F7F6F3</item>
    </style>
</resources>
```

```kotlin
// ShipHappensApplication.kt
package com.shiphappens.android

import android.app.Application
import com.shiphappens.ui.di.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ShipHappensApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ShipHappensApplication)
            modules(appModules())
        }
    }
}
```

```kotlin
// MainActivity.kt
package com.shiphappens.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.shiphappens.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App() }
    }
}
```

- [ ] **Step 3: Build the APK**

Run: `./gradlew :app-android:assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL; `app-android/build/outputs/apk/debug/app-android-debug.apk` exists.

- [ ] **Step 4: Commit**

```bash
git add gradle app-android && git commit -m "[android] Add application module with Koin bootstrap and MainActivity

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 19: `app-ios` — Xcode shell via XcodeGen

**Files:**
- Create: `app-ios/project.yml`
- Create: `app-ios/ShipHappens/ShipHappensApp.swift`
- Generated (gitignored): `app-ios/ShipHappens.xcodeproj`

**Interfaces:**
- Consumes: `SharedUI` framework from `:ui` (`MainViewController()`, `initKoin()` from Task 17).

- [ ] **Step 1: Write the XcodeGen spec and Swift entry**

```yaml
# app-ios/project.yml
name: ShipHappens
options:
  bundleIdPrefix: com.shiphappens
  deploymentTarget:
    iOS: "16.0"
settings:
  base:
    ENABLE_USER_SCRIPT_SANDBOXING: "NO"
targets:
  ShipHappens:
    type: application
    platform: iOS
    sources: [ShipHappens]
    info:
      path: ShipHappens/Info.plist
      properties:
        CFBundleDisplayName: Ship Happens
        UILaunchScreen: {}
    settings:
      base:
        PRODUCT_BUNDLE_IDENTIFIER: com.shiphappens
        OTHER_LDFLAGS: $(inherited) -framework SharedUI
        FRAMEWORK_SEARCH_PATHS: $(inherited) $(SRCROOT)/../ui/build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)
    preBuildScripts:
      - name: Build Kotlin Framework
        script: |
          cd "$SRCROOT/.."
          ./gradlew :ui:embedAndSignAppleFrameworkForXcode
        basedOnDependencyAnalysis: false
```

```swift
// app-ios/ShipHappens/ShipHappensApp.swift
import SwiftUI
import SharedUI

@main
struct ShipHappensApp: App {
    init() {
        AppModulesKt.doInitKoin()
    }
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
```

(Kotlin top-level functions surface in Swift as `<FileName>Kt.<name>` — `initKoin` lives in `AppModules.kt` → `AppModulesKt.doInitKoin()`. If Xcode autocompletes it as `AppModulesKt.initKoin()` — no `do` prefix — use what the generated header actually exports.)

- [ ] **Step 2: Generate and build**

```bash
command -v xcodegen >/dev/null || brew install xcodegen
(cd app-ios && xcodegen generate)
xcodebuild -project app-ios/ShipHappens.xcodeproj -scheme ShipHappens \
  -destination 'generic/platform=iOS Simulator' -configuration Debug build
```

Expected: `** BUILD SUCCEEDED **` (first run is slow — it compiles the Kotlin framework).

- [ ] **Step 3: Commit**

```bash
git add app-ios && git commit -m "[iOS] Add Xcode shell via XcodeGen hosting the shared Compose UI

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 20: Full verification, README, wrap-up

**Files:**
- Create: `README.md`
- Create: `.claude/launch.json` (optional, for browser-pane preview of docs — skip if not useful)

- [ ] **Step 1: Run every test suite**

```bash
./gradlew :core:model:jvmTest :core:data:jvmTest :source:api:jvmTest \
          :source:demo:jvmTest :source:trackingmore:jvmTest :source:ups:jvmTest \
          :ui:testDebugUnitTest --console=plain
```

Expected: BUILD SUCCESSFUL, zero failures.

- [ ] **Step 2: Build both apps**

```bash
./gradlew :app-android:assembleDebug --console=plain
xcodebuild -project app-ios/ShipHappens.xcodeproj -scheme ShipHappens \
  -destination 'generic/platform=iOS Simulator' -configuration Debug build
```

- [ ] **Step 3: Manual smoke test (Android emulator or iOS simulator, whichever is available)**

Launch the app and verify against `design/Parcels.dc.html`:
1. Fresh install shows the empty Active tab with the manual-add card.
2. Settings → toggle **Demo data** ON → back → seven sample parcels appear, sorted soonest-first, delivered ones at the bottom with green Delivered chips.
3. Tap a parcel → detail shows headline, delivery window, timeline with carrier-colored done/current steps, mono tracking number.
4. Swipe a delivered parcel left → archives with "Package archived" toast → Undo restores it.
5. Manual-add card: paste `1Z 999 AA1 01 2345 6784` → label flips to "NEW · UPS"; add → appears in list ("Waiting for first update" if only demo enabled — demo doesn't know this number; that's per design).
6. Copy a USPS number (e.g. `9400 1118 9922 3300 1122`, after deleting its demo parcel) elsewhere, background + foreground the app → pending-import card appears; dismiss → doesn't reappear.
7. Settings: TrackingMore card shows API-key field + Test connection ("Enter your TrackingMore API key first" toast when empty); UPS/USPS/FedEx cards show their credential fields; frequency selector persists across relaunch.

Record any deviations; fix or report them before closing out.

- [ ] **Step 4: Write `README.md`**

Cover: what the app is (one paragraph + pointer to `design/Parcels.dc.html`); module map (the Task 4 tree from the spec); how to build/run each platform (`./gradlew :app-android:assembleDebug`, XcodeGen + xcodebuild); how to run tests (Step 1's command); **how to add a tracking source** (implement `TrackingSource` in a new `source/<name>` module, expose a Koin module, add it to `appModules()` in `ui/di/AppModules.kt` — three steps, no core changes); where settings/keys live (unencrypted DataStore, by design).

- [ ] **Step 5: Final commit**

```bash
git add README.md && git commit -m "[docs] Add README with build, test, and add-a-source instructions

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Execution notes

- Tasks 2–11 are backend-only and independent of Compose; Tasks 12–17 build the UI on top; 18–20 are app shells + verification. Execute in order — each task's **Interfaces: Consumes** names its true dependencies.
- Known soft spots called out inline (all have STOP-and-report or match-the-installed-API instructions): Room 3 artifact/builder signatures (Tasks 1, 5, 8), Navigation 3 artifact version + `NavDisplay` signature (Tasks 1, 17), kotlinx-datetime accessor renames (Task 12), Koin viewModel DSL imports (Task 17), Swift name mangling (Task 19).

package com.dgmltn.shiphappens.ui.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.clipboard.ClipboardReader
import com.dgmltn.shiphappens.data.daily.DailyRefreshScheduler
import com.dgmltn.shiphappens.data.daily.NoOpDailyRefreshScheduler
import com.dgmltn.shiphappens.data.daily.NoOpStatusNotifier
import com.dgmltn.shiphappens.data.daily.StatusNotifier
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okio.Path.Companion.toPath
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.test.check.checkModules

/**
 * Koin graph sanity check: verifies every definition assembled by [appModules] can actually be
 * instantiated (no missing dependencies, no circular graphs) using Koin's `checkModules()`.
 *
 * `androidHostTest` compiles the ANDROID variant of `:ui`, so `platformDataModule()` here would
 * resolve to `PlatformDataModule.android.kt`, whose singles call `androidContext()` — there is no
 * real Android `Context` available in a JVM host test. This test substitutes a JVM-style platform
 * module (mirroring `PlatformDataModule.jvm.kt`: in-memory Room, a temp-file DataStore, a no-op
 * `ClipboardReader`) in place of `platformDataModule()`, then checks that the REST of the graph —
 * `dataModule`, all five source modules, and `uiModule` (including the parameterized
 * `DetailViewModel` factory) — resolves end to end. It mirrors the JVM platform module's full
 * binding set, so a new platform-provided dependency must be added here too.
 */
class AppModulesTest {

    private fun testPlatformDataModule(): Module = module {
        single {
            val dir = createTempDirectory("appmodulestest").toString()
            PreferenceDataStoreFactory.createWithPath { "$dir/shiphappens.preferences_pb".toPath() }
        }
        single {
            Room.inMemoryDatabaseBuilder<ShipHappensDb>()
                .setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
        }
        single<ClipboardReader> { object : ClipboardReader { override suspend fun readText(): String? = null } }
        single<StatusNotifier> { NoOpStatusNotifier }
        single<DailyRefreshScheduler> { NoOpDailyRefreshScheduler }
    }

    private fun testWebModule(): Module = module {
        single<com.dgmltn.shiphappens.source.webview.WebScraper> { com.dgmltn.shiphappens.source.webview.NoWebScraper }
        single<com.dgmltn.shiphappens.source.webview.WebCookieJar> { com.dgmltn.shiphappens.source.webview.NoOpCookieJar }
        single<com.dgmltn.shiphappens.source.webview.debug.ScrapeTracer> { com.dgmltn.shiphappens.source.webview.debug.NoOpScrapeTracer }
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun appModules_graph_resolves() {
        // uiModule's ListViewModel has an init{} block that eagerly does
        // `viewModelScope.launch { coordinator.summaries.collect {...} } }`; checkModules() below
        // constructs every definition in the graph, including it, so that launch fires here too.
        // viewModelScope dispatches via Dispatchers.Main.immediate, and this test — unlike the
        // *ViewModelTest classes — never calls Dispatchers.setMain(). Without it, that launch
        // fails with "platform dispatcher absent", and since `launch` doesn't propagate
        // synchronously, kotlinx-coroutines-test defers reporting the failure to whichever LATER
        // test next touches Dispatchers.Main — causing sporadic, seemingly-unrelated failures
        // elsewhere in the suite. Stubbing Main for the duration lets that launch dispatch
        // normally; `coordinator.summaries` never emits on its own (RefreshCoordinator only emits
        // when explicitly triggered), so the resulting collector just sits idle, no further leak.
        Dispatchers.setMain(UnconfinedTestDispatcher())

        // appModules() puts platformDataModule() then platformWebModule() first (documented
        // order); swap both for host-test-safe modules and keep the rest of the real graph as-is.
        val realModulesMinusPlatform = appModules().drop(2)

        koinApplication {
            modules(listOf(testPlatformDataModule(), testWebModule()) + realModulesMinusPlatform)
        }.checkModules()
    }
}

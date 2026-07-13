package com.shiphappens.ui.di

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.clipboard.ClipboardReader
import com.shiphappens.data.db.ShipHappensDb
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
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
 * `DetailViewModel` factory) — resolves end to end.
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
    }

    private fun testWebModule(): Module = module {
        single<com.shiphappens.source.webview.WebScraper> { com.shiphappens.source.webview.NoWebScraper }
        single<com.shiphappens.source.webview.WebCookieJar> { com.shiphappens.source.webview.NoOpCookieJar }
    }

    @Test fun appModules_graph_resolves() {
        // appModules() puts platformDataModule() then platformWebModule() first (documented
        // order); swap both for host-test-safe modules and keep the rest of the real graph as-is.
        val realModulesMinusPlatform = appModules().drop(2)

        koinApplication {
            modules(listOf(testPlatformDataModule(), testWebModule()) + realModulesMinusPlatform)
        }.checkModules()
    }
}

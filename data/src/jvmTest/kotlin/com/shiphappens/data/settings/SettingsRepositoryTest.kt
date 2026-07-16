package com.shiphappens.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shiphappens.source.api.SourceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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

    @Test fun source_config_roundtrips() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        val cfg = SourceConfig(enabled = true, values = mapOf("apiKey" to "key-1"))
        r.setSourceConfig("ups", cfg)
        assertEquals(cfg, r.settings.first().sourceConfigs["ups"])
        assertNull(r.settings.first().sourceConfigs["never-set"])
        scope.cancel()
    }

    /** Regression: two concurrent field writes to the SAME source must not clobber each other. */
    @Test fun concurrent_updates_to_same_source_both_persist() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val r = repo(scope)
        withContext(Dispatchers.Default) {  // real threads: genuinely concurrent edits
            listOf(
                async { r.updateSourceConfig("ups") { it.copy(values = it.values + ("clientId" to "abc")) } },
                async { r.updateSourceConfig("ups") { it.copy(values = it.values + ("clientSecret" to "shh")) } },
            ).awaitAll()
        }
        val cfg = r.settings.first().sourceConfigs.getValue("ups")
        assertEquals("abc", cfg["clientId"])
        assertEquals("shh", cfg["clientSecret"])
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

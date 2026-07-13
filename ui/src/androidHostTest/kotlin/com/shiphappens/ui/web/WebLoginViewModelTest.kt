package com.shiphappens.ui.web

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.source.ups.UpsWebSource
import com.shiphappens.source.webview.NoOpCookieJar
import com.shiphappens.source.webview.NoWebScraper
import com.shiphappens.source.webview.PageEvent
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

class WebLoginViewModelTest {
    private lateinit var settings: SettingsRepository
    private lateinit var vm: WebLoginViewModel

    private fun TestScope.buildVm(): WebLoginViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = createTempDirectory("weblogin").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        val registry = SourceRegistry(listOf(UpsWebSource(NoWebScraper)), settings)
        val v = WebLoginViewModel("ups", registry, settings, NoOpCookieJar)
        backgroundScope.launch { v.state.collect() }
        vm = v
        return v
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private suspend fun awaitState(predicate: (WebLoginUiState) -> Boolean): WebLoginUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first(predicate) } }

    @Test fun state_exposes_login_url() = runTest {
        buildVm()
        val s = awaitState { it.url.isNotEmpty() }
        assertTrue(s.url.contains("ups.com"))
        assertEquals("UPS", s.name)
        assertFalse(s.done)
    }

    @Test fun logged_in_event_persists_flag_and_completes() = runTest {
        buildVm()
        awaitState { it.url.isNotEmpty() }
        vm.onEvent(PageEvent.LoggedIn(true))
        assertTrue(awaitState { it.done }.done)
        val cfg = withContext(Dispatchers.Default) {
            withTimeout(10_000) { settings.settings.first { it.sourceConfigs["ups"]?.values?.get("loggedIn") == "true" } }
        }
        assertEquals("true", cfg.sourceConfigs["ups"]?.values?.get("loggedIn"))
    }

    @Test fun logged_out_event_is_ignored() = runTest {
        buildVm()
        awaitState { it.url.isNotEmpty() }
        vm.onEvent(PageEvent.LoggedIn(false))
        vm.onEvent(PageEvent.Finished("https://www.ups.com/lasso/signin"))
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(250) }
        assertFalse(vm.state.value.done)
    }
}

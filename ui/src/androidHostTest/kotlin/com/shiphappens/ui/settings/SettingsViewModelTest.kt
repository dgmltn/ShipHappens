package com.shiphappens.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.*
import com.shiphappens.data.db.ShipHappensDb
import com.shiphappens.data.settings.RefreshFrequency
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.source.ups.UpsWebSource
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
 * against the full RECORDED sequence of states (awaitRecorded). Asserted values are identical to
 * the brief's original spec.
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
        val registry = SourceRegistry(listOf(UpsWebSource(NoWebScraper)), settings)
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

    @Test fun sources_are_grouped_and_default_disabled() = runTest {
        val vm = vm()
        val s = awaitState { it.carriers.isNotEmpty() }
        assertTrue(s.universal.isEmpty())
        assertEquals(listOf("ups"), s.carriers.map { it.id })
        assertEquals("Not connected", s.carriers.single().statusText)
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

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
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        val demo = DemoSource(today = { LocalDate(2026, 7, 10) }, now = { Instant.fromEpochMilliseconds(1_752_148_800_000) })
        val registry = SourceRegistry(listOf(demo, UpsSource()), settings)
        repo = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        vm = SettingsViewModel(registry, settings, repo)
        // Records every emission and keeps WhileSubscribed alive for the whole test.
        backgroundScope.launch { vm.state.collect { check(recordedStates.tryEmit(it)) } }
        // Prime the pipeline: the first combined emission requires settings.settings' initial load.
        awaitState { it.universal.isNotEmpty() }
        return vm
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun sources_are_grouped_and_default_disabled() = runTest {
        val vm = vm()
        val s = awaitState { it.universal.isNotEmpty() && it.carriers.isNotEmpty() }
        assertEquals(listOf("demo"), s.universal.map { it.id })
        assertEquals(listOf("ups"), s.carriers.map { it.id })
        assertEquals("Not connected", s.carriers.single().statusText)
    }

    @Test fun toggle_enables_and_seeds_demo() = runTest {
        val vm = vm()
        vm.onToggle("demo")
        val s = awaitState { it.universal.singleOrNull()?.enabled == true }
        assertTrue(s.universal.single().enabled)
        assertEquals("Connected · 1,000+ couriers", s.universal.single().statusText)
        assertEquals(7, repo.observeParcels(false).first { it.size == 7 }.size)  // demo seeds flowed through
    }

    /** True once [key]'s field on the sole carrier card holds [value] — settings.current() has committed it. */
    private fun fieldCommitted(key: String, value: String): (SettingsUiState) -> Boolean =
        { it.carriers.singleOrNull()?.fields?.any { f -> f.key == key && f.value == value } == true }

    @Test fun field_edits_persist_and_change_status() = runTest {
        val vm = vm()
        vm.onToggle("ups")
        val enabled = awaitState { it.carriers.singleOrNull()?.enabled == true }
        assertEquals("Enabled · add your credentials", enabled.carriers.single().statusText)
        // Sequenced (not fired concurrently): onField does a read-modify-write of the same
        // settings key, so two in-flight calls would race and one could clobber the other.
        vm.onField("ups", "clientId", "abc")
        awaitState(predicate = fieldCommitted("clientId", "abc"))
        vm.onField("ups", "clientSecret", "shh")
        val configured = awaitState { it.carriers.singleOrNull()?.statusText == "Connected · syncing" }
        assertEquals("Connected · syncing", configured.carriers.single().statusText)
        assertEquals("abc", settings.current("ups")["clientId"])
    }

    @Test fun test_connection_toasts() = runTest {
        val vm = vm()
        vm.onTest("ups")
        assertEquals(
            "Enter UPS credentials first",
            awaitRecorded { it.toast == "Enter UPS credentials first" }.toast,
        )
        vm.onField("ups", "clientId", "a")
        awaitState(predicate = fieldCommitted("clientId", "a"))
        vm.onField("ups", "clientSecret", "b")
        awaitState(predicate = fieldCommitted("clientSecret", "b"))
        vm.onTest("ups")
        assertEquals(
            "UPS credentials look valid",
            awaitRecorded { it.toast == "UPS credentials look valid" }.toast,
        )
    }

    @Test fun sync_settings_roundtrip() = runTest {
        val vm = vm()
        vm.onAutoImport(false)
        vm.onFrequency(RefreshFrequency.ONE_HOUR)
        val s = awaitState { !it.autoImport && it.frequency == RefreshFrequency.ONE_HOUR }
        assertFalse(s.autoImport)
        assertEquals(RefreshFrequency.ONE_HOUR, s.frequency)
    }
}

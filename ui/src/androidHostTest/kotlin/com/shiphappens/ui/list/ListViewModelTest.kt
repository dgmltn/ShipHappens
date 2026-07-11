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

/**
 * Synchronization note: Room 3's Flow-returning DAO methods deliver emissions from the
 * database's own real threads, invisible to the virtual-time scheduler, so
 * `advanceUntilIdle(); vm.state.value` is racy under androidHostTest. Instead, every state
 * read AWAITS the stable post-condition of the action (real-time timeout), and transient
 * states (toasts, which the ViewModel auto-dismisses on a virtual timer) are asserted against
 * the full RECORDED sequence of states. Asserted values are identical to the original spec.
 */
class ListViewModelTest {
    private lateinit var repo: ParcelRepository
    private lateinit var clipboard: FakeClipboard
    private lateinit var manager: ClipboardImportManager
    private lateinit var settings: SettingsRepository
    private lateinit var clock: FixedClock
    private lateinit var source: FakeSource
    private lateinit var vm: ListViewModel

    /** Every state the ViewModel ever emitted, in order; replay lets awaiters see past states. */
    private val recordedStates = MutableSharedFlow<ListUiState>(replay = Int.MAX_VALUE)

    /**
     * Awaits the first current-or-future state matching [predicate] — for STABLE post-conditions
     * (card counts, tab, manualAdd fields). Runs on Dispatchers.Default so the timeout is real
     * time: a virtual-time timeout would auto-fire the moment the test scheduler goes idle while
     * Room's real threads are still working.
     */
    private suspend fun awaitState(timeoutMs: Long = 10_000, predicate: (ListUiState) -> Boolean): ListUiState =
        withContext(Dispatchers.Default) { withTimeout(timeoutMs) { vm.state.first(predicate) } }

    /**
     * Awaits a state matching [predicate] anywhere in the recorded sequence, past or future —
     * for TRANSIENT states (toasts), which may already have auto-dismissed (virtual delay) by
     * the time a stable await returns. Predicates must be unique to the step under test.
     */
    private suspend fun awaitRecorded(timeoutMs: Long = 10_000, predicate: (ListUiState) -> Boolean): ListUiState =
        withContext(Dispatchers.Default) { withTimeout(timeoutMs) { recordedStates.first(predicate) } }

    private suspend fun TestScope.vm(): ListViewModel {
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
        vm = ListViewModel(repo, manager, coordinator, clock)
        // Records every emission and keeps WhileSubscribed alive for the whole test.
        backgroundScope.launch { vm.state.collect { check(recordedStates.tryEmit(it)) } }
        // Prime the pipeline: the first combined emission requires both Room flows' initial
        // loads (real threads). After this, toast flashes always reach the state flow.
        awaitState { it.manualAdd.options.isNotEmpty() }
        return vm
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    @Test fun cards_show_ring_days_and_status() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        repo.addParcel("Keyboard", "1Z999AA10123456784", WellKnownCarriers.UPS)
        val s = awaitState { it.cards.size == 1 && it.cards.single().statusText == "In transit" }
        val card = s.cards.single()
        assertEquals("Keyboard", card.name)
        assertEquals("UPS", card.carrierName)
        assertEquals("In transit", card.statusText)
        assertEquals("2", card.ring?.number)                 // eta 7/12, today 7/10
        assertEquals(2f / 4f, card.ring!!.fraction, 0.001f)  // IN_TRANSIT = step 2
        assertFalse(card.urgent)
        assertEquals("Fri, Jul 10", s.dateLabel)
        assertEquals("1 arriving soon", s.headerSub)
    }

    @Test fun out_for_delivery_today_is_urgent_with_status_override() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        source.snapshot = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, etaDate = LocalDate(2026, 7, 10))
        repo.addParcel("Lamp", "1Z88E0330398765432", WellKnownCarriers.UPS)
        val s = awaitState { it.cards.size == 1 && it.cards.single().statusText == "Out for delivery today" }
        val card = s.cards.single()
        assertTrue(card.urgent)
        assertEquals("Out for delivery today", card.statusText)
        assertEquals("0", card.ring?.number)
    }

    @Test fun archive_shows_undo_toast_and_undo_restores() = runTest {
        val vm = vm()
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        awaitState { it.cards.size == 1 }
        vm.onArchive(added.parcel.id)
        val afterArchive = awaitState { it.cards.isEmpty() }
        assertTrue(afterArchive.cards.isEmpty())
        val toast = awaitRecorded { it.toast?.message == "Package archived" }.toast!!
        assertEquals("Package archived", toast.message)
        assertTrue(toast.showUndo)
        vm.onUndo()
        val afterUndo = awaitState { it.cards.size == 1 && it.toast == null }
        assertEquals(1, afterUndo.cards.size)
        assertNull(afterUndo.toast)
    }

    @Test fun archived_tab_shows_restore_and_empty_texts() = runTest {
        val vm = vm()
        vm.onTabSelect(ListTab.ARCHIVED)
        val empty = awaitState { it.tab == ListTab.ARCHIVED && it.emptyText != null }
        assertNotNull(empty.emptyText)
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        repo.archive(added.parcel.id)
        val s = awaitState { it.cards.size == 1 }
        assertTrue(s.cards.single().showRestore)
        assertEquals("1 package archived", s.headerSub)
    }

    @Test fun manual_add_validates_then_adds() = runTest {
        val vm = vm()
        vm.onAddManual()
        assertEquals("Enter a tracking number",
            awaitRecorded { it.toast?.message == "Enter a tracking number" }.toast?.message)
        vm.onManualTracking("ZZ!!ZZ!!ZZ!!")
        vm.onAddManual()
        assertEquals("Tap the icon to choose a carrier",
            awaitRecorded { it.toast?.message == "Tap the icon to choose a carrier" }.toast?.message)
        vm.onManualTracking("1Z999AA10123456784")   // auto-detected UPS
        vm.onManualName("Keyboard")
        vm.onAddManual()
        assertEquals("Delivery added",
            awaitRecorded { it.toast?.message == "Delivery added" }.toast?.message)
        val added = awaitState { it.cards.size == 1 && it.manualAdd.tracking.isEmpty() }
        assertEquals("Keyboard", added.cards.single().name)
        assertEquals("", added.manualAdd.tracking)
        vm.onManualTracking("1z 999 aa1 01 2345 6784")
        vm.onAddManual()
        assertEquals("That package is already in your list",
            awaitRecorded { it.toast?.message == "That package is already in your list" }.toast?.message)
    }

    @Test fun clipboard_pending_import_accept_flow() = runTest {
        val vm = vm()
        clipboard.text = "9400 1118 9922 3300 1122"
        vm.onForeground()
        val pending = awaitState { it.pendingImport != null }
        assertEquals("USPS", pending.pendingImport?.carrierName)
        vm.onPendingName("Phone case")
        vm.onAcceptPending()
        val s = awaitState { it.pendingImport == null && it.cards.size == 1 }
        assertNull(s.pendingImport)
        assertEquals("Phone case", s.cards.single().name)
    }

    @Test fun manual_picker_effective_carrier_follows_pick_then_detection() = runTest {
        val vm = vm()
        val options = awaitState { it.manualAdd.options.isNotEmpty() }
        assertEquals(listOf(null, "ups", "usps", "fedex"), options.manualAdd.options.map { it.code })
        vm.onManualTracking("1Z999AA10123456784")
        val ups = awaitState { it.manualAdd.effectiveCarrierName == "UPS" }
        assertEquals("UPS", ups.manualAdd.effectiveCarrierName)
        vm.onPickCarrier("fedex")
        val fedex = awaitState { it.manualAdd.effectiveCarrierName == "FedEx" }
        assertEquals("FedEx", fedex.manualAdd.effectiveCarrierName)
    }
}

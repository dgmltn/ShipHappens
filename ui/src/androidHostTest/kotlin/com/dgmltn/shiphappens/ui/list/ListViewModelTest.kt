package com.dgmltn.shiphappens.ui.list

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.*
import com.dgmltn.shiphappens.data.clipboard.ClipboardImportManager
import com.dgmltn.shiphappens.data.clipboard.ClipboardReader
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
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
    /** When set, track() suspends until completed — lets tests observe the mid-refresh state. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    override val descriptor = SourceDescriptor("fake", "Fake")
    override fun detectCarrier(trackingNumber: String): Carrier? = WellKnownCarriers.UPS
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        gate?.await()
        return SourceResult.Success(snapshot)
    }
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
    private lateinit var db: ShipHappensDb
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

    /** Polls the repository (real time) until [id] is actually gone — for delete-without-undo. */
    private suspend fun awaitParcelDeleted(id: String, timeoutMs: Long = 10_000) =
        withContext(Dispatchers.Default) {
            withTimeout(timeoutMs) { while (repo.observeParcel(id).first() != null) delay(50) }
        }

    private suspend fun TestScope.vm(): ListViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = kotlin.io.path.createTempDirectory("listvm").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        clock = FixedClock()
        source = FakeSource()
        val registry = SourceRegistry(listOf(source), settings)
        repo = ParcelRepository(db.parcelDao(), registry, settings, clock)
        clipboard = FakeClipboard()
        manager = ClipboardImportManager(clipboard, registry, db.parcelDao(), settings)
        val coordinator = RefreshCoordinator(repo, backgroundScope)
        vm = ListViewModel(repo, manager, coordinator, clock, registry, settings)
        // Records every emission and keeps WhileSubscribed alive for the whole test.
        backgroundScope.launch { vm.state.collect { check(recordedStates.tryEmit(it)) } }
        // Prime the pipeline: the first combined emission requires both Room flows' initial
        // loads (real threads). After this, toast flashes always reach the state flow.
        awaitState { it.manualAdd.options.isNotEmpty() }
        return vm
    }

    @AfterTest fun tearDown() {
        // `state` combines Room's observeParcels (real invalidation-tracker threads) with other
        // sources via WhileSubscribed(5_000) on viewModelScope, same shape as
        // WebDetailViewModelTest's documented flake: since ViewModel.clear() is never invoked
        // here, that scope would otherwise leak past this test, and a late real-thread emission
        // resuming on it after resetMain() crashes with "platform dispatcher absent",
        // misattributed to whichever test runs next. Cancelling viewModelScope alone isn't
        // enough — Job.cancel() doesn't wait for an already in-flight blocking Room query to
        // finish, so it can still resume after resetMain(). Closing the (never-otherwise-closed)
        // Room db shuts down its invalidation-tracker threads at the source, which is what
        // actually stops the race deterministically; cancelling the scope first avoids any
        // in-flight collector seeing a "database closed" failure as a surprise.
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    @Test fun cards_show_ring_days_and_status() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        val added = repo.addParcel("Keyboard", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        repo.refresh(added.parcel.id)
        val s = awaitState { it.cards.size == 1 && it.cards.single().statusText == "In transit" }
        val card = s.cards.single()
        assertEquals("Keyboard", card.name)
        assertEquals("UPS", card.carrierName)
        assertEquals("In transit", card.statusText)
        assertEquals(2, card.ring?.number)                   // eta 7/12, today 7/10
        assertEquals(2f / 4f, card.ring!!.fraction, 0.001f)  // IN_TRANSIT = step 2
        assertFalse(card.urgent)
        assertEquals("Fri, Jul 10", s.dateLabel)
        assertEquals("1 arriving soon", s.headerSub)
    }

    @Test fun a_delayed_card_flags_the_delay_without_losing_the_stage() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        source.snapshot = TrackingSnapshot(
            TrackingStatus.IN_TRANSIT,
            etaDate = LocalDate(2026, 7, 12),
            delayNote = "Due to weather, your package is delayed by one business day.",
        )
        val added = repo.addParcel("Keyboard", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        repo.refresh(added.parcel.id)
        val s = awaitState { it.cards.size == 1 && it.cards.single().delayed }
        val card = s.cards.single()
        // The stage still reads as the stage; the delay is a separate flag, so the card can
        // show "In transit" AND a delay chip instead of collapsing to "Delivery exception".
        assertEquals("In transit", card.statusText)
        assertTrue(card.delayed)
        assertEquals(2, card.ring?.number)  // the revised ETA still drives the ring
    }

    @Test fun an_undelayed_card_is_not_flagged() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        val added = repo.addParcel("Keyboard", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        repo.refresh(added.parcel.id)
        val s = awaitState { it.cards.size == 1 && it.cards.single().statusText == "In transit" }
        assertFalse(s.cards.single().delayed)
    }

    @Test fun out_for_delivery_shows_step_label_and_is_urgent() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        source.snapshot = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, etaDate = LocalDate(2026, 7, 10))
        val added = repo.addParcel("Lamp", "1Z88E0330398765432", WellKnownCarriers.UPS) as AddResult.Added
        repo.refresh(added.parcel.id)
        val s = awaitState { it.cards.size == 1 && it.cards.single().statusText == "Out for delivery" }
        val card = s.cards.single()
        assertTrue(card.urgent)
        assertEquals("Out for delivery", card.statusText)  // same label as the detail timeline's current step
        assertEquals(0, card.ring?.number)
    }

    @Test fun status_falls_back_to_events_when_status_unknown_matching_detail() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        source.snapshot = TrackingSnapshot(
            TrackingStatus.UNKNOWN,
            events = listOf(TrackingEvent(Instant.fromEpochMilliseconds(1_752_000_000_000), "On vehicle", status = TrackingStatus.OUT_FOR_DELIVERY)),
        )
        val added = repo.addParcel("Lamp", "1Z88E0330398765432", WellKnownCarriers.UPS) as AddResult.Added
        repo.refresh(added.parcel.id)
        // Detail's timeline marks OUT_FOR_DELIVERY current via the event fallback; the list must agree.
        val s = awaitState { it.cards.size == 1 && it.cards.single().statusText == "Out for delivery" }
        assertEquals("Out for delivery", s.cards.single().statusText)
        assertNull(s.cards.single().ring)  // no ETA → no days ring
    }

    @Test fun unrefreshed_parcel_hides_ring_and_waits_for_first_update() = runTest {
        val vm = vm()
        // Source never enabled → refresh resolves no source; parcel stays unrefreshed.
        repo.addParcel("Socks", "1Z999AA10123456784", WellKnownCarriers.UPS)
        val s = awaitState { it.cards.size == 1 }
        val card = s.cards.single()
        assertNull(card.ring)
        assertEquals("Waiting for first update", card.statusText)
        assertFalse(card.delivered)
    }

    @Test fun card_flags_sourceless_until_its_source_is_enabled() = runTest {
        val vm = vm()
        repo.addParcel("Socks", "1Z999AA10123456784", WellKnownCarriers.UPS)
        val before = awaitState { it.cards.size == 1 }
        assertTrue(before.cards.single().sourceless)  // "fake" source not enabled → nothing will refresh this
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        val after = awaitState { it.cards.singleOrNull()?.sourceless == false }
        assertFalse(after.cards.single().sourceless)  // enabling the source clears the badge live
    }

    @Test fun card_flags_refreshing_while_refresh_in_flight() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        repo.addParcel("Keyboard", "1Z999AA10123456784", WellKnownCarriers.UPS)
        awaitState { it.cards.size == 1 && !it.cards.single().refreshing }
        source.gate = kotlinx.coroutines.CompletableDeferred()
        vm.onRefresh()
        val during = awaitState { it.cards.singleOrNull()?.refreshing == true }
        assertTrue(during.cards.single().refreshing)
        source.gate!!.complete(Unit)
        val after = awaitState { it.cards.singleOrNull()?.refreshing == false && !it.isRefreshing }
        assertFalse(after.cards.single().refreshing)
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

    @Test fun archived_tab_shows_correct_header_and_empty_texts() = runTest {
        val vm = vm()
        vm.onTabSelect(ListTab.ARCHIVED)
        val empty = awaitState { it.tab == ListTab.ARCHIVED && it.emptyText != null }
        assertEquals("Nothing archived yet. Swipe a package right to archive it.", empty.emptyText)
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        repo.archive(added.parcel.id)
        val s = awaitState { it.cards.size == 1 }
        assertEquals("1 package archived", s.headerSub)
    }

    @Test fun archive_available_for_non_delivered_package() = runTest {
        val vm = vm()
        val added = repo.addParcel("Keyboard", "1Z999AA10123456784", WellKnownCarriers.UPS) as AddResult.Added
        val before = awaitState { it.cards.size == 1 }
        assertFalse(before.cards.single().delivered)  // FakeSource defaults to IN_TRANSIT
        vm.onArchive(added.parcel.id)
        val afterArchive = awaitState { it.cards.isEmpty() }
        assertTrue(afterArchive.cards.isEmpty())
        vm.onTabSelect(ListTab.ARCHIVED)
        val archivedTab = awaitState { it.tab == ListTab.ARCHIVED && it.cards.size == 1 }
        assertEquals("Keyboard", archivedTab.cards.single().name)
    }

    @Test fun restore_shows_undo_toast_and_undo_rearchives() = runTest {
        val vm = vm()
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        awaitState { it.cards.size == 1 }
        repo.archive(added.parcel.id)
        vm.onTabSelect(ListTab.ARCHIVED)
        awaitState { it.tab == ListTab.ARCHIVED && it.cards.size == 1 }
        vm.onRestore(added.parcel.id)
        val afterRestore = awaitState { it.tab == ListTab.ARCHIVED && it.cards.isEmpty() }
        assertTrue(afterRestore.cards.isEmpty())
        val toast = awaitRecorded { it.toast?.message == "Package restored" }.toast!!
        assertTrue(toast.showUndo)
        vm.onUndo()
        val afterUndo = awaitState { it.tab == ListTab.ARCHIVED && it.cards.size == 1 && it.toast == null }
        assertEquals(1, afterUndo.cards.size)
    }

    /**
     * Timing note: unlike the other awaits in this class, the "hidden, not deleted yet" check
     * here deliberately avoids a *fresh* `repo.observeParcel(id).first()` call at that exact
     * instant. A brand-new Room Flow query always requires a genuine real-thread round-trip;
     * while `runTest`'s scheduler is genuinely blocked waiting on that real completion, it
     * opportunistically drains every other pending virtual-time task too — including onDelete's
     * 3.8s grace-period job — racing straight past the very "not yet deleted" state under test
     * (verified empirically: a bare `.first()` call there jumps `testScheduler.currentTime` from
     * 0 to 3800 before it resolves, regardless of whether the call goes through
     * `withContext(Dispatchers.Default)` or not). Pre-subscribing a hot `StateFlow` to the row
     * *before* deleting sidesteps this: once primed, its cached `.value` needs no further Room
     * I/O, so reading it can't trigger that drain.
     */
    @Test fun delete_hides_immediately_and_undo_restores_it() = runTest {
        val vm = vm()
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        awaitState { it.cards.size == 1 }
        val parcelRow = repo.observeParcel(added.parcel.id).stateIn(backgroundScope, SharingStarted.Eagerly, null)
        withContext(Dispatchers.Default) { withTimeout(10_000) { parcelRow.first { it != null } } }

        vm.onDelete(added.parcel.id)
        testScheduler.runCurrent()
        val afterDelete = vm.state.value
        assertTrue(afterDelete.cards.isEmpty())
        val toast = afterDelete.toast!!
        assertEquals("Package deleted", toast.message)
        assertTrue(toast.showUndo)
        assertNotNull(parcelRow.value)  // hidden, not deleted yet — cached pre-delete row, no fresh Room I/O
        vm.onUndo()
        val afterUndo = awaitState { it.cards.size == 1 && it.toast == null }
        assertEquals(1, afterUndo.cards.size)
        assertNotNull(repo.observeParcel(added.parcel.id).first())
    }

    @Test fun delete_without_undo_removes_parcel_after_grace_period() = runTest {
        val vm = vm()
        val added = repo.addParcel("Beans", "9400111899223197428", WellKnownCarriers.USPS) as AddResult.Added
        awaitState { it.cards.size == 1 }
        vm.onDelete(added.parcel.id)
        awaitState { it.cards.isEmpty() }
        awaitParcelDeleted(added.parcel.id)
        assertNull(repo.observeParcel(added.parcel.id).first())
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

    @Test fun manual_add_clears_card_as_soon_as_row_appears_not_after_first_refresh() = runTest {
        val vm = vm()
        settings.setSourceConfig("fake", SourceConfig(enabled = true))
        source.gate = kotlinx.coroutines.CompletableDeferred()  // hold the first refresh in flight
        vm.onManualTracking("1Z999AA10123456784")
        vm.onManualName("Keyboard")
        vm.onAddManual()
        // The row exists but the first refresh hasn't finished: the card must already be reset.
        val during = awaitState { it.cards.size == 1 && it.manualAdd.tracking.isEmpty() }
        assertEquals("", during.manualAdd.name)
        assertNull(during.manualAdd.effectiveCarrierName)
        awaitRecorded { it.toast?.message == "Delivery added" }
        source.gate!!.complete(Unit)
        // The add still triggers the first refresh once unblocked.
        awaitState { it.cards.singleOrNull()?.statusText == "In transit" }
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
        assertEquals(listOf(null, "ups", "usps", "fedex", "amazon", "amzl"), options.manualAdd.options.map { it.code })
        vm.onManualTracking("1Z999AA10123456784")
        val ups = awaitState { it.manualAdd.effectiveCarrierName == "UPS" }
        assertEquals("UPS", ups.manualAdd.effectiveCarrierName)
        vm.onPickCarrier("fedex")
        val fedex = awaitState { it.manualAdd.effectiveCarrierName == "FedEx" }
        assertEquals("FedEx", fedex.manualAdd.effectiveCarrierName)
    }
}

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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.test.*

/**
 * Synchronization note: see ListViewModelTest (Task 13) — Room 3's Flow-returning DAO methods
 * deliver emissions from the database's own real threads, invisible to the virtual-time
 * scheduler, so `advanceUntilIdle(); vm.state.value` is racy under androidHostTest. Instead,
 * every state read AWAITS the stable post-condition of the action (real-time timeout via
 * Dispatchers.Default + withTimeout), gated on the exact field(s) the assertions depend on so a
 * partially-joined emission (e.g. parcel row written but events not yet replaced) can't be
 * mistaken for the final state. Asserted values are identical to the original spec.
 */
class DetailViewModelTest {
    private class FixedClock : AppClock {
        override fun now() = Instant.fromEpochMilliseconds(1_752_148_800_000)
        override fun today() = LocalDate(2026, 7, 10)
    }

    private lateinit var db: ShipHappensDb
    private lateinit var vm: DetailViewModel

    private suspend fun awaitState(timeoutMs: Long = 10_000, predicate: (DetailUiState) -> Boolean): DetailUiState =
        withContext(Dispatchers.Default) { withTimeout(timeoutMs) { vm.state.first(predicate) } }

    private fun TestScope.vm(parcel: Parcel): DetailViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = kotlin.io.path.createTempDirectory("detail").toString()
        val settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        val repo = ParcelRepository(db.parcelDao(), SourceRegistry(emptyList(), settings), settings, FixedClock())
        val v = DetailViewModel(parcel.id, repo, FixedClock())
        backgroundScope.launch { v.state.collect() }
        vm = v
        return v
    }

    @AfterTest fun tearDown() { Dispatchers.resetMain() }

    private fun base(status: TrackingStatus, eta: LocalDate?) = Parcel(
        id = "p1", name = "Trail running shoes", trackingNumber = "FX 8823 0199 4422",
        carrier = WellKnownCarriers.FEDEX, status = status, etaDate = eta, etaTime = LocalTime(21, 0),
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    @Test fun arrives_tomorrow_headline_and_window() = runTest {
        val p = base(TrackingStatus.OUT_FOR_DELIVERY, LocalDate(2026, 7, 11))
        vm(p)
        db.parcelDao().upsertParcel(p.toEntity())
        val s = awaitState { it.loaded }
        assertTrue(s.loaded)
        assertEquals("Arrives tomorrow", s.headline)
        assertEquals("Estimated delivery", s.windowLabel)
        assertEquals("Sat, Jul 11 · by 9:00 PM", s.windowText)
        assertEquals("FedEx", s.carrierName)
    }

    @Test fun timeline_marks_done_current_todo() = runTest {
        val ev = TrackingEvent(Instant.fromEpochMilliseconds(1_752_000_000_000), "In transit", "Memphis, TN", TrackingStatus.IN_TRANSIT)
        val p = base(TrackingStatus.IN_TRANSIT, LocalDate(2026, 7, 14)).copy(events = listOf(ev))
        vm(p)
        db.parcelDao().upsertParcel(p.toEntity())
        db.parcelDao().replaceEvents("p1", p.events.map { it.toEntity("p1") })
        // Gate on the CURRENT step's event-derived time text — the parcel row alone (before
        // replaceEvents lands) already yields loaded=true and timeline.size==5, so those two
        // conditions alone would race; this waits for the events join to actually be visible.
        val s = awaitState { it.loaded && it.timeline.size == 5 && it.timeline[2].time?.endsWith("latest update") == true }
        val t = s.timeline
        assertEquals(5, t.size)
        assertEquals(listOf(StepState.DONE, StepState.DONE, StepState.CURRENT, StepState.TODO, StepState.TODO), t.map { it.state })
        assertTrue(t[2].time!!.endsWith("· latest update"))
        assertEquals("Memphis, TN", s.locationText)
    }

    @Test fun delivered_shows_delivered_window_and_full_timeline() = runTest {
        val delivered = TrackingEvent(Instant.parse("2026-07-08T19:14:00Z"), "Delivered", "Front porch", TrackingStatus.DELIVERED)
        val p = base(TrackingStatus.DELIVERED, LocalDate(2026, 7, 8)).copy(events = listOf(delivered))
        vm(p)
        db.parcelDao().upsertParcel(p.toEntity())
        db.parcelDao().replaceEvents("p1", p.events.map { it.toEntity("p1") })
        // Delivered status alone (present the moment the parcel row lands) already drives
        // headline, windowLabel, and "all DONE" timeline state — no event-timing dependency, so
        // per the brief this test asserts no clock/timezone-sensitive string.
        val s = awaitState { it.loaded }
        assertEquals("Delivered", s.headline)
        assertEquals("Delivered", s.windowLabel)
        assertTrue(s.timeline.all { it.state == StepState.DONE })
    }

    @Test fun arriving_today_and_in_n_days() = runTest {
        val today = base(TrackingStatus.OUT_FOR_DELIVERY, LocalDate(2026, 7, 10))
        vm(today)
        db.parcelDao().upsertParcel(today.toEntity())
        val first = awaitState { it.loaded && it.headline == "Arriving today" }
        assertEquals("Arriving today", first.headline)
        db.parcelDao().upsertParcel(base(TrackingStatus.IN_TRANSIT, LocalDate(2026, 7, 14)).toEntity())
        val second = awaitState { it.headline == "Arrives in 4 days" }
        assertEquals("Arrives in 4 days", second.headline)
    }
}

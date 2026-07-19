package com.shiphappens.ui.detail

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.viewModelScope
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.*
import com.shiphappens.data.db.ShipHappensDb
import com.shiphappens.data.db.toEntity
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.shiphappens.ui.util.design12h
import com.shiphappens.ui.util.designFormat
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
        val registry = SourceRegistry(listOf(com.shiphappens.source.ups.UpsWebSource(com.shiphappens.source.webview.NoWebScraper)), settings)
        val repo = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        val v = DetailViewModel(parcel.id, repo, FixedClock(), registry)
        backgroundScope.launch { v.state.collect() }
        vm = v
        return v
    }

    @AfterTest fun tearDown() {
        // `state` maps Room's observeParcel (real invalidation-tracker threads) via
        // WhileSubscribed(5_000) on viewModelScope — the same shape as WebDetailViewModelTest's
        // documented flake, just with one real-thread source instead of two (lower odds, not
        // zero — this class was still an observed contributor to the full-suite flake).
        // ViewModel.clear() is never invoked here, so viewModelScope (Dispatchers.Main) would
        // otherwise leak past this test, and a late real-thread emission resuming on it after
        // resetMain() crashes with "platform dispatcher absent", misattributed to whichever test
        // runs next. Cancelling viewModelScope alone isn't enough — Job.cancel() doesn't wait
        // for an already in-flight blocking Room query to finish, so it can still resume after
        // resetMain(). Closing the (never-otherwise-closed) Room db shuts down its
        // invalidation-tracker threads at the source, which is what actually stops the race
        // deterministically; cancelling the scope first avoids any in-flight collector seeing a
        // "database closed" failure as a surprise.
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

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

    @Test fun delivered_window_shows_actual_delivery_time_from_event() = runTest {
        val deliveredAt = Instant.parse("2026-07-11T19:12:00Z")
        val delivered = TrackingEvent(deliveredAt, "Delivered, Front Door/Porch", "CARLSBAD, CA 92009", TrackingStatus.DELIVERED)
        // No ETA at all — the USPS DOM-scrape shape (delivered pages carry no expected-delivery block).
        val p = base(TrackingStatus.DELIVERED, eta = null).copy(etaTime = null, events = listOf(delivered))
        vm(p)
        db.parcelDao().upsertParcel(p.toEntity())
        db.parcelDao().replaceEvents("p1", p.events.map { it.toEntity("p1") })
        // Expected string computed with the same tz/formatters as production, so the assertion
        // is timezone-agnostic; gating on it also waits out the events-join race.
        val ldt = deliveredAt.toLocalDateTime(TimeZone.currentSystemDefault())
        val expected = "${ldt.date.designFormat()} · ${ldt.time.design12h()}"
        val s = awaitState { it.loaded && it.windowText == expected }
        assertEquals("Delivered", s.windowLabel)
        assertEquals(expected, s.windowText)
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

    @Test fun in_transit_without_eta_shows_status_not_waiting() = runTest {
        // Amazon commonly reports status IN_TRANSIT with no etaDate. The top-bar headline must
        // reflect the real status ("In transit"), matching the home card and the timeline — not
        // fall through to "Waiting for first update", which is reserved for never-refreshed parcels.
        val p = base(TrackingStatus.IN_TRANSIT, eta = null).copy(
            carrier = WellKnownCarriers.AMAZON, lastRefreshedAt = Instant.fromEpochMilliseconds(1_752_100_000_000),
        )
        vm(p)
        db.parcelDao().upsertParcel(p.toEntity())
        val s = awaitState { it.loaded }
        assertEquals("In transit", s.headline)
    }

    @Test fun never_refreshed_unknown_still_waits() = runTest {
        // Genuinely no tracking data yet: UNKNOWN status, never refreshed, no ETA.
        val p = base(TrackingStatus.UNKNOWN, eta = null).copy(etaTime = null)
        vm(p)
        db.parcelDao().upsertParcel(p.toEntity())
        val s = awaitState { it.loaded }
        assertEquals("Waiting for first update", s.headline)
    }

    @Test fun web_button_shows_only_for_web_capable_carrier() = runTest {
        val ups = base(TrackingStatus.IN_TRANSIT, LocalDate(2026, 7, 14)).copy(carrier = WellKnownCarriers.UPS)
        vm(ups)
        db.parcelDao().upsertParcel(ups.toEntity())
        assertEquals("UPS", awaitState { it.loaded }.webCarrierName)
    }
}

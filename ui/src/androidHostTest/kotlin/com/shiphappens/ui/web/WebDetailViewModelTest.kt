package com.shiphappens.ui.web

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.shiphappens.data.*
import com.shiphappens.data.db.ShipHappensDb
import com.shiphappens.data.db.toEntity
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.domain.*
import com.shiphappens.source.ups.UpsWebSource
import com.shiphappens.source.webview.NoWebScraper
import androidx.lifecycle.viewModelScope
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath

class WebDetailViewModelTest {
    private class FixedClock : AppClock {
        override fun now() = Instant.fromEpochMilliseconds(1_752_300_000_000)
        override fun today() = LocalDate(2026, 7, 12)
    }

    private lateinit var db: ShipHappensDb
    private lateinit var repo: ParcelRepository
    private lateinit var vm: WebDetailViewModel

    private val parcel = Parcel(
        id = "p1", name = "Web parcel", trackingNumber = "1Z999AA10123456784",
        carrier = WellKnownCarriers.UPS, createdAt = Instant.fromEpochMilliseconds(0),
    )

    private fun TestScope.buildVm(): WebDetailViewModel {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val dir = createTempDirectory("webdetail").toString()
        val settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { "$dir/s.preferences_pb".toPath() })
        db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        val registry = SourceRegistry(listOf(UpsWebSource(NoWebScraper)), settings)
        repo = ParcelRepository(db.parcelDao(), registry, settings, FixedClock())
        val v = WebDetailViewModel(parcel.id, repo, registry, settings)
        backgroundScope.launch { v.state.collect() }
        vm = v
        return v
    }

    @AfterTest fun tearDown() {
        // `state` combines Room's observeParcel (real invalidation-tracker threads) with
        // settings.settings (real DataStore dispatcher) via WhileSubscribed(5_000) on
        // viewModelScope. The test's only subscriber is the backgroundScope collector below,
        // which runTest cancels when the test body returns — but WhileSubscribed keeps the
        // upstream flows hot afterward, and ViewModel.clear() is never invoked here, so
        // viewModelScope (Dispatchers.Main) would otherwise leak past this test. A late
        // real-thread emission resuming on it after resetMain() crashes with "platform
        // dispatcher absent", misattributed to whichever test runs next. Cancelling
        // viewModelScope alone isn't enough — Job.cancel() doesn't wait for an already
        // in-flight blocking Room query to finish, so it can still resume after resetMain().
        // Closing the (never-otherwise-closed) Room db shuts down its invalidation-tracker
        // threads at the source, which is what actually stops the race deterministically;
        // cancelling the scope first avoids any in-flight collector seeing a "database closed"
        // failure as a surprise.
        if (::vm.isInitialized) vm.viewModelScope.cancel()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
    }

    private suspend fun awaitState(predicate: (WebDetailUiState) -> Boolean): WebDetailUiState =
        withContext(Dispatchers.Default) { withTimeout(10_000) { vm.state.first(predicate) } }

    @Test fun state_exposes_ups_tracking_url_and_spec() = runTest {
        buildVm()
        db.parcelDao().upsertParcel(parcel.toEntity())
        val s = awaitState { it.loaded }
        assertEquals("UPS", s.carrierName)
        assertTrue(s.url.contains("ups.com/track"))
        assertTrue(s.url.contains("1Z999AA10123456784"))
        assertNotNull(s.spec)
        assertTrue(s.showLoginHint)  // not signed in yet
    }

    @Test fun tracking_payload_is_applied_to_repository() = runTest {
        buildVm()
        db.parcelDao().upsertParcel(parcel.toEntity())
        awaitState { it.loaded }
        val job = vm.onPayload("""{"kind":"dom","body":"{\"page\":\"ok\",\"tracking\":{\"status\":\"OUT_FOR_DELIVERY\",\"location\":\"Memphis, TN\"}}"}""")
        // Join the write coroutine before the test can return — otherwise it may still be
        // resuming onto Dispatchers.Main after tearDown's resetMain(), crashing a later test.
        withContext(Dispatchers.Default) { withTimeout(10_000) { assertNotNull(job).join() } }
        val updated = withContext(Dispatchers.Default) {
            withTimeout(10_000) { repo.observeParcel("p1").first { it?.status == TrackingStatus.OUT_FOR_DELIVERY } }
        }
        assertEquals("Memphis, TN", assertNotNull(updated).latestLocation)
        assertEquals("ups", updated.sourceId)
    }

    @Test fun non_tracking_payload_changes_nothing() = runTest {
        buildVm()
        db.parcelDao().upsertParcel(parcel.toEntity())
        awaitState { it.loaded }
        // Both return null (no write launched) — nothing is left in flight when the test returns.
        assertNull(vm.onPayload("garbage"))
        assertNull(vm.onPayload("""{"kind":"dom","body":"{\"page\":\"challenge\"}"}"""))
        // Give writes (if any, wrongly) a chance to land, then confirm status unchanged.
        withContext(Dispatchers.Default) { kotlinx.coroutines.delay(250) }
        assertEquals(TrackingStatus.UNKNOWN, assertNotNull(db.parcelDao().getById("p1")).parcel.status.let { TrackingStatus.valueOf(it) })
    }
}

package com.dgmltn.shiphappens.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.FakeSource
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import okio.Path.Companion.toPath
import kotlin.test.*

class ParcelChangeTest {
    private lateinit var settings: SettingsRepository

    private fun repo(
        scope: CoroutineScope,
        vararg sources: TrackingSource,
        clock: FixedClock = FixedClock(),
    ): ParcelRepository {
        val dir = kotlin.io.path.createTempDirectory("change").toString()
        settings = SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
        val db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build()
        return ParcelRepository(db.parcelDao(), SourceRegistry(sources.toList(), settings), settings, clock)
    }

    private suspend fun enable(id: String) = settings.setSourceConfig(id, SourceConfig(enabled = true))

    @Test fun status_transition_is_notable() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = repo(scope, src)
        enable("u")
        val added = r.addParcel("Keyboard", "1Z999AA10123456784", null) as AddResult.Added
        r.refreshAll(force = true)  // UNKNOWN -> IN_TRANSIT

        src.trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY))
        val summary = r.refreshAll(force = true)

        val change = summary.changes.single { it.parcelId == added.parcel.id }
        assertEquals(TrackingStatus.IN_TRANSIT, change.statusBefore)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, change.statusAfter)
        assertTrue(change.statusChanged)
        assertTrue(change.isNotable)
        assertEquals("Keyboard", change.parcelName)
    }

    @Test fun identical_snapshot_is_not_notable() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)))
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.refreshAll(force = true)

        val summary = r.refreshAll(force = true)

        assertEquals(1, summary.changes.size)
        assertFalse(summary.changes.single().isNotable)
    }

    @Test fun eta_date_shift_is_notable_without_status_change() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(
                TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 8, 19))))
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.refreshAll(force = true)

        src.trackResult = SourceResult.Success(
            TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 8, 21)))
        val change = r.refreshAll(force = true).changes.single()

        assertFalse(change.statusChanged)
        assertTrue(change.etaChanged)
        assertTrue(change.isNotable)
        assertEquals(LocalDate(2026, 8, 19), change.etaBefore)
        assertEquals(LocalDate(2026, 8, 21), change.etaAfter)
    }

    @Test fun unknown_status_after_is_never_notable() {
        val change = ParcelChange("p1", "Keyboard", TrackingStatus.UNKNOWN, TrackingStatus.UNKNOWN, null, null,
            checkedOn = LocalDate(2026, 9, 6))
        assertFalse(change.isNotable)
    }

    @Test fun auth_failure_is_reported_with_its_source_id() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Failure(FailureReason.AUTH, "session expired"))
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)

        val summary = r.refreshAll(force = true)

        assertEquals(setOf("u"), summary.authFailedSourceIds)
        assertTrue(summary.changes.isEmpty())
        assertEquals(1, summary.failed)
    }

    @Test fun success_reports_its_source_id_for_nag_clearing() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val src = FakeSource("u", detects = WellKnownCarriers.UPS)
        val r = repo(scope, src)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)

        assertEquals(setOf("u"), r.refreshAll(force = true).succeededSourceIds)
    }

    @Test fun a_newly_delayed_parcel_is_notable_even_when_stage_and_eta_hold() {
        // The whole point of the feature: UPS can announce a delay before it moves the date.
        val change = ParcelChange(
            parcelId = "p1", parcelName = "Boots",
            statusBefore = TrackingStatus.IN_TRANSIT, statusAfter = TrackingStatus.IN_TRANSIT,
            etaBefore = LocalDate(2026, 8, 29), etaAfter = LocalDate(2026, 8, 29),
            checkedOn = LocalDate(2026, 8, 20), previouslyCheckedOn = LocalDate(2026, 8, 19),
            delayNoteBefore = null, delayNoteAfter = "Due to weather, delayed by one business day.",
        )
        assertTrue(change.becameDelayed)
        assertTrue(change.isNotable)
    }

    @Test fun an_unchanged_delay_is_not_notable_on_its_own() {
        // Don't re-notify every daily run for the duration of a delay.
        val change = ParcelChange(
            parcelId = "p1", parcelName = "Boots",
            statusBefore = TrackingStatus.IN_TRANSIT, statusAfter = TrackingStatus.IN_TRANSIT,
            etaBefore = LocalDate(2026, 8, 29), etaAfter = LocalDate(2026, 8, 29),
            checkedOn = LocalDate(2026, 8, 20), previouslyCheckedOn = LocalDate(2026, 8, 19),
            delayNoteBefore = "Delayed by weather", delayNoteAfter = "Delayed by weather",
        )
        assertFalse(change.becameDelayed)
        assertFalse(change.isNotable)
    }

    @Test fun a_resolved_delay_is_not_reported_as_a_new_delay() {
        val change = ParcelChange(
            parcelId = "p1", parcelName = "Boots",
            statusBefore = TrackingStatus.IN_TRANSIT, statusAfter = TrackingStatus.IN_TRANSIT,
            etaBefore = LocalDate(2026, 8, 29), etaAfter = LocalDate(2026, 8, 29),
            checkedOn = LocalDate(2026, 8, 20), previouslyCheckedOn = LocalDate(2026, 8, 19),
            delayNoteBefore = "Delayed by weather", delayNoteAfter = null,
        )
        assertFalse(change.becameDelayed)
    }

    /**
     * The imminence rule: the ETA holds still, the calendar moves. Every case below shares one
     * unchanged date and differs only in when the two checks happened.
     */
    private fun steady(
        eta: LocalDate?,
        checkedOn: LocalDate,
        previouslyCheckedOn: LocalDate?,
    ) = ParcelChange(
        parcelId = "p1", parcelName = "Boots",
        statusBefore = TrackingStatus.IN_TRANSIT, statusAfter = TrackingStatus.IN_TRANSIT,
        etaBefore = eta, etaAfter = eta,
        checkedOn = checkedOn, previouslyCheckedOn = previouslyCheckedOn,
    )

    @Test fun an_eta_that_has_become_tomorrow_is_notable_though_the_date_never_moved() {
        val change = steady(LocalDate(2026, 9, 11), LocalDate(2026, 9, 10), LocalDate(2026, 9, 9))
        assertFalse(change.etaChanged)
        assertFalse(change.statusChanged)
        assertTrue(change.becameImminent)
        assertTrue(change.isNotable)
    }

    @Test fun an_eta_that_has_become_today_is_notable() {
        val change = steady(LocalDate(2026, 9, 11), LocalDate(2026, 9, 11), LocalDate(2026, 9, 10))
        assertEquals(Imminence.TODAY, change.imminenceAfter)
        assertTrue(change.isNotable)
    }

    @Test fun the_first_day_past_due_is_notable_and_the_next_one_is_not() {
        val slipped = steady(LocalDate(2026, 9, 11), LocalDate(2026, 9, 12), LocalDate(2026, 9, 11))
        assertTrue(slipped.isNotable)

        val stillLate = steady(LocalDate(2026, 9, 11), LocalDate(2026, 9, 13), LocalDate(2026, 9, 12))
        assertFalse(stillLate.isNotable)
    }

    @Test fun a_second_check_on_the_same_day_does_not_re_announce() {
        // Both halves land in the same band, so there is no crossing to report.
        val change = steady(LocalDate(2026, 9, 11), LocalDate(2026, 9, 11), LocalDate(2026, 9, 11))
        assertFalse(change.becameImminent)
        assertFalse(change.isNotable)
    }

    @Test fun a_first_ever_check_announces_nothing_on_imminence_alone() {
        // No previous check to have crossed FROM — adding a parcel due today isn't news.
        val change = steady(LocalDate(2026, 9, 11), LocalDate(2026, 9, 11), null)
        assertFalse(change.becameImminent)
        assertFalse(change.isNotable)
    }

    @Test fun drifting_into_the_this_week_band_stays_quiet() {
        // A week out becoming six days out is just time passing; announcing it daily trains swipes.
        val change = steady(LocalDate(2026, 9, 11), LocalDate(2026, 9, 5), LocalDate(2026, 9, 4))
        assertEquals(Imminence.THIS_WEEK, change.imminenceAfter)
        assertFalse(change.isNotable)
    }

    @Test fun a_parcel_with_no_eta_has_no_imminence_to_cross() {
        val change = steady(null, LocalDate(2026, 9, 11), LocalDate(2026, 9, 10))
        assertNull(change.imminenceAfter)
        assertFalse(change.isNotable)
    }

    @Test fun the_daily_pass_reports_a_crossing_the_carrier_did_not_cause() = runTest {
        // End-to-end through the repository: same snapshot both days, different day.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val eta = LocalDate(2026, 7, 11)
        val src = FakeSource("u", detects = WellKnownCarriers.UPS,
            trackResult = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = eta)))
        val clock = FixedClock()  // 2026-07-10
        val r = repo(scope, src, clock = clock)
        enable("u")
        r.addParcel("Keyboard", "1Z999AA10123456784", null)
        r.refreshAll(force = true)

        clock.date = LocalDate(2026, 7, 11)
        clock.instant = Instant.fromEpochMilliseconds(1_783_771_200_000)  // 2026-07-11T12:00Z
        val change = r.refreshAll(force = true).changes.single()

        assertFalse(change.etaChanged)
        assertEquals(LocalDate(2026, 7, 10), change.previouslyCheckedOn)
        assertEquals(Imminence.TODAY, change.imminenceAfter)
        assertTrue(change.isNotable)
    }
}

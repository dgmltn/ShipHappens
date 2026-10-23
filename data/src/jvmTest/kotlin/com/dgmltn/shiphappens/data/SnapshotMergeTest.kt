package com.dgmltn.shiphappens.data

import com.dgmltn.shiphappens.domain.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SnapshotMergeTest {
    private val amzl = WellKnownCarriers.AMAZON_LOGISTICS
    private fun ev(iso: String, desc: String, loc: String? = null, st: TrackingStatus? = null, code: String? = null) =
        TrackingEvent(Instant.parse(iso), desc, loc, st, code)

    @Test fun secondary_events_are_tagged_and_interleaved_by_time() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-22T10:00:00Z", "Shipped", st = TrackingStatus.SHIPPED)))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T10:00:00Z", "Arrived at facility", "Vista, US", TrackingStatus.IN_TRANSIT)))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(listOf(null, "amzl"), m.events.map { it.carrierCode })
        assertEquals(listOf("Shipped", "Arrived at facility"), m.events.map { it.description })
    }

    @Test fun same_minute_same_stage_rows_keep_the_secondarys_wording() {
        val p = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, events = listOf(ev("2026-10-23T10:57:20Z", "Package is out for delivery.", "Vista, US", TrackingStatus.OUT_FOR_DELIVERY)))
        val s = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, events = listOf(ev("2026-10-23T10:57:00Z", "Out for delivery", "Vista, US", TrackingStatus.OUT_FOR_DELIVERY)))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(listOf("Out for delivery"), m.events.map { it.description })
        assertEquals("amzl", m.events.single().carrierCode)
    }

    @Test fun a_duplicate_keeps_the_primarys_row_when_only_it_has_a_location() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T03:03:00Z", "Package arrived at a carrier facility.", "Vista, US", TrackingStatus.IN_TRANSIT)))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T03:03:40Z", "Arrived at carrier facility", null, TrackingStatus.IN_TRANSIT)))
        assertEquals("Vista, US", mergeSnapshots(p, s, amzl).events.single().location)
    }

    @Test fun equal_descriptions_ignoring_case_and_punctuation_within_the_hour_are_duplicates() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T03:00:00Z", "Package left the shipper facility.")))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T03:40:00Z", "package left the shipper facility")))
        assertEquals(1, mergeSnapshots(p, s, amzl).events.size)
    }

    @Test fun the_same_wording_hours_apart_or_at_another_place_is_another_scan() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-22T03:00:00Z", "Package arrived at a carrier facility.", "Vista, US", TrackingStatus.IN_TRANSIT)))
        val later = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-24T03:00:00Z", "Package arrived at a carrier facility.", "Vista, US", TrackingStatus.IN_TRANSIT)))
        val elsewhere = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-22T03:20:00Z", "Package arrived at a carrier facility.", "San Diego, CA", TrackingStatus.IN_TRANSIT)))
        assertEquals(2, mergeSnapshots(p, later, amzl).events.size)
        assertEquals(2, mergeSnapshots(p, elsewhere, amzl).events.size)
    }

    @Test fun one_carriers_own_repeated_wording_at_different_hubs_is_kept() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(
            ev("2026-10-22T03:00:00Z", "Processed through sort facility", "Whitestown, IN, US", TrackingStatus.IN_TRANSIT),
            ev("2026-10-22T03:30:00Z", "Processed through sort facility", "Compton, CA, US", TrackingStatus.IN_TRANSIT),
        ))
        assertEquals(2, mergeSnapshots(p, TrackingSnapshot(TrackingStatus.UNKNOWN), amzl).events.size)
        assertEquals(2, mergeSnapshots(TrackingSnapshot(TrackingStatus.UNKNOWN), p, amzl).events.size)
    }

    @Test fun the_side_with_the_newest_scan_wins_status_location_and_delay() {
        val p = TrackingSnapshot(TrackingStatus.SHIPPED, latestLocation = "Seattle, WA", delayNote = "Running late",
            events = listOf(ev("2026-10-22T10:00:00Z", "Shipped", st = TrackingStatus.SHIPPED)))
        val s = TrackingSnapshot(TrackingStatus.OUT_FOR_DELIVERY, latestLocation = "Vista, US",
            events = listOf(ev("2026-10-23T10:00:00Z", "Out for delivery", "Vista, US", TrackingStatus.OUT_FOR_DELIVERY)))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, m.status)
        assertEquals("Vista, US", m.latestLocation)
        // Delay wording mostly comes from a headline, not a scan, so the other side's still fills in.
        assertEquals("Running late", m.delayNote)
    }

    @Test fun an_unknown_status_on_the_newest_side_defers_to_the_other() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-22T10:00:00Z", "Shipped")))
        val s = TrackingSnapshot(TrackingStatus.UNKNOWN, events = listOf(ev("2026-10-23T10:00:00Z", "Scan")))
        assertEquals(TrackingStatus.IN_TRANSIT, mergeSnapshots(p, s, amzl).status)
    }

    @Test fun delivered_on_either_side_always_wins() {
        val p = TrackingSnapshot(TrackingStatus.DELIVERED, events = listOf(ev("2026-10-22T10:00:00Z", "Delivered", st = TrackingStatus.DELIVERED)))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, events = listOf(ev("2026-10-23T10:00:00Z", "In transit", st = TrackingStatus.IN_TRANSIT)))
        assertEquals(TrackingStatus.DELIVERED, mergeSnapshots(p, s, amzl).status)
        assertEquals(TrackingStatus.DELIVERED, mergeSnapshots(s, p, amzl).status)
    }

    @Test fun no_events_on_either_side_means_the_primary_wins() {
        val m = mergeSnapshots(TrackingSnapshot(TrackingStatus.SHIPPED, latestLocation = "A"), TrackingSnapshot(TrackingStatus.IN_TRANSIT, latestLocation = "B"), amzl)
        assertEquals(TrackingStatus.SHIPPED, m.status)
        assertEquals("A", m.latestLocation)
    }

    @Test fun the_secondarys_eta_and_window_win_when_it_has_a_date() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 10, 24), etaWindowEnd = LocalTime(20, 0))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 10, 23), etaWindowStart = LocalTime(12, 45), etaWindowEnd = LocalTime(16, 45))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(LocalDate(2026, 10, 23), m.etaDate)
        assertEquals(LocalTime(12, 45), m.etaWindowStart)
        assertEquals(LocalTime(16, 45), m.etaWindowEnd)
    }

    @Test fun without_a_secondary_date_the_primarys_eta_and_window_are_kept_together() {
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 10, 24), etaWindowEnd = LocalTime(20, 0))
        val s = TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaWindowStart = LocalTime(9, 0), etaWindowEnd = LocalTime(12, 0))
        val m = mergeSnapshots(p, s, amzl)
        assertEquals(LocalDate(2026, 10, 24), m.etaDate)
        assertNull(m.etaWindowStart)
        assertEquals(LocalTime(20, 0), m.etaWindowEnd)
    }

    @Test fun the_handoff_is_the_primarys() {
        val h = Handoff(amzl, "TBA1")
        val p = TrackingSnapshot(TrackingStatus.IN_TRANSIT, handoff = h)
        assertEquals(h, mergeSnapshots(p, TrackingSnapshot(TrackingStatus.IN_TRANSIT), amzl).handoff)
    }
}

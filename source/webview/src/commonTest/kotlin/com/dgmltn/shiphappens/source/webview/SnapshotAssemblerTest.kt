package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class SnapshotAssemblerTest {
    private val v = StatusVocabulary()
    private fun at(iso: String) = Instant.parse(iso)
    private val older = TrackingEvent(at("2026-08-17T09:00:00Z"), "Picked up", "SANTA CLARA, CA", TrackingStatus.SHIPPED)
    private val newer = TrackingEvent(at("2026-08-18T14:33:00Z"), "Departed FedEx location", "MEMPHIS, TN", TrackingStatus.IN_TRANSIT)
    private val unclassified = TrackingEvent(at("2026-08-19T08:00:00Z"), "Delivery date pending", null, null)

    @Test fun headline_wins_over_events_and_events_sort_ascending() {
        val s = assembleSnapshot(v, "Out for delivery", events = listOf(newer, older))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, s.status)
        assertEquals(listOf(older, newer), s.events)
    }

    @Test fun unreadable_headline_falls_back_to_the_newest_classifiable_event() {
        val s = assembleSnapshot(v, "Your order", events = listOf(older, newer, unclassified))
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
    }

    @Test fun status_fallback_is_used_only_when_nothing_classifies() {
        assertEquals(TrackingStatus.LABEL_CREATED, assembleSnapshot(v, "x", statusFallback = TrackingStatus.LABEL_CREATED).status)
        assertEquals(TrackingStatus.IN_TRANSIT, assembleSnapshot(v, "x", events = listOf(newer), statusFallback = TrackingStatus.LABEL_CREATED).status)
        assertEquals(TrackingStatus.UNKNOWN, assembleSnapshot(v, "x").status)
        assertEquals(TrackingStatus.UNKNOWN, assembleSnapshot(v, null).status)
    }

    @Test fun location_prefers_the_banner_then_the_newest_located_event() {
        assertEquals("Sacramento, CA", assembleSnapshot(v, "In transit", events = listOf(older, newer), location = "Sacramento, CA").latestLocation)
        assertEquals("MEMPHIS, TN", assembleSnapshot(v, "In transit", events = listOf(newer, older, unclassified)).latestLocation)
        assertNull(assembleSnapshot(v, "In transit").latestLocation)
    }

    @Test fun explicit_window_wins_and_text_is_the_fallback() {
        val explicit = assembleSnapshot(v, "In transit", etaWindow = EtaWindow(null, LocalTime(21, 0)), etaWindowText = "3:00 PM - 5:00 PM")
        assertNull(explicit.etaWindowStart)
        assertEquals(LocalTime(21, 0), explicit.etaWindowEnd)
        val text = assembleSnapshot(v, "In transit", etaWindowText = "Arriving today 3:00 PM - 5:00 PM")
        assertEquals(LocalTime(15, 0), text.etaWindowStart)
        assertEquals(LocalTime(17, 0), text.etaWindowEnd)
        val empty = assembleSnapshot(v, "In transit", etaWindow = EtaWindow(null, null), etaWindowText = "by 10 PM")
        assertEquals(LocalTime(22, 0), empty.etaWindowEnd)  // an all-null window is no window
        assertNull(assembleSnapshot(v, "In transit", etaWindowText = "sometime tomorrow").etaWindowEnd)
    }

    @Test fun eta_date_and_delay_note_pass_through() {
        val s = assembleSnapshot(v, "On the way: Delayed", etaDate = LocalDate(2026, 8, 20), delayNote = "Due to weather, delayed one day")
        assertEquals(LocalDate(2026, 8, 20), s.etaDate)
        assertEquals("Due to weather, delayed one day", s.delayNote)
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
    }
}

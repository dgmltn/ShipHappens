package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

class TrackerPageResolverTest {
    private val rules = TrackerPageRules(
        vocabulary = StatusVocabulary(StatusKeywords(inTransit = listOf("moving through"))),
        notFound = listOf("""status not available"""),
    )
    private fun resolve(raw: DomRaw) = resolveTrackerPage(raw, rules, TimeZone.UTC)
    private fun tracker(
        statusText: String? = null, pageText: String? = null, etaText: String? = null,
        locationText: String? = null, todayIso: String? = null, events: List<DomRawEvent> = emptyList(),
    ) = DomRaw(kind = "tracker", statusText = statusText, pageText = pageText, etaText = etaText,
               locationText = locationText, todayIso = todayIso, events = events)

    @Test fun a_foreign_kind_is_refused() {
        assertNull(resolve(DomRaw(kind = "cards", statusText = "On the way")))
    }

    @Test fun shared_and_carrier_not_found_wordings_route_not_found() {
        assertIs<PageOutcome.NotFound>(resolve(tracker(pageText = "We couldn't find this tracking number")))
        assertIs<PageOutcome.NotFound>(resolve(tracker(pageText = "Status Not Available for this item")))
        assertIs<PageOutcome.NotFound>(resolve(tracker(statusText = "On the way", pageText = "invalid tracking number")))
    }

    @Test fun ordinary_page_text_does_not_trip_not_found() {
        val out = resolve(tracker(statusText = "On the way", pageText = "Track your package: on the way, expected Thursday"))
        assertEquals(TrackingStatus.IN_TRANSIT, out.snapshotOrNull()?.status)
    }

    @Test fun nothing_readable_is_empty() {
        assertIs<PageOutcome.Empty>(resolve(tracker()))
        assertIs<PageOutcome.Empty>(resolve(tracker(statusText = "   ", pageText = "Track a package")))
    }

    @Test fun a_novel_headline_is_still_a_result_with_unknown_status() {
        assertEquals(TrackingStatus.UNKNOWN, resolve(tracker(statusText = "Some new wording")).snapshotOrNull()?.status)
    }

    @Test fun a_promise_alone_is_a_result() {
        val out = resolve(tracker(etaText = "Estimated delivery Tuesday 8/19/2026 by 8:00 PM"))
        val s = out.snapshotOrNull()!!
        assertEquals(TrackingStatus.UNKNOWN, s.status)
        assertEquals(LocalDate(2026, 8, 19), s.etaDate)
        assertEquals(LocalTime(20, 0), s.etaWindowEnd)
    }

    @Test fun multi_stage_headline_is_refused_not_classified() {
        // A progress rail's step labels, all present in the DOM regardless of state.
        assertNull(resolve(tracker(statusText = "Picked up In transit Delivered")))
    }

    @Test fun headline_classifies_with_the_carrier_vocabulary_and_events_back_it_up() {
        val events = listOf(
            DomRawEvent("2026-08-17T09:00:00Z", "Picked up", "SANTA CLARA, CA"),
            DomRawEvent("2026-08-18T14:33:00Z", "Departed facility", "MEMPHIS, TN"),
        )
        val ok = resolve(tracker(statusText = "Moving through network", events = events)).snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, ok.status)
        assertEquals("MEMPHIS, TN", ok.latestLocation)
        assertEquals(TrackingStatus.SHIPPED, ok.events.first().status)
        val fallback = resolve(tracker(statusText = "Delivery date pending", events = events)).snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, fallback.status)
    }

    @Test fun location_hook_and_banner_win_over_events() {
        val stripping = TrackerPageRules(rules.vocabulary, location = { it.locationText?.removePrefix("Currently in ") })
        val out = resolveTrackerPage(tracker(statusText = "In transit", locationText = "Currently in Sacramento, CA"), stripping, TimeZone.UTC)
        assertEquals("Sacramento, CA", out.snapshotOrNull()?.latestLocation)
    }

    @Test fun eta_resolves_against_the_page_date_by_default() {
        val out = resolve(tracker(statusText = "In transit", etaText = "Arriving tomorrow", todayIso = "2026-08-18"))
        assertEquals(LocalDate(2026, 8, 19), out.snapshotOrNull()?.etaDate)
        assertNull(resolve(tracker(statusText = "In transit", etaText = "Arriving tomorrow")).snapshotOrNull()?.etaDate)
    }

    @Test fun delay_note_is_the_headline_or_else_the_newest_event_only() {
        val v = rules.vocabulary
        assertEquals("On the way: Delayed", headlineThenNewestEvent(v, "On the way: Delayed", emptyList()))
        val delayedNewest = listOf(
            TrackingEvent(Instant.parse("2026-08-18T05:00:00Z"), "Package left the facility"),
            TrackingEvent(Instant.parse("2026-08-18T07:00:00Z"), "Package delayed in transit"),
        )
        assertEquals("Package delayed in transit", headlineThenNewestEvent(v, "Arriving tomorrow", delayedNewest))
        // Delay is orthogonal: a recognized non-delayed headline with a delayed newest event still reports the delay.
        assertEquals("Package delayed in transit", headlineThenNewestEvent(v, "In transit", delayedNewest))
        // A delay event older than the newest scan doesn't flag a package that moved on.
        val movedOn = delayedNewest + TrackingEvent(Instant.parse("2026-08-19T10:00:00Z"), "Delivered")
        assertNull(headlineThenNewestEvent(v, "Delivered", movedOn))
        assertNull(headlineThenNewestEvent(v, "On the way", emptyList()))
        // Several rows can share the newest timestamp (Amazon live capture 2026-08-18): any of
        // them being delayed counts, regardless of row order.
        val tiedNewest = listOf(
            TrackingEvent(Instant.parse("2026-08-18T07:00:00.000Z"), "Delivery appointment scheduled"),
            TrackingEvent(Instant.parse("2026-08-18T07:00:00.000Z"), "Package delayed in transit"),
            TrackingEvent(Instant.parse("2026-08-18T07:00:00.000Z"), "Delivery appointment scheduled"),
        )
        assertEquals("Package delayed in transit", headlineThenNewestEvent(v, "Arriving tomorrow", tiedNewest))
        // Mirror: several rows tied for newest, none delayed, plus an older delayed row — null.
        val tiedNewestNoneDelayed = listOf(
            TrackingEvent(Instant.parse("2026-08-17T07:00:00.000Z"), "Package delayed in transit"),
            TrackingEvent(Instant.parse("2026-08-18T07:00:00.000Z"), "Delivery appointment scheduled"),
            TrackingEvent(Instant.parse("2026-08-18T07:00:00.000Z"), "Out for delivery"),
        )
        assertNull(headlineThenNewestEvent(v, "Arriving tomorrow", tiedNewestNoneDelayed))
    }

    @Test fun delayed_headline_carries_the_note_through_the_resolver() {
        val s = resolve(tracker(statusText = "On the way: Delayed")).snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
        assertEquals("On the way: Delayed", s.delayNote)
    }

    @Test fun eta_hook_sees_the_whole_raw_page() {
        val fromHeadline = TrackerPageRules(rules.vocabulary, etaDate = { raw, today -> parseRelativeDay(raw.statusText, today) })
        val out = resolveTrackerPage(tracker(statusText = "Arriving tomorrow", todayIso = "2026-08-18"), fromHeadline, TimeZone.UTC)
        assertEquals(LocalDate(2026, 8, 19), out.snapshotOrNull()?.etaDate)
    }

    @Test fun window_is_read_from_the_status_line_before_the_promise_banner() {
        val out = resolve(tracker(statusText = "Arriving today by 10 PM", etaText = "Between 8 AM and 12 PM tomorrow"))
        assertEquals(LocalTime(22, 0), out.snapshotOrNull()?.etaWindowEnd)
        assertNull(out.snapshotOrNull()?.etaWindowStart)
        val bannerOnly = resolve(tracker(statusText = "In transit", etaText = "Between 8 AM and 12 PM"))
        assertEquals(LocalTime(8, 0), bannerOnly.snapshotOrNull()?.etaWindowStart)
        val delivered = resolve(tracker(statusText = "Delivered at 1:15 pm", etaText = "Estimated delivery by 9:00 pm"))
        assertEquals(LocalTime(21, 0), delivered.snapshotOrNull()?.etaWindowEnd)
    }
}

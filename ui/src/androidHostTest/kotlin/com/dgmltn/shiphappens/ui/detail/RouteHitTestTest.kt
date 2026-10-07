package com.dgmltn.shiphappens.ui.detail

import androidx.compose.ui.geometry.Offset
import com.dgmltn.shiphappens.geo.LatLng
import kotlin.test.*

class RouteHitTestTest {
    private val pts = listOf(Offset(10f, 10f), Offset(100f, 50f), Offset(104f, 52f))

    @Test fun nearest_within_radius_wins() = assertEquals(2, nearestStop(pts, Offset(105f, 53f), 24f))
    @Test fun nothing_within_radius_is_null() = assertNull(nearestStop(pts, Offset(60f, 90f), 24f))
    @Test fun empty_points_is_null() = assertNull(nearestStop(emptyList(), Offset(0f, 0f), 24f))

    @Test fun tapping_selected_stop_or_empty_space_dismisses() {
        assertNull(nextSelection(current = 1, hit = 1))
        assertNull(nextSelection(current = 1, hit = null))
        assertEquals(2, nextSelection(current = 1, hit = 2))
        assertEquals(0, nextSelection(current = null, hit = 0))
    }

    @Test fun callout_truncates_with_more_count() {
        val stop = StopUi("Sacramento, CA", LatLng(0.0, 0.0), (1..6).map { StopEventUi("Tue · $it:00", "scan $it") })
        val lines = calloutLines(stop)
        assertEquals(5, lines.size)
        assertEquals("Tue · 1:00 · scan 1", lines[0])
        assertEquals("+2 more", lines[4])
    }

    @Test fun callout_without_overflow_has_no_more_line() {
        val stop = StopUi("X", LatLng(0.0, 0.0), listOf(StopEventUi("Tue · 1:00", "scan")))
        assertEquals(listOf("Tue · 1:00 · scan"), calloutLines(stop))
    }

    @Test fun selection_resets_when_stops_change() {
        val a = listOf(StopUi("A", LatLng(0.0, 0.0), emptyList()), StopUi("B", LatLng(1.0, 1.0), emptyList()))
        val b = a.take(1)
        assertNotEquals(a, b)
        assertNull(selectionFor(previousStops = a, previous = 1, stops = b))
        assertEquals(1, selectionFor(previousStops = a, previous = 1, stops = a))
    }
}

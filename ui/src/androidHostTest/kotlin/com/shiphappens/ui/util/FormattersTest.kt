package com.shiphappens.ui.util

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FormattersTest {

    @Test fun window_with_shared_meridiem_collapses_it() {
        assertEquals("3:00 – 5:00 PM", formatEtaWindow(LocalTime(15, 0), LocalTime(17, 0)))
    }

    @Test fun window_with_differing_meridiem_shows_both() {
        assertEquals("11:30 AM – 1:30 PM", formatEtaWindow(LocalTime(11, 30), LocalTime(13, 30)))
    }

    @Test fun end_only_renders_by_prefix() {
        assertEquals("by 8:00 PM", formatEtaWindow(null, LocalTime(20, 0)))
    }

    @Test fun both_null_returns_null() {
        assertNull(formatEtaWindow(null, null))
    }

    @Test fun start_without_end_is_degenerate_and_returns_null() {
        assertNull(formatEtaWindow(LocalTime(15, 0), null))
    }

    @Test fun midnight_and_noon_use_twelve() {
        assertEquals("12:00 AM – 12:00 PM", formatEtaWindow(LocalTime(0, 0), LocalTime(12, 0)))
    }

    @Test fun design12h_output_is_unchanged() {
        assertEquals("9:05 AM", LocalTime(9, 5).design12h())
        assertEquals("12:00 PM", LocalTime(12, 0).design12h())
        assertEquals("12:00 AM", LocalTime(0, 0).design12h())
    }
}

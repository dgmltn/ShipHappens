package com.dgmltn.shiphappens.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.*

class DisplayTest {
    @Test fun window_with_shared_meridiem_collapses() {
        assertEquals("3:00 – 5:00 PM", formatEtaWindow(LocalTime(15, 0), LocalTime(17, 0), is24Hour = false))
    }

    @Test fun window_crossing_meridiem_keeps_both() {
        assertEquals("11:30 AM – 1:30 PM", formatEtaWindow(LocalTime(11, 30), LocalTime(13, 30), is24Hour = false))
    }

    @Test fun end_only_reads_as_by() {
        assertEquals("by 8:00 PM", formatEtaWindow(null, LocalTime(20, 0), is24Hour = false))
    }

    @Test fun no_end_is_null() {
        assertNull(formatEtaWindow(null, null, is24Hour = false))
        assertNull(formatEtaWindow(LocalTime(15, 0), null, is24Hour = false))
    }

    @Test fun midnight_and_noon() {
        assertEquals("12:00 AM – 12:00 PM", formatEtaWindow(LocalTime(0, 0), LocalTime(12, 0), is24Hour = false))
    }

    @Test fun design12h_output_is_unchanged() {
        assertEquals("9:05 AM", LocalTime(9, 5).design12h())
        assertEquals("12:00 PM", LocalTime(12, 0).design12h())
        assertEquals("12:00 AM", LocalTime(0, 0).design12h())
    }

    @Test fun design24h_pads_and_drops_the_meridiem() {
        assertEquals("09:05", LocalTime(9, 5).design24h())
        assertEquals("15:30", LocalTime(15, 30).design24h())
        assertEquals("00:00", LocalTime(0, 0).design24h())
        assertEquals("12:00", LocalTime(12, 0).design24h())
    }

    @Test fun designTime_follows_the_device_convention() {
        assertEquals("3:00 PM", LocalTime(15, 0).designTime(is24Hour = false))
        assertEquals("15:00", LocalTime(15, 0).designTime(is24Hour = true))
    }

    @Test fun window_on_a_24_hour_clock_has_no_meridiem_to_collapse() {
        assertEquals("15:00 – 17:00", formatEtaWindow(LocalTime(15, 0), LocalTime(17, 0), is24Hour = true))
        assertEquals("11:30 – 13:30", formatEtaWindow(LocalTime(11, 30), LocalTime(13, 30), is24Hour = true))
        assertEquals("by 20:00", formatEtaWindow(null, LocalTime(20, 0), is24Hour = true))
    }

    @Test fun designFormat_is_weekday_month_day() {
        assertEquals("Wed, Aug 19", LocalDate(2026, 8, 19).designFormat())
    }
}

package com.dgmltn.shiphappens.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.*

class DisplayTest {
    @Test fun window_with_shared_meridiem_collapses() {
        assertEquals("3:00 – 5:00 PM", formatEtaWindow(LocalTime(15, 0), LocalTime(17, 0)))
    }

    @Test fun window_crossing_meridiem_keeps_both() {
        assertEquals("11:30 AM – 1:30 PM", formatEtaWindow(LocalTime(11, 30), LocalTime(13, 30)))
    }

    @Test fun end_only_reads_as_by() {
        assertEquals("by 8:00 PM", formatEtaWindow(null, LocalTime(20, 0)))
    }

    @Test fun no_end_is_null() {
        assertNull(formatEtaWindow(null, null))
        assertNull(formatEtaWindow(LocalTime(15, 0), null))
    }

    @Test fun midnight_and_noon() {
        assertEquals("12:00 AM – 12:00 PM", formatEtaWindow(LocalTime(0, 0), LocalTime(12, 0)))
    }

    @Test fun design12h_output_is_unchanged() {
        assertEquals("9:05 AM", LocalTime(9, 5).design12h())
        assertEquals("12:00 PM", LocalTime(12, 0).design12h())
        assertEquals("12:00 AM", LocalTime(0, 0).design12h())
    }

    @Test fun designFormat_is_weekday_month_day() {
        assertEquals("Wed, Aug 19", LocalDate(2026, 8, 19).designFormat())
    }
}

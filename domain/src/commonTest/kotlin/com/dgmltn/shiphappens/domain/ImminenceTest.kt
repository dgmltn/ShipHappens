package com.dgmltn.shiphappens.domain

import kotlinx.datetime.LocalDate
import kotlin.test.*

class ImminenceTest {
    private val today = LocalDate(2026, 9, 6)

    @Test fun buckets_by_days_until_the_eta() {
        assertEquals(Imminence.OVERDUE, Imminence.of(today, LocalDate(2026, 9, 5)))
        assertEquals(Imminence.TODAY, Imminence.of(today, today))
        assertEquals(Imminence.TOMORROW, Imminence.of(today, LocalDate(2026, 9, 7)))
        assertEquals(Imminence.THIS_WEEK, Imminence.of(today, LocalDate(2026, 9, 8)))
        assertEquals(Imminence.THIS_WEEK, Imminence.of(today, LocalDate(2026, 9, 12)))
        assertEquals(Imminence.LATER, Imminence.of(today, LocalDate(2026, 9, 13)))
    }

    @Test fun long_overdue_is_still_just_overdue() {
        assertEquals(Imminence.OVERDUE, Imminence.of(today, LocalDate(2026, 1, 1)))
    }

    @Test fun only_the_three_that_change_plans_are_worth_waking_someone_for() {
        assertTrue(Imminence.OVERDUE.isWorthAnnouncing)
        assertTrue(Imminence.TODAY.isWorthAnnouncing)
        assertTrue(Imminence.TOMORROW.isWorthAnnouncing)
        assertFalse(Imminence.THIS_WEEK.isWorthAnnouncing)
        assertFalse(Imminence.LATER.isWorthAnnouncing)
    }

    @Test fun weekday_name_is_spelled_out() {
        assertEquals("Sunday", LocalDate(2026, 9, 6).weekdayName())
        assertEquals("Thursday", LocalDate(2026, 9, 10).weekdayName())
        assertEquals("Saturday", LocalDate(2026, 9, 12).weekdayName())
    }
}

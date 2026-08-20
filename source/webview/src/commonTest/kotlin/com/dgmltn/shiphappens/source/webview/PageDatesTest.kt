package com.dgmltn.shiphappens.source.webview

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PageDatesTest {

    // -- month-name dates (year required) --

    @Test fun parses_month_day_year_with_and_without_comma() {
        assertEquals(LocalDate(2026, 7, 28), parseMonthNameDate("Expected Delivery on Monday, July 28, 2026"))
        assertEquals(LocalDate(2026, 7, 28), parseMonthNameDate("July 28 2026"))
    }

    @Test fun parses_day_month_year_order() {
        // USPS progress banner renders "Tuesday 28 July 2026" split across spans.
        assertEquals(LocalDate(2026, 7, 28), parseMonthNameDate("Tuesday 28 July 2026 tooltip junk"))
    }

    @Test fun parses_abbreviated_months() {
        assertEquals(LocalDate(2026, 8, 13), parseMonthNameDate("Aug 13, 2026"))
        assertEquals(LocalDate(2026, 9, 2), parseMonthNameDate("Sept 2, 2026"))
    }

    @Test fun month_name_needs_a_year() {
        assertNull(parseMonthNameDate("Saturday, August 22"))
        assertNull(parseMonthNameDate(null))
    }

    // -- numeric M/D/YYYY --

    @Test fun parses_numeric_mdy_including_run_together_prefix() {
        assertEquals(LocalDate(2026, 8, 19), parseNumericMdyDate("Tuesday 8/19/2026 by end of day"))
        // fedex.com hero flattens with no space before the date (QA 2026-08-19).
        assertEquals(LocalDate(2026, 8, 20), parseNumericMdyDate("Thursday8/20/2026 Between 10:10 AM - 2:10 PM"))
        assertEquals(LocalDate(2026, 7, 16), parseNumericMdyDate("07/16/2026"))
    }

    @Test fun numeric_rejects_invalid_and_absent_dates() {
        assertNull(parseNumericMdyDate("Estimated delivery Pending"))
        assertNull(parseNumericMdyDate(null))
    }

    // -- relative days (resolved against the page's own date) --

    @Test fun resolves_relative_wordings() {
        val today = LocalDate(2026, 8, 19)
        assertEquals(LocalDate(2026, 8, 19), parseRelativeDay("Arriving today by 10 PM", today))
        assertEquals(LocalDate(2026, 8, 20), parseRelativeDay("Now expected tomorrow by 8 AM", today))
        assertEquals(LocalDate(2026, 8, 20), parseRelativeDay("Arriving overnight 7 AM – 11 AM", today))
        assertEquals(LocalDate(2026, 8, 18), parseRelativeDay("Delivered yesterday", today))
    }

    @Test fun relative_needs_today_and_a_relative_word() {
        assertNull(parseRelativeDay("Arriving today", null))
        assertNull(parseRelativeDay("Arriving August 22", LocalDate(2026, 8, 19)))
        assertNull(parseRelativeDay(null, LocalDate(2026, 8, 19)))
    }

    // -- year-less day (month + day, year inferred from the page date) --

    @Test fun infers_the_year_for_a_yearless_day() {
        val today = LocalDate(2026, 8, 19)
        assertEquals(LocalDate(2026, 8, 22), parseDayWithoutYear("Saturday, August 22", today))
        assertEquals(LocalDate(2026, 8, 22), parseDayWithoutYear("22 August", today))
    }

    @Test fun a_day_too_far_ahead_belongs_to_last_year() {
        // A "December 20" promise read on January 5 is history, not eleven months out.
        assertEquals(LocalDate(2025, 12, 20), parseDayWithoutYear("December 20", LocalDate(2026, 1, 5)))
    }

    @Test fun yearless_needs_today() {
        assertNull(parseDayWithoutYear("August 22", null))
        assertNull(parseDayWithoutYear("no date here", LocalDate(2026, 8, 19)))
    }

    // -- weekday-only promise ("Arriving Thursday"), resolved to its next occurrence --

    @Test fun resolves_a_weekday_to_its_next_occurrence() {
        val wednesday = LocalDate(2026, 8, 19)
        // Live fedex.com capture 2026-08-19: the hero sometimes renders the promise as
        // "Thursday Between 10:10 AM - 2:10 PM" — weekday only, no numeric date.
        assertEquals(LocalDate(2026, 8, 20), parseWeekdayName("Thursday Between 10:10 AM - 2:10 PM", wednesday))
        assertEquals(LocalDate(2026, 8, 24), parseWeekdayName("Arriving Monday", wednesday))
        assertEquals(LocalDate(2026, 8, 21), parseWeekdayName("Fri by end of day", wednesday))
    }

    @Test fun a_promise_for_todays_weekday_is_today() {
        val wednesday = LocalDate(2026, 8, 19)
        assertEquals(wednesday, parseWeekdayName("Wednesday by 8:00 PM", wednesday))
    }

    @Test fun weekday_needs_today_and_a_weekday_word() {
        assertNull(parseWeekdayName("Arriving Thursday", null))
        assertNull(parseWeekdayName("by end of day", LocalDate(2026, 8, 19)))
        assertNull(parseWeekdayName(null, LocalDate(2026, 8, 19)))
    }
}

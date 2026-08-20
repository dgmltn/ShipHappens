package com.dgmltn.shiphappens.source.webview

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EtaWindowParserTest {

    private fun window(start: LocalTime, end: LocalTime) = EtaWindow(start, end)

    // -- findEtaWindowText: pulling the window phrase out of a noisy banner --

    @Test fun finds_a_range_phrase_verbatim() {
        assertEquals(
            "10:35 AM - 2:35 PM",
            findEtaWindowText("Estimated delivery window 10:35 AM - 2:35 PM tooltip junk"),
        )
        assertEquals(
            "Between 10:10 AM - 2:10 PM",
            findEtaWindowText("Thursday8/20/2026 Between 10:10 AM - 2:10 PM"),
        )
        assertEquals(
            "between 9:45am and 1:45pm",
            findEtaWindowText("expected between 9:45am and 1:45pm on Monday"),
        )
    }

    @Test fun finds_a_lone_cutoff_phrase() {
        assertEquals("by 8:00 PM", findEtaWindowText("Tuesday 8/19/2026 by 8:00 PM"))
        assertEquals("by 9:00pm", findEtaWindowText("Expected Delivery by Monday, July 28, 2026 by 9:00pm"))
    }

    @Test fun no_timed_phrase_means_null() {
        assertNull(findEtaWindowText("Tuesday 8/19/2026 by end of day"))
        assertNull(findEtaWindowText("Arriving Thursday"))
        assertNull(findEtaWindowText(null))
    }

    @Test fun parses_full_range_with_both_meridiems() {
        assertEquals(window(LocalTime(15, 0), LocalTime(17, 0)), parseEtaWindow("3:00 PM - 5:00 PM"))
    }

    @Test fun start_inherits_end_meridiem_when_omitted() {
        assertEquals(window(LocalTime(15, 0), LocalTime(17, 0)), parseEtaWindow("3 - 5 PM"))
    }

    @Test fun parses_range_crossing_meridiem() {
        assertEquals(window(LocalTime(11, 30), LocalTime(13, 30)), parseEtaWindow("11:30 AM – 1:30 PM"))
    }

    @Test fun parses_word_separator() {
        assertEquals(window(LocalTime(8, 0), LocalTime(12, 0)), parseEtaWindow("8 AM to 12 PM"))
    }

    @Test fun parses_between_and_separator() {
        assertEquals(
            window(LocalTime(12, 0), LocalTime(14, 0)),
            parseEtaWindow("between 12:00pm and 2:00pm"),
        )
    }

    @Test fun parses_em_dash_separator() {
        assertEquals(window(LocalTime(15, 0), LocalTime(17, 0)), parseEtaWindow("3:00 PM — 5:00 PM"))
    }

    @Test fun parses_midnight_range() {
        assertEquals(window(LocalTime(0, 0), LocalTime(2, 0)), parseEtaWindow("12 AM - 2 AM"))
    }

    @Test fun parses_noon_end() {
        assertEquals(window(LocalTime(10, 0), LocalTime(12, 0)), parseEtaWindow("10 AM - 12 PM"))
    }

    @Test fun is_case_insensitive() {
        assertEquals(window(LocalTime(15, 0), LocalTime(17, 0)), parseEtaWindow("3:00 pm - 5:00 pm"))
    }

    @Test fun finds_window_inside_surrounding_text() {
        assertEquals(
            window(LocalTime(15, 0), LocalTime(17, 0)),
            parseEtaWindow("Arriving today 3:00 PM - 5:00 PM"),
        )
    }

    @Test fun by_form_yields_open_ended_window() {
        assertEquals(EtaWindow(null, LocalTime(22, 0)), parseEtaWindow("Arriving today by 10 PM"))
    }

    @Test fun rejects_range_without_meridiem() {
        assertNull(parseEtaWindow("3 - 5"))
    }

    @Test fun rejects_out_of_range_hour() {
        assertNull(parseEtaWindow("13:00 PM - 15:00 PM"))
        assertNull(parseEtaWindow("0:30 AM - 2:30 AM"))
    }

    @Test fun rejects_out_of_range_minute() {
        assertNull(parseEtaWindow("3:60 PM - 5:00 PM"))
    }

    @Test fun rejects_text_with_no_time() {
        assertNull(parseEtaWindow("Arriving today"))
    }

    @Test fun rejects_empty_and_null() {
        assertNull(parseEtaWindow(""))
        assertNull(parseEtaWindow(null))
    }
}

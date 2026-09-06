package com.dgmltn.shiphappens.ui.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

class NextUpdateToastTest {
    private val noon = Instant.parse("2026-09-03T12:00:00Z")
    private val utc = TimeZone.UTC

    @Test fun later_today_reads_in_hours() {
        assertEquals("Next scheduled update in 3 hours",
            NextUpdateToast.message(LocalTime(15, 0), noon, utc, is24Hour = false))
    }

    @Test fun hours_round_to_nearest() {
        // 2h31m rounds up to 3.
        assertEquals("Next scheduled update in 3 hours",
            NextUpdateToast.message(LocalTime(14, 31), noon, utc, is24Hour = false))
        // 1h29m rounds down to the singular hour.
        assertEquals("Next scheduled update in 1 hour",
            NextUpdateToast.message(LocalTime(13, 29), noon, utc, is24Hour = false))
    }

    @Test fun under_an_hour_reads_in_minutes() {
        assertEquals("Next scheduled update in 45 minutes",
            NextUpdateToast.message(LocalTime(12, 45), noon, utc, is24Hour = false))
        assertEquals("Next scheduled update in 1 minute",
            NextUpdateToast.message(LocalTime(12, 1), noon, utc, is24Hour = false))
    }

    @Test fun a_time_already_past_today_reads_tomorrow_at() {
        assertEquals("Next scheduled update tomorrow at 7:00 AM",
            NextUpdateToast.message(LocalTime(7, 0), noon, utc, is24Hour = false))
    }

    @Test fun a_24_hour_device_gets_a_24_hour_toast() {
        assertEquals("Next scheduled update tomorrow at 07:00",
            NextUpdateToast.message(LocalTime(7, 0), noon, utc, is24Hour = true))
    }
}

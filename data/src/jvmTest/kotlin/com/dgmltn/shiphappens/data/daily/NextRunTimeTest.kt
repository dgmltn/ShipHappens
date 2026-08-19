package com.dgmltn.shiphappens.data.daily

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.hours
import kotlin.test.*

class NextRunTimeTest {
    private val ny = TimeZone.of("America/New_York")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        LocalDateTime(y, m, d, h, min).toInstant(ny)

    @Test fun later_today_is_today() {
        val now = at(2026, 8, 18, 6, 30)
        assertEquals(at(2026, 8, 18, 8, 0), NextRunTime.nextOccurrence(LocalTime(8, 0), now, ny))
    }

    @Test fun already_past_rolls_to_tomorrow() {
        val now = at(2026, 8, 18, 9, 15)
        assertEquals(at(2026, 8, 19, 8, 0), NextRunTime.nextOccurrence(LocalTime(8, 0), now, ny))
    }

    @Test fun exactly_now_rolls_to_tomorrow() {
        val now = at(2026, 8, 18, 8, 0)
        assertEquals(at(2026, 8, 19, 8, 0), NextRunTime.nextOccurrence(LocalTime(8, 0), now, ny))
    }

    @Test fun spring_forward_still_lands_on_local_eight_am() {
        // 2027-03-14 is the US DST spring-forward date; 8am local is 23 hours after 8am on the 13th.
        val now = at(2027, 3, 13, 9, 0)
        val next = NextRunTime.nextOccurrence(LocalTime(8, 0), now, ny)
        assertEquals(at(2027, 3, 14, 8, 0), next)
        assertEquals(23.hours, next - at(2027, 3, 13, 8, 0))
    }

    @Test fun delay_is_the_gap_from_now() {
        val now = at(2026, 8, 18, 6, 0)
        assertEquals(2.hours, NextRunTime.delayUntilNext(LocalTime(8, 0), now, ny))
    }
}

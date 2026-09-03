package com.dgmltn.shiphappens.ui.settings

import com.dgmltn.shiphappens.data.daily.NextRunTime
import com.dgmltn.shiphappens.domain.design12h
import kotlin.time.Instant
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Toast copy confirming when a just-(re)scheduled daily update will actually fire. Same-day
 * bookings read as a countdown ("in 3 hours"); a time already past today reads as "tomorrow at",
 * which is also the answer to the "why didn't it run right now?" the user is about to ask.
 */
object NextUpdateToast {

    fun message(target: LocalTime, now: Instant, zone: TimeZone): String {
        val next = NextRunTime.nextOccurrence(target, now, zone)
        if (next.toLocalDateTime(zone).date != now.toLocalDateTime(zone).date) {
            return "Next scheduled update tomorrow at ${target.design12h()}"
        }
        val minutes = (next - now).inWholeMinutes
        val amount = when {
            minutes < 60 -> plural(minutes.coerceAtLeast(1), "minute")
            // Integer half-up rounding: 1h29m -> "1 hour", 1h30m -> "2 hours".
            else -> plural((minutes + 30) / 60, "hour")
        }
        return "Next scheduled update in $amount"
    }

    private fun plural(n: Long, unit: String): String = if (n == 1L) "1 $unit" else "$n ${unit}s"
}

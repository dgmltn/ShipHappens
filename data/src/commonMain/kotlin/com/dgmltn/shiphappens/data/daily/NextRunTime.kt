package com.dgmltn.shiphappens.data.daily

import kotlin.time.Duration
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * When the daily refresh should next fire, anchored to a WALL-CLOCK local time.
 *
 * Deliberately not "now + 24h": across a DST boundary that would drift the run an hour, and
 * every re-anchor would compound the drift. Computing the next local 8:00 each time keeps it
 * fixed to the user's morning, and shortens/lengthens the one interval that straddles the change.
 */
object NextRunTime {

    fun nextOccurrence(target: LocalTime, now: Instant, zone: TimeZone): Instant {
        val today = now.toLocalDateTime(zone).date
        val todayAt = LocalDateTime(today, target).toInstant(zone)
        // Strictly-after: a run that fires exactly at the target must schedule the NEXT day,
        // not re-enqueue itself for the instant it is already at.
        return if (todayAt > now) todayAt else LocalDateTime(today.plus(1, DateTimeUnit.DAY), target).toInstant(zone)
    }

    fun delayUntilNext(target: LocalTime, now: Instant, zone: TimeZone): Duration =
        nextOccurrence(target, now, zone) - now
}

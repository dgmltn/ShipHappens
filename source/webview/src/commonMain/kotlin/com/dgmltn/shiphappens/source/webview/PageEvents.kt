package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** A scan's wall-clock date and time in [zone]; a missing time pins to midnight. */
fun eventAt(date: LocalDate, time: LocalTime?, zone: TimeZone): Instant =
    LocalDateTime(date, time ?: LocalTime(0, 0)).toInstant(zone)

// Carrier APIs stamp events with a US zone abbreviation ("ET"), not an offset; IANA zones keep
// the DST arithmetic right. Arizona summer scans stamped "MST" read an hour off via
// America/Denver — accepted, the same class of skew as a device-zone fallback.
private val ZONES = mapOf(
    "ET" to "America/New_York", "EST" to "America/New_York", "EDT" to "America/New_York",
    "CT" to "America/Chicago", "CST" to "America/Chicago", "CDT" to "America/Chicago",
    "MT" to "America/Denver", "MST" to "America/Denver", "MDT" to "America/Denver",
    "PT" to "America/Los_Angeles", "PST" to "America/Los_Angeles", "PDT" to "America/Los_Angeles",
    "AKT" to "America/Anchorage", "AKST" to "America/Anchorage", "AKDT" to "America/Anchorage",
    "HT" to "Pacific/Honolulu", "HST" to "Pacific/Honolulu",
    "AT" to "America/Puerto_Rico", "AST" to "America/Puerto_Rico",
    "UTC" to "UTC", "GMT" to "UTC", "Z" to "UTC",
)

/** The IANA zone for a US abbreviation, or null for one we don't know (callers fall back to the device zone). */
fun zoneForAbbreviation(abbreviation: String?): TimeZone? =
    ZONES[abbreviation?.trim()?.uppercase()]?.let { TimeZone.of(it) }

/** The device-local date the page was read on, or null when the extractor never reported one. */
fun DomRaw.today(): LocalDate? = todayIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

/**
 * One event row as a domain event. An ISO [DomRawEvent.timestamp] wins; otherwise the verbatim
 * [DomRawEvent.whenText] is read as a date (numeric, month-name, relative to [today], or a
 * year-less month+day) plus a clock time. A row whose date can't be read is dropped (null) —
 * an event with a guessed time would sort wrongly, which is worse than a missing row.
 */
fun DomRawEvent.toTrackingEvent(vocabulary: StatusVocabulary, zone: TimeZone, today: LocalDate? = null): TrackingEvent? {
    val at = instant(zone, today) ?: return null
    return TrackingEvent(
        timestamp = at,
        description = description,
        location = location,
        status = vocabulary.classify(description),
    )
}

private fun DomRawEvent.instant(zone: TimeZone, today: LocalDate?): Instant? {
    runCatching { Instant.parse(timestamp) }.getOrNull()?.let { return it }
    val w = whenText ?: return null
    val date = parseNumericMdyDate(w)
        ?: parseMonthNameDate(w)
        ?: parseRelativeDay(w, today)
        ?: parseDayWithoutYear(w, today)
        ?: return null
    return eventAt(date, parseTimeOfDay(w), zone)
}

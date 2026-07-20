package com.dgmltn.shiphappens.ui.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

private val WD = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MO = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

// kotlinx-datetime 0.8.0: monthNumber/dayOfMonth are deprecated in favor of month.number/day.
fun LocalDate.designFormat(): String = "${WD[dayOfWeek.isoDayNumber - 1]}, ${MO[month.number - 1]} $day"

private fun LocalTime.clock12(): String {
    val h12 = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
    return "$h12:${minute.toString().padStart(2, '0')}"
}

private fun LocalTime.meridiem(): String = if (hour < 12) "AM" else "PM"

fun LocalTime.design12h(): String = "${clock12()} ${meridiem()}"

/**
 * Renders an ETA delivery window.
 *
 * - both set   -> "3:00 – 5:00 PM" (meridiem collapsed when shared, else "11:30 AM – 1:30 PM")
 * - end only   -> "by 8:00 PM"
 * - no end     -> null (a start without an end is degenerate; no carrier produces it)
 */
fun formatEtaWindow(start: LocalTime?, end: LocalTime?): String? {
    if (end == null) return null
    if (start == null) return "by ${end.design12h()}"
    val startText = if (start.meridiem() == end.meridiem()) start.clock12() else start.design12h()
    return "$startText – ${end.design12h()}"
}

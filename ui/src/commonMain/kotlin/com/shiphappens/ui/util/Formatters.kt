package com.shiphappens.ui.util

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

private val WD = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MO = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

// kotlinx-datetime 0.8.0: monthNumber/dayOfMonth are deprecated in favor of month.number/day.
fun LocalDate.designFormat(): String = "${WD[dayOfWeek.isoDayNumber - 1]}, ${MO[month.number - 1]} $day"

fun LocalTime.design12h(): String {
    val h12 = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
    val ampm = if (hour < 12) "AM" else "PM"
    return "$h12:${minute.toString().padStart(2, '0')} $ampm"
}

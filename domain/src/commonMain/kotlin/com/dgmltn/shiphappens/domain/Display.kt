package com.dgmltn.shiphappens.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

private val WD = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val WD_FULL = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val MO = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/**
 * Display labels for the 5-step timeline, indexed by `TrackingStatus.stepIndex` /
 * `Parcel.effectiveStepIndex`. Shared by the list row status, the detail timeline, and
 * notification copy so every surface names the current step identically.
 */
val TRACKING_STEP_LABELS = listOf("Label created", "Shipped", "In transit", "Out for delivery", "Delivered")

// kotlinx-datetime 0.8.0: monthNumber/dayOfMonth are deprecated in favor of month.number/day.
fun LocalDate.designFormat(): String = "${WD[dayOfWeek.isoDayNumber - 1]}, ${MO[month.number - 1]} $day"

/** Spelled-out weekday, for copy that names a nearby day instead of dating it ("on Thursday"). */
fun LocalDate.weekdayName(): String = WD_FULL[dayOfWeek.isoDayNumber - 1]

private fun LocalTime.clock12(): String {
    val h12 = when { hour == 0 -> 12; hour > 12 -> hour - 12; else -> hour }
    return "$h12:${minute.toString().padStart(2, '0')}"
}

private fun LocalTime.meridiem(): String = if (hour < 12) "AM" else "PM"

fun LocalTime.design12h(): String = "${clock12()} ${meridiem()}"

/** Zero-padded 24-hour clock: "07:00", "15:30", midnight as "00:00". */
fun LocalTime.design24h(): String =
    "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

/**
 * A time in the convention the device is set to — every user-facing time goes through here, so
 * a phone on a 24-hour clock never sees an AM/PM anywhere in the app. The flag comes from
 * `TimeFormat` (in `data`), which reads the platform setting.
 */
fun LocalTime.designTime(is24Hour: Boolean): String = if (is24Hour) design24h() else design12h()

/**
 * Renders an ETA delivery window.
 *
 * - both set   -> "3:00 – 5:00 PM" (meridiem collapsed when shared, else "11:30 AM – 1:30 PM")
 *                 or "15:00 – 17:00" on a 24-hour clock, where there's nothing to collapse
 * - end only   -> "by 8:00 PM" / "by 20:00"
 * - no end     -> null (a start without an end is degenerate; no carrier produces it)
 */
fun formatEtaWindow(start: LocalTime?, end: LocalTime?, is24Hour: Boolean): String? {
    if (end == null) return null
    if (start == null) return "by ${end.designTime(is24Hour)}"
    // 12-hour only: "3:00 – 5:00 PM" reads better than repeating a shared meridiem.
    val startText = when {
        is24Hour -> start.design24h()
        start.meridiem() == end.meridiem() -> start.clock12()
        else -> start.design12h()
    }
    return "$startText – ${end.designTime(is24Hour)}"
}

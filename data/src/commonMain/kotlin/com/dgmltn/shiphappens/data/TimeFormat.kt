package com.dgmltn.shiphappens.data

/**
 * Whether the device is set to a 24-hour clock. Platform-provided (Android's
 * `DateFormat.is24HourFormat`, iOS's locale template) and deliberately a seam rather than a
 * locale guess: the setting is a per-device preference a user can flip independently of region,
 * and the app has no business overriding it.
 *
 * Read at the point a string is built rather than cached, so flipping the system setting takes
 * effect on the next screen the user opens.
 */
fun interface TimeFormat {
    fun uses24HourClock(): Boolean
}

/** For tests and platforms with no setting to read. */
object TwelveHourFormat : TimeFormat {
    override fun uses24HourClock(): Boolean = false
}

package com.dgmltn.shiphappens.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * How near a delivery date is, relative to a given day.
 *
 * The band an ETA falls in moves on its own as days pass — a date that read as [LATER] on Monday
 * reads as [TOMORROW] on Thursday without the carrier changing anything — which is exactly why
 * notifications key off it rather than off the date alone (see `ParcelChange.becameImminent`).
 */
enum class Imminence {
    OVERDUE,
    TODAY,
    TOMORROW,
    /** Two to six days out: near enough that a relative count reads better than a date. */
    THIS_WEEK,
    LATER;

    /**
     * Whether ARRIVING in this band is news. The near bands change what someone does today —
     * be home, chase the carrier — while a package drifting from [LATER] into [THIS_WEEK] is
     * just time passing, and announcing it every morning would train the user to swipe.
     */
    val isWorthAnnouncing: Boolean get() = this == OVERDUE || this == TODAY || this == TOMORROW

    companion object {
        fun of(today: LocalDate, eta: LocalDate): Imminence = when (today.daysUntil(eta)) {
            in Int.MIN_VALUE..-1 -> OVERDUE
            0 -> TODAY
            1 -> TOMORROW
            in 2..6 -> THIS_WEEK
            else -> LATER
        }
    }
}

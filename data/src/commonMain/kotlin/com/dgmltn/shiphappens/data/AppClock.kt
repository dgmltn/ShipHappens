package com.dgmltn.shiphappens.data

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

interface AppClock {
    fun now(): Instant
    fun today(): LocalDate

    /**
     * The local date some earlier instant fell on — used to ask "how far out did this ETA look
     * the last time we checked". Defaulted so test clocks only ever have to pin the two values
     * that matter.
     */
    fun dateOf(instant: Instant): LocalDate = instant.toLocalDateTime(TimeZone.currentSystemDefault()).date
}

class SystemClock : AppClock {
    override fun now(): Instant = Clock.System.now()
    override fun today(): LocalDate = dateOf(now())
}

package com.dgmltn.shiphappens.data

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

interface AppClock {
    fun now(): Instant
    fun today(): LocalDate
}

class SystemClock : AppClock {
    override fun now(): Instant = Clock.System.now()
    override fun today(): LocalDate = now().toLocalDateTime(TimeZone.currentSystemDefault()).date
}

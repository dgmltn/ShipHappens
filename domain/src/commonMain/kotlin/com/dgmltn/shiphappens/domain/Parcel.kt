package com.dgmltn.shiphappens.domain

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

fun normalizeTracking(raw: String): String =
    raw.filterNot { it.isWhitespace() || it == '-' }.uppercase()

/**
 * Current position on the 5-step timeline. Falls back to the furthest step-bearing event when
 * [Parcel.status] itself doesn't advance the timeline (EXCEPTION/UNKNOWN), so list and detail
 * screens agree on the current step.
 */
val Parcel.effectiveStepIndex: Int
    get() = if (status.stepIndex >= 0) status.stepIndex
        else events.mapNotNull { it.status?.stepIndex }.filter { it >= 0 }.maxOrNull() ?: 0

data class Parcel(
    val id: String,
    val name: String,
    val trackingNumber: String,
    val carrier: Carrier,
    val sourceId: String? = null,
    val status: TrackingStatus = TrackingStatus.UNKNOWN,
    val etaDate: LocalDate? = null,
    val etaWindowStart: LocalTime? = null,
    val etaWindowEnd: LocalTime? = null,
    val events: List<TrackingEvent> = emptyList(),
    val latestLocation: String? = null,
    val isArchived: Boolean = false,
    val createdAt: Instant,
    val lastRefreshedAt: Instant? = null,
) {
    val normalizedTracking: String get() = normalizeTracking(trackingNumber)
}

package com.shiphappens.core.model

import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

fun normalizeTracking(raw: String): String =
    raw.filterNot { it.isWhitespace() || it == '-' }.uppercase()

data class Parcel(
    val id: String,
    val name: String,
    val trackingNumber: String,
    val carrier: Carrier,
    val sourceId: String? = null,
    val status: TrackingStatus = TrackingStatus.UNKNOWN,
    val etaDate: LocalDate? = null,
    val etaTime: LocalTime? = null,
    val events: List<TrackingEvent> = emptyList(),
    val latestLocation: String? = null,
    val isArchived: Boolean = false,
    val createdAt: Instant,
    val lastRefreshedAt: Instant? = null,
) {
    val normalizedTracking: String get() = normalizeTracking(trackingNumber)
}

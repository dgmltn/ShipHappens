package com.shiphappens.source.webview

import com.shiphappens.domain.TrackingEvent
import com.shiphappens.domain.TrackingSnapshot
import com.shiphappens.domain.TrackingStatus
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable

/**
 * Canonical extraction output. Every extraction layer (captured provider API JSON after
 * provider-side parsing, DOM extraction JS, future AI extractor) produces this one shape, so
 * everything downstream of the JS bridge is provider-agnostic Kotlin.
 *
 * `status` values are [TrackingStatus] enum names; `timestamp` is an ISO-8601 instant;
 * `etaDate` is an ISO local date; `etaWindowStart`/`etaWindowEnd` are ISO local times bounding the
 * delivery window (start omitted => open-ended "by <end>"). Unknown or malformed values degrade gracefully
 * (UNKNOWN status, dropped event, null eta) rather than failing the whole scrape.
 */
@Serializable
data class ScrapedTracking(
    val status: String,
    val etaDate: String? = null,
    val etaWindowStart: String? = null,
    val etaWindowEnd: String? = null,
    val location: String? = null,
    val events: List<ScrapedEvent> = emptyList(),
)

@Serializable
data class ScrapedEvent(
    val timestamp: String,
    val description: String,
    val location: String? = null,
    val status: String? = null,
)

private fun statusOrNull(name: String?): TrackingStatus? =
    name?.let { n -> TrackingStatus.entries.firstOrNull { it.name == n } }

fun ScrapedTracking.toSnapshot(): TrackingSnapshot = TrackingSnapshot(
    status = statusOrNull(status) ?: TrackingStatus.UNKNOWN,
    etaDate = etaDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    etaWindowStart = etaWindowStart?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
    etaWindowEnd = etaWindowEnd?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
    latestLocation = location,
    events = events.mapNotNull { e ->
        runCatching { Instant.parse(e.timestamp) }.getOrNull()?.let { ts ->
            TrackingEvent(timestamp = ts, description = e.description, location = e.location, status = statusOrNull(e.status))
        }
    },
)

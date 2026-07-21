package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
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
 * delivery window (start omitted => open-ended "by <end>"). `etaWindowText` is the raw window phrase
 * as it appeared on the page ("3:00 PM - 5:00 PM", "by 10 PM") for extractors that cannot normalize
 * times themselves; it is parsed only when the ISO fields are absent, and is never persisted.
 * Unknown or malformed values degrade gracefully
 * (UNKNOWN status, dropped event, null eta) rather than failing the whole scrape.
 */
@Serializable
data class ScrapedTracking(
    val status: String,
    val etaDate: String? = null,
    val etaWindowStart: String? = null,
    val etaWindowEnd: String? = null,
    val etaWindowText: String? = null,
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

/**
 * Fills the fields a rich hop result is missing from the [coarse] order-page fallback, so an
 * impoverished ship-track page (UNKNOWN status, no ETA — tracker-selector drift, or a shipment the
 * tracker hasn't caught up on) can't shadow an ETA the order page already knew and blank the card to
 * "--". Rich still wins for every field it actually carries; only nulls (and an UNKNOWN status) are
 * backfilled. No-op when there is no coarse fallback.
 */
internal fun ScrapedTracking.backfilledFrom(coarse: ScrapedTracking?): ScrapedTracking {
    if (coarse == null) return this
    val richStatus = statusOrNull(status)
    return copy(
        status = if (richStatus == null || richStatus == TrackingStatus.UNKNOWN) coarse.status else status,
        etaDate = etaDate ?: coarse.etaDate,
        etaWindowStart = etaWindowStart ?: coarse.etaWindowStart,
        etaWindowEnd = etaWindowEnd ?: coarse.etaWindowEnd,
        etaWindowText = etaWindowText ?: coarse.etaWindowText,
    )
}

fun ScrapedTracking.toSnapshot(): TrackingSnapshot {
    val start = etaWindowStart?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    val end = etaWindowEnd?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    // Explicit ISO times win; free text is the fallback for DOM extractors (see EtaWindowParser).
    val window = if (start != null || end != null) EtaWindow(start, end) else parseEtaWindow(etaWindowText)
    return TrackingSnapshot(
        status = statusOrNull(status) ?: TrackingStatus.UNKNOWN,
        etaDate = etaDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        etaWindowStart = window?.start,
        etaWindowEnd = window?.end,
        latestLocation = location,
        events = events.mapNotNull { e ->
            runCatching { Instant.parse(e.timestamp) }.getOrNull()?.let { ts ->
                TrackingEvent(timestamp = ts, description = e.description, location = e.location, status = statusOrNull(e.status))
            }
        },
    )
}

package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import com.dgmltn.shiphappens.source.webview.isDelayedWording
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class DhlEcsResponse(val packages: List<DhlEcsPackage> = emptyList())

@Serializable private data class DhlEcsPackage(
    val status: String? = null,
    val estimatedDeliveryDate: String? = null,
    val events: List<DhlEcsEvent> = emptyList(),
)

@Serializable private data class DhlEcsEvent(
    val date: String? = null,
    val time: String? = null,
    val timeZone: String? = null,
    val primaryEventDescription: String? = null,
    val location: String? = null,
)

/**
 * Maps api.dhlecs.com/webtrack/v4/tracking JSON (the SPA's only data call, captured in-page) to
 * the canonical [ScrapedTracking]. Tolerant like the other API parsers: every field optional,
 * unknown vocabulary degrades to UNKNOWN, decode failure returns null. An unknown number is an
 * in-band `packages: []` on HTTP 200, so null routes it to the DOM fallback's NotFound.
 *
 * Recon 2026-09-03 observed one live package (status "Electronic Notification", event
 * "LABEL CREATED" in ET); the rest of the vocabulary comes from the SPA's locale file and is
 * confirmed during device QA across a package's lifecycle.
 */
object DhlEcsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): ScrapedTracking? {
        val response = runCatching { json.decodeFromString<DhlEcsResponse>(body) }.getOrNull() ?: return null
        val pkg = response.packages.firstOrNull() ?: return null

        val events = pkg.events.mapNotNull { e ->
            val rawDescription = e.primaryEventDescription?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val timestamp = toIsoInstant(e.date, e.time, e.timeZone) ?: return@mapNotNull null
            ScrapedEvent(
                timestamp = timestamp,
                description = describe(rawDescription),
                location = e.location?.takeIf { it.isNotBlank() },
                status = classifyDhlEcsStatus(rawDescription)?.name,
            )
        }.sortedBy { it.timestamp }

        val status = classifyDhlEcsStatus(pkg.status)
            ?: events.lastOrNull { it.status != null }?.status?.let { TrackingStatus.valueOf(it) }
            ?: TrackingStatus.UNKNOWN

        return ScrapedTracking(
            status = status.name,
            etaDate = pkg.estimatedDeliveryDate?.take(10)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.toString(),
            location = events.lastOrNull { it.location != null }?.location,
            // Only the live status or the NEWEST event may assert a delay: delay events stay in
            // the history for the life of the shipment, and non-null delayNote IS the delay flag —
            // scanning older rows would keep a delivered package flagged as delayed forever. A
            // still-live delay whose event was followed by a routine scan under-reports here; the
            // carrier's status wording is expected to carry it in that window.
            delayNote = pkg.status?.takeIf { isDelayedWording(it) }
                ?: events.lastOrNull()?.description?.takeIf { isDelayedWording(it) },
            events = events,
        )
    }

    // The API stamps events with a US zone abbreviation ("ET"), not an offset; IANA zones keep
    // the DST arithmetic right. An unknown abbreviation falls back to the device zone — the
    // documented UPS/AMZL trade-off (only ordering and dates surface in UI).
    private val ZONES = mapOf(
        "ET" to "America/New_York", "EST" to "America/New_York", "EDT" to "America/New_York",
        "CT" to "America/Chicago", "CST" to "America/Chicago", "CDT" to "America/Chicago",
        "MT" to "America/Denver", "MST" to "America/Denver", "MDT" to "America/Denver",
        "PT" to "America/Los_Angeles", "PST" to "America/Los_Angeles", "PDT" to "America/Los_Angeles",
        "AKT" to "America/Anchorage", "AKST" to "America/Anchorage", "AKDT" to "America/Anchorage",
        "HT" to "Pacific/Honolulu", "HST" to "Pacific/Honolulu",
        // Puerto Rico scans; AST has no DST. (Arizona summer scans stamped "MST" read an hour
        // off via America/Denver — accepted, same class of skew as the device-zone fallback.)
        "AT" to "America/Puerto_Rico", "AST" to "America/Puerto_Rico",
        "UTC" to "UTC", "GMT" to "UTC", "Z" to "UTC",
    )

    private fun toIsoInstant(date: String?, time: String?, zoneAbbreviation: String?): String? {
        val localDate = runCatching { LocalDate.parse(date ?: return null) }.getOrNull() ?: return null
        val localTime = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime(0, 0)
        val zone = ZONES[zoneAbbreviation?.trim()?.uppercase()]?.let { TimeZone.of(it) }
            ?: TimeZone.currentSystemDefault()
        return LocalDateTime(localDate, localTime).toInstant(zone).toString()
    }

    // The API SCREAMS its prose ("ARRIVAL DHL ECOMMERCE FACILITY"); fold to sentence case,
    // keeping carrier acronyms readable.
    private fun describe(raw: String): String =
        raw.trim().lowercase().replaceFirstChar { it.uppercase() }
            .replace(Regex("\\b(dhl|usps)\\b", RegexOption.IGNORE_CASE)) { it.value.uppercase() }
            .replace(Regex("\\becommerce\\b", RegexOption.IGNORE_CASE), "eCommerce")
}

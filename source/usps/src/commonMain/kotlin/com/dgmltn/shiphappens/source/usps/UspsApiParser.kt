package com.dgmltn.shiphappens.source.usps

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.source.webview.EtaWindow
import com.dgmltn.shiphappens.source.webview.assembleSnapshot
import com.dgmltn.shiphappens.source.webview.parseAnyDate
import com.dgmltn.shiphappens.source.webview.parseTimeOfDay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

// PROVISIONAL DTOs (Akamai blocked off-device capture of the real in-page API — see design
// spec). Field vocabulary follows USPS's tracking-API naming; live QA replaces the recorded
// fixture and adjusts these to the captured body.
@Serializable private data class UspsTrackResponse(
    val statusCategory: String? = null,
    val statusSummary: String? = null,
    val expectedDeliveryDate: String? = null,
    val expectedDeliveryTime: String? = null,
    val trackingEvents: List<UspsEvent>? = null,
)

@Serializable private data class UspsEvent(
    val eventType: String? = null,
    val eventTimestamp: String? = null,
    val eventCity: String? = null,
    val eventState: String? = null,
)

/**
 * Maps tools.usps.com's in-page tracking API JSON to a [TrackingSnapshot]. Same contract as
 * UpsApiParser: every field optional, unknown wording degrades to UNKNOWN, null for bodies
 * that aren't tracking JSON at all. Event timestamps without a zone are interpreted in the
 * device zone (same documented tradeoff as UPS).
 */
object UspsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): TrackingSnapshot? {
        val r = runCatching { json.decodeFromString<UspsTrackResponse>(body) }.getOrNull() ?: return null
        if (r.statusCategory == null && r.trackingEvents.isNullOrEmpty()) return null  // foreign JSON
        val zone = TimeZone.currentSystemDefault()
        val events = r.trackingEvents.orEmpty().mapNotNull { e ->
            val type = e.eventType ?: return@mapNotNull null
            val at = parseTimestamp(e.eventTimestamp, zone) ?: return@mapNotNull null
            TrackingEvent(timestamp = at, description = type, location = locationOf(e), status = USPS_VOCABULARY.classify(type))
        }
        return assembleSnapshot(
            vocabulary = USPS_VOCABULARY,
            headline = r.statusCategory ?: r.statusSummary,
            events = events,
            etaDate = parseAnyDate(r.expectedDeliveryDate),
            etaWindow = EtaWindow(null, parseTimeOfDay(r.expectedDeliveryTime)),
        )
    }

    private fun locationOf(e: UspsEvent): String? =
        listOfNotNull(e.eventCity?.trim()?.takeIf { it.isNotEmpty() }, e.eventState?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(", ").takeIf { it.isNotEmpty() }

    /** ISO instant ("...Z") or zoneless ISO local datetime, device zone. */
    private fun parseTimestamp(raw: String?, zone: TimeZone): Instant? {
        val s = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        runCatching { Instant.parse(s) }.getOrNull()?.let { return it }
        return runCatching { LocalDateTime.parse(s).toInstant(zone) }.getOrNull()
    }
}

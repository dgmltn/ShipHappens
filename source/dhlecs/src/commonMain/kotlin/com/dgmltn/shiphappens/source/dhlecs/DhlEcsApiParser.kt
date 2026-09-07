package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.source.webview.assembleSnapshot
import com.dgmltn.shiphappens.source.webview.eventAt
import com.dgmltn.shiphappens.source.webview.headlineThenNewestEvent
import com.dgmltn.shiphappens.source.webview.parseAnyDate
import com.dgmltn.shiphappens.source.webview.zoneForAbbreviation
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
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
 * the canonical [TrackingSnapshot]. Tolerant like the other API parsers: every field optional,
 * unknown vocabulary degrades to UNKNOWN, decode failure returns null. An unknown number is an
 * in-band `packages: []` on HTTP 200, so null routes it to the DOM fallback's NotFound.
 *
 * Recon 2026-09-03 observed one live package (status "Electronic Notification", event
 * "LABEL CREATED" in ET); the rest of the vocabulary comes from the SPA's locale file and is
 * confirmed during device QA across a package's lifecycle.
 */
object DhlEcsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): TrackingSnapshot? {
        val response = runCatching { json.decodeFromString<DhlEcsResponse>(body) }.getOrNull() ?: return null
        val pkg = response.packages.firstOrNull() ?: return null
        val device = TimeZone.currentSystemDefault()
        val events = pkg.events.mapNotNull { e ->
            val rawDescription = e.primaryEventDescription?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val date = e.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val time = e.time?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
            TrackingEvent(
                timestamp = eventAt(date, time, zoneForAbbreviation(e.timeZone) ?: device),
                description = describe(rawDescription),
                location = e.location?.takeIf { it.isNotBlank() },
                status = DHLECS_VOCABULARY.classify(rawDescription),
            )
        }
        return assembleSnapshot(
            vocabulary = DHLECS_VOCABULARY,
            headline = pkg.status,
            events = events,
            etaDate = parseAnyDate(pkg.estimatedDeliveryDate),
            // Only the live status or the NEWEST event may assert a delay — the shared rule.
            delayNote = headlineThenNewestEvent(DHLECS_VOCABULARY, pkg.status, events),
        )
    }

    // The API SCREAMS its prose ("ARRIVAL DHL ECOMMERCE FACILITY"); fold to sentence case,
    // keeping carrier acronyms readable.
    private fun describe(raw: String): String =
        raw.trim().lowercase().replaceFirstChar { it.uppercase() }
            .replace(Regex("\\b(dhl|usps)\\b", RegexOption.IGNORE_CASE)) { it.value.uppercase() }
            .replace(Regex("\\becommerce\\b", RegexOption.IGNORE_CASE), "eCommerce")
}

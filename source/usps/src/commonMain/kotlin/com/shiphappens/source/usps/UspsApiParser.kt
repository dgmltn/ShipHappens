package com.shiphappens.source.usps

import com.shiphappens.source.webview.ScrapedEvent
import com.shiphappens.source.webview.ScrapedTracking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
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
 * Maps tools.usps.com's in-page tracking API JSON to the canonical [ScrapedTracking].
 * Same contract as UpsApiParser: every field optional, unknown wording degrades to UNKNOWN,
 * null for bodies that aren't tracking JSON at all. Event timestamps without a zone are
 * interpreted in the device zone (same documented tradeoff as UPS).
 */
object UspsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): ScrapedTracking? {
        val r = runCatching { json.decodeFromString<UspsTrackResponse>(body) }.getOrNull() ?: return null
        if (r.statusCategory == null && r.trackingEvents.isNullOrEmpty()) return null  // foreign JSON
        val tz = TimeZone.currentSystemDefault()
        val events = r.trackingEvents.orEmpty().mapNotNull { e ->
            val type = e.eventType ?: return@mapNotNull null
            val ts = parseTimestamp(e.eventTimestamp, tz) ?: return@mapNotNull null
            ts to ScrapedEvent(
                timestamp = ts.toString(),
                description = type,
                location = locationOf(e),
                status = classify(type).takeIf { it != "UNKNOWN" },
            )
        }.sortedBy { it.first }.map { it.second }  // USPS is newest-first; domain expects ascending
        val overall = classify(r.statusCategory ?: r.statusSummary ?: "").takeIf { it != "UNKNOWN" }
            ?: events.lastOrNull()?.status ?: "UNKNOWN"
        return ScrapedTracking(
            status = overall,
            etaDate = parseDate(r.expectedDeliveryDate)?.toString(),
            etaWindowEnd = parseTime(r.expectedDeliveryTime)?.toString(),
            location = events.lastOrNull { it.location != null }?.location,
            events = events,
        )
    }

    private fun locationOf(e: UspsEvent): String? =
        listOfNotNull(e.eventCity?.trim()?.takeIf { it.isNotEmpty() }, e.eventState?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(", ").takeIf { it.isNotEmpty() }

    /** ISO instant ("...Z") or zoneless ISO local datetime, device zone. */
    private fun parseTimestamp(raw: String?, tz: TimeZone): Instant? {
        val s = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        runCatching { Instant.parse(s) }.getOrNull()?.let { return it }
        return runCatching { LocalDateTime.parse(s).toInstant(tz) }.getOrNull()
    }

    /** "2026-07-16", "07/16/2026", or "Wednesday, July 16, 2026". */
    private fun parseDate(raw: String?): LocalDate? {
        val s = raw?.trim() ?: return null
        runCatching { LocalDate.parse(s) }.getOrNull()?.let { return it }
        Regex("""(\d{2})/(\d{2})/(\d{4})""").find(s)?.let { m ->
            val (mm, dd, yyyy) = m.destructured
            return runCatching { LocalDate(yyyy.toInt(), mm.toInt(), dd.toInt()) }.getOrNull()
        }
        val m = Regex("""([A-Za-z]+)\s+(\d{1,2}),\s*(\d{4})""").find(s) ?: return null
        val (monthName, dd, yyyy) = m.destructured
        val month = Month.entries.firstOrNull { it.name.equals(monthName, ignoreCase = true) } ?: return null
        return runCatching { LocalDate(yyyy.toInt(), month, dd.toInt()) }.getOrNull()
    }

    /** "20:00:00" (24h) or "8:00pm". */
    private fun parseTime(raw: String?): LocalTime? {
        val s = raw?.trim() ?: return null
        Regex("""^(\d{1,2}):(\d{2})\s*([ap])\.?m\.?$""", RegexOption.IGNORE_CASE).find(s)?.let { m ->
            val (h, min, ap) = m.destructured
            val hour24 = (h.toInt() % 12) + if (ap.lowercase() == "p") 12 else 0
            return runCatching { LocalTime(hour24, min.toInt()) }.getOrNull()
        }
        val m = Regex("""^(\d{1,2}):(\d{2})""").find(s) ?: return null
        val (h, min) = m.destructured
        return runCatching { LocalTime(h.toInt(), min.toInt()) }.getOrNull()
    }

    /** Keyword classification shared conceptually with the DOM extractor (spec §2). */
    private fun classify(text: String): String {
        val t = text.lowercase()
        return when {
            "out for delivery" in t -> "OUT_FOR_DELIVERY"
            "delivered" in t -> "DELIVERED"
            "alert" in t || "attempted" in t || "notice left" in t || "return" in t || "held" in t -> "EXCEPTION"
            "label created" in t || "pre-shipment" in t || "awaiting item" in t -> "LABEL_CREATED"
            "accepted" in t || "picked up" in t || "possession" in t -> "SHIPPED"
            "in transit" in t || "departed" in t || "arrived" in t || "moving through" in t || "processed" in t -> "IN_TRANSIT"
            else -> "UNKNOWN"
        }
    }
}

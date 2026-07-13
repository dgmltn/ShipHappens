package com.shiphappens.source.ups

import com.shiphappens.source.webview.ScrapedEvent
import com.shiphappens.source.webview.ScrapedTracking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable private data class UpsTrackResponse(val trackDetails: List<UpsTrackDetail>? = null)

@Serializable private data class UpsTrackDetail(
    val packageStatus: String? = null,
    val packageStatusType: String? = null,
    val scheduledDeliveryDate: String? = null,
    val shipmentProgressActivities: List<UpsActivity>? = null,
)

@Serializable private data class UpsActivity(
    val date: String? = null,
    val time: String? = null,
    val location: String? = null,
    val activityScan: String? = null,
)

/**
 * Maps ups.com's in-page tracking API JSON to the canonical [ScrapedTracking].
 * Field vocabulary is tolerant: every field optional, unknown wording degrades to UNKNOWN.
 * UPS reports local wall-clock times with no zone; we interpret them in the device zone —
 * imperfect for cross-zone shipments, but only event ordering and dates surface in the UI.
 */
object UpsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): ScrapedTracking? {
        val detail = runCatching { json.decodeFromString<UpsTrackResponse>(body) }
            .getOrNull()?.trackDetails?.firstOrNull() ?: return null
        val activities = detail.shipmentProgressActivities.orEmpty()
        val tz = TimeZone.currentSystemDefault()
        val events = activities.mapNotNull { a ->
            val date = parseUpsDate(a.date) ?: return@mapNotNull null
            val time = parseUpsTime(a.time) ?: LocalTime(0, 0)
            val scan = a.activityScan ?: return@mapNotNull null
            ScrapedEvent(
                timestamp = LocalDateTime(date, time).toInstant(tz).toString(),
                description = scan,
                location = a.location,
                status = classify(null, scan).takeIf { it != "UNKNOWN" },
            )
        }.reversed()  // UPS is newest-first; domain expects chronological ascending
        return ScrapedTracking(
            status = classify(detail.packageStatusType, detail.packageStatus ?: ""),
            etaDate = parseUpsDate(detail.scheduledDeliveryDate)?.toString(),
            location = activities.firstOrNull()?.location,
            events = events,
        )
    }

    /** "07/15/2026" -> LocalDate. */
    private fun parseUpsDate(raw: String?): LocalDate? {
        val m = Regex("""(\d{2})/(\d{2})/(\d{4})""").find(raw ?: "") ?: return null
        val (mm, dd, yyyy) = m.destructured
        return runCatching { LocalDate(yyyy.toInt(), mm.toInt(), dd.toInt()) }.getOrNull()
    }

    /** "8:15 A.M." / "12:07 P.M." -> LocalTime. */
    private fun parseUpsTime(raw: String?): LocalTime? {
        val m = Regex("""(\d{1,2}):(\d{2})\s*([AP])\.?M\.?""", RegexOption.IGNORE_CASE).find(raw ?: "") ?: return null
        val (h, min, ap) = m.destructured
        val hour24 = (h.toInt() % 12) + if (ap.uppercase() == "P") 12 else 0
        return runCatching { LocalTime(hour24, min.toInt()) }.getOrNull()
    }

    private fun classify(typeCode: String?, text: String): String = when (typeCode?.uppercase()) {
        "M" -> "LABEL_CREATED"
        "P" -> "SHIPPED"
        "I" -> "IN_TRANSIT"
        "O" -> "OUT_FOR_DELIVERY"
        "D" -> "DELIVERED"
        "X" -> "EXCEPTION"
        else -> {
            val t = text.lowercase()
            when {
                "out for delivery" in t -> "OUT_FOR_DELIVERY"
                "delivered" in t -> "DELIVERED"
                "exception" in t || "action required" in t || "attempt" in t -> "EXCEPTION"
                "label" in t || "not received" in t || "order processed" in t -> "LABEL_CREATED"
                "origin scan" in t || "pickup" in t || "picked up" in t -> "SHIPPED"
                "on the way" in t || "in transit" in t || "departed" in t || "arrived" in t -> "IN_TRANSIT"
                else -> "UNKNOWN"
            }
        }
    }
}

package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.EtaWindow
import com.dgmltn.shiphappens.source.webview.assembleSnapshot
import com.dgmltn.shiphappens.source.webview.eventAt
import com.dgmltn.shiphappens.source.webview.parseCompactDate
import com.dgmltn.shiphappens.source.webview.parseNumericMdyDate
import com.dgmltn.shiphappens.source.webview.parseTimeOfDay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant

@Serializable private data class UpsTrackResponse(val trackDetails: List<UpsTrackDetail>? = null)

@Serializable private data class UpsTrackDetail(
    val packageStatus: String? = null,
    val packageStatusType: String? = null,
    // UPS's plain-English gloss on the current status ("Due to weather, your package is delayed
    // by one business day."); present on delayed and exception shipments, absent otherwise.
    val simplifiedText: String? = null,
    // Current ups.com shape: scheduled delivery date "sdd" (compact "YYYYMMDD") bounded by
    // "sdst"/"sdt" ("HH:MM:SS", window start/end). "scheduledDeliveryDate" (MM/DD/YYYY) is the
    // older field, kept as a fallback.
    val sdd: String? = null,
    val sdst: String? = null,
    val sdt: String? = null,
    val scheduledDeliveryDate: String? = null,
    val shipmentProgressActivities: List<UpsActivity>? = null,
)

@Serializable private data class UpsActivity(
    val date: String? = null,
    val time: String? = null,
    val location: String? = null,
    val activityScan: String? = null,
    // The same moment pinned to UTC ("20261001" / "11:24:00"), and the facility's offset from it
    // ("+08:00"); "date"/"time" above are local to the facility.
    val gmtDate: String? = null,
    val gmtTime: String? = null,
    val gmtOffset: String? = null,
)

/**
 * Maps ups.com's in-page tracking API JSON to a [TrackingSnapshot]. Field vocabulary is
 * tolerant: every field optional, unknown wording degrades to UNKNOWN. Scan times are the
 * UTC instant UPS reports alongside each facility-local time, so a route through several time
 * zones keeps its real order; scans without one are read in the device zone.
 */
object UpsApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): TrackingSnapshot? {
        val detail = runCatching { json.decodeFromString<UpsTrackResponse>(body) }
            .getOrNull()?.trackDetails?.firstOrNull() ?: return null
        val activities = detail.shipmentProgressActivities.orEmpty()
        val zone = TimeZone.currentSystemDefault()
        val events = activities.mapNotNull { a ->
            val date = parseNumericMdyDate(a.date) ?: return@mapNotNull null
            val scan = a.activityScan ?: return@mapNotNull null
            TrackingEvent(
                timestamp = a.instant(date, zone),
                description = scan,
                location = a.location,
                status = UPS_VOCABULARY.classify(scan),
            )
        }
        return assembleSnapshot(
            vocabulary = UPS_VOCABULARY,
            // Status text takes precedence: live ups.com keeps packageStatusType "I" (a coarse
            // in-transit bucket) even when the package is out for delivery — only the text is
            // specific. The type code is the fallback for unrecognized or reworded statuses.
            headline = detail.packageStatus,
            events = events,
            etaDate = parseCompactDate(detail.sdd) ?: parseNumericMdyDate(detail.scheduledDeliveryDate),
            etaWindow = EtaWindow(parseTimeOfDay(detail.sdst), parseTimeOfDay(detail.sdt)),
            location = activities.firstOrNull()?.location,  // UPS lists newest first
            // Delay rides alongside the stage rather than replacing it: "On the Way: Delayed" is
            // genuinely in transit, and genuinely late. Prefer UPS's reason sentence; the
            // headline itself is the fallback when there isn't one.
            delayNote = detail.packageStatus
                ?.takeIf { UPS_VOCABULARY.isDelayed(it) }
                ?.let { detail.simplifiedText?.takeIf(String::isNotBlank) ?: it },
            statusFallback = typeCodeStatus(detail.packageStatusType),
        )
    }

    /** Best available reading of a scan's moment: UTC, then local time less the facility's offset, then the device zone. */
    private fun UpsActivity.instant(localDate: LocalDate, deviceZone: TimeZone): Instant {
        val utcDate = parseCompactDate(gmtDate)
        val utcTime = gmtTime?.let { runCatching { LocalTime.parse(it.trim()) }.getOrNull() }
        if (utcDate != null && utcTime != null) return LocalDateTime(utcDate, utcTime).toInstant(TimeZone.UTC)
        val localTime = parseTimeOfDay(time)
        val offset = gmtOffset?.let { runCatching { UtcOffset.parse(it.trim()) }.getOrNull() }
        if (offset != null) return LocalDateTime(localDate, localTime ?: LocalTime(0, 0)).toInstant(offset)
        return eventAt(localDate, localTime, deviceZone)
    }

    private fun typeCodeStatus(code: String?): TrackingStatus? = when (code?.uppercase()) {
        "M" -> TrackingStatus.LABEL_CREATED
        "P" -> TrackingStatus.SHIPPED
        "I" -> TrackingStatus.IN_TRANSIT
        "O" -> TrackingStatus.OUT_FOR_DELIVERY
        "D" -> TrackingStatus.DELIVERED
        "X" -> TrackingStatus.EXCEPTION
        else -> null
    }
}

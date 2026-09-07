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
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
)

/**
 * Maps ups.com's in-page tracking API JSON to a [TrackingSnapshot]. Field vocabulary is
 * tolerant: every field optional, unknown wording degrades to UNKNOWN. UPS reports local
 * wall-clock times with no zone; we interpret them in the device zone — imperfect for
 * cross-zone shipments, but only event ordering and dates surface in the UI.
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
                timestamp = eventAt(date, parseTimeOfDay(a.time), zone),
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

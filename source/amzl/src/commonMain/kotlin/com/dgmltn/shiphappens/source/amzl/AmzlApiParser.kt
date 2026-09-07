package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.source.webview.assembleSnapshot
import com.dgmltn.shiphappens.source.webview.eventAt
import com.dgmltn.shiphappens.source.webview.humanizeToken
import com.dgmltn.shiphappens.source.webview.parseMonthNameDate
import com.dgmltn.shiphappens.source.webview.parseTimeOfDay
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// track.amazon.com's /api/tracker/{id} envelope double-encodes its two interesting fields:
// progressTracker and eventHistory arrive as JSON *strings* inside the outer JSON object.
@Serializable private data class AmzlEnvelope(
    val progressTracker: String? = null,
    val eventHistory: String? = null,
)

@Serializable private data class AmzlProgressTracker(
    val summary: AmzlSummary? = null,
    val errors: List<AmzlError> = emptyList(),
)

@Serializable private data class AmzlError(val errorCode: String? = null)

@Serializable private data class AmzlSummary(
    val status: String? = null,
    val metadata: AmzlMetadata? = null,
)

@Serializable private data class AmzlMetadata(
    val promisedDeliveryDate: AmzlDateValue? = null,
    val expectedDeliveryDate: AmzlDateValue? = null,
    val trackingStatus: AmzlStringValue? = null,
)

@Serializable private data class AmzlDateValue(val date: String? = null)
@Serializable private data class AmzlStringValue(val stringValue: String? = null)

@Serializable private data class AmzlEventHistoryDoc(
    val eventHistory: List<AmzlEvent> = emptyList(),
    val summary: AmzlHistorySummary? = null,
)

@Serializable private data class AmzlHistorySummary(val status: String? = null)

@Serializable private data class AmzlEvent(
    val eventCode: String? = null,
    val eventTime: String? = null,
    val location: AmzlLocation? = null,
)

// Field names are provisional — the recon package's location was {}; live-QA captures refine them.
@Serializable private data class AmzlLocation(
    val city: String? = null,
    val stateProvince: String? = null,
    val countryCode: String? = null,
)

/**
 * Maps track.amazon.com's in-page tracker API JSON to the canonical [TrackingSnapshot].
 * Tolerant like [the UPS parser]: every field optional, unknown vocabulary degrades to UNKNOWN,
 * decode failure returns null. The API reports local wall-clock times with no zone; we interpret
 * them in the device zone — the documented UPS trade-off (only ordering and dates surface in UI).
 *
 * Only CreationConfirmed/READY_FOR_RECEIVE were observed live (2026-08-11); the rest of the
 * vocabulary derives from the SPA's milestone string ids (swa_rex_intransit, swa_rex_ofd, …)
 * and is confirmed during device QA across a package's lifecycle.
 */
object AmzlApiParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): TrackingSnapshot? {
        val envelope = runCatching { json.decodeFromString<AmzlEnvelope>(body) }.getOrNull() ?: return null
        val tracker = envelope.progressTracker
            ?.let { runCatching { json.decodeFromString<AmzlProgressTracker>(it) }.getOrNull() }
        // TRACKING_ID_NOT_FOUND arrives as an in-band error on HTTP 200; parseApi can only say
        // snapshot-or-null, so null it is — the DOM fallback owns the NotFound outcome.
        if (tracker == null || tracker.errors.isNotEmpty()) return null
        val history = envelope.eventHistory
            ?.let { runCatching { json.decodeFromString<AmzlEventHistoryDoc>(it) }.getOrNull() }
        val summary = tracker.summary
        if (summary == null && history == null) return null

        val zone = TimeZone.currentSystemDefault()
        val events = history?.eventHistory.orEmpty().mapNotNull { e ->
            val code = e.eventCode ?: return@mapNotNull null
            val date = parseMonthNameDate(e.eventTime) ?: return@mapNotNull null
            TrackingEvent(
                timestamp = eventAt(date, parseTimeOfDay(e.eventTime), zone),
                description = describe(code),
                location = e.location?.render(),
                status = AMZL_VOCABULARY.classifyToken(code),
            )
        }
        val statusTokens = listOfNotNull(
            summary?.status,
            summary?.metadata?.trackingStatus?.stringValue,
            history?.summary?.status,
        )
        return assembleSnapshot(
            vocabulary = AMZL_VOCABULARY,
            // The first token that names a stage; humanized so the chain reads words.
            headline = statusTokens.firstOrNull { AMZL_VOCABULARY.classifyToken(it) != null }?.let { humanizeToken(it) },
            events = events,
            etaDate = (summary?.metadata?.promisedDeliveryDate?.date ?: summary?.metadata?.expectedDeliveryDate?.date)
                ?.let { parseMonthNameDate(it) },
            // The API carries codes, not prose ("DeliveryDelayed"), so the note reuses the same
            // CamelCase-splitting rendering the event descriptions get.
            delayNote = statusTokens.firstOrNull { AMZL_VOCABULARY.isDelayedToken(it) }?.let { describe(it) },
        )
    }

    private fun describe(code: String): String =
        EVENT_DESCRIPTIONS[code] ?: humanizeToken(code).replaceFirstChar { it.uppercase() }

    // The API carries localisation ids (swa_rex_*), not English text — the SPA translates
    // client-side. Known event codes map to short English; the fallback splits CamelCase.
    private val EVENT_DESCRIPTIONS = mapOf(
        "CreationConfirmed" to "Label created",
        "PickupDone" to "Package picked up",
        "OutForDelivery" to "Out for delivery",
        "Delivered" to "Delivered",
    )

    private fun AmzlLocation.render(): String? =
        listOfNotNull(city?.takeIf { it.isNotBlank() }, stateProvince?.takeIf { it.isNotBlank() })
            .joinToString(", ").ifEmpty { null }
}

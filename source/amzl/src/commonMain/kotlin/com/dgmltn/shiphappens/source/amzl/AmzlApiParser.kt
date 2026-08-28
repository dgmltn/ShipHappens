package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.source.webview.ScrapedEvent
import com.dgmltn.shiphappens.source.webview.ScrapedTracking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
 * Maps track.amazon.com's in-page tracker API JSON to the canonical [ScrapedTracking].
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

    fun parse(body: String): ScrapedTracking? {
        val envelope = runCatching { json.decodeFromString<AmzlEnvelope>(body) }.getOrNull() ?: return null
        val tracker = envelope.progressTracker
            ?.let { runCatching { json.decodeFromString<AmzlProgressTracker>(it) }.getOrNull() }
        // TRACKING_ID_NOT_FOUND arrives as an in-band error on HTTP 200; parseApi can only say
        // tracking-or-null, so null it is — the DOM extractor owns the NotFound outcome.
        if (tracker == null || tracker.errors.isNotEmpty()) return null
        val history = envelope.eventHistory
            ?.let { runCatching { json.decodeFromString<AmzlEventHistoryDoc>(it) }.getOrNull() }
        val summary = tracker.summary
        if (summary == null && history == null) return null

        val tz = TimeZone.currentSystemDefault()
        val events = history?.eventHistory.orEmpty().mapNotNull { e ->
            val code = e.eventCode ?: return@mapNotNull null
            val (date, time) = parseDateTime(e.eventTime) ?: return@mapNotNull null
            ScrapedEvent(
                timestamp = LocalDateTime(date, time).toInstant(tz).toString(),
                description = describe(code),
                location = e.location?.render(),
                status = classify(code).takeIf { it != UNKNOWN },
            )
        }.sortedBy { it.timestamp }

        val eta = (summary?.metadata?.promisedDeliveryDate?.date ?: summary?.metadata?.expectedDeliveryDate?.date)
            ?.let { parseDateTime(it)?.first }

        val statusTokens = listOfNotNull(
            summary?.status,
            summary?.metadata?.trackingStatus?.stringValue,
            history?.summary?.status,
        )
        val status = statusTokens.asSequence()
            .mapNotNull { s -> classify(s).takeIf { it != UNKNOWN } }.firstOrNull() ?: UNKNOWN

        return ScrapedTracking(
            status = status,
            etaDate = eta?.toString(),
            location = events.lastOrNull()?.location,
            // The API carries codes, not prose ("DeliveryDelayed"), so the note reuses the same
            // CamelCase-splitting rendering the event descriptions get.
            delayNote = statusTokens.firstOrNull { isDelayed(it) }?.let { describe(it) },
            events = events,
        )
    }

    private const val UNKNOWN = "UNKNOWN"

    /**
     * One classifier for both vocabularies — summary.status CamelCase ("CreationConfirmed") and
     * metadata/eventHistory SCREAMING_SNAKE ("READY_FOR_RECEIVE") — by lowercasing and stripping
     * underscores. Exception keywords are checked before "delivered" so "Undeliverable" and
     * "DeliveryAttempted" can't leak into DELIVERED.
     */
    /** Whether a status token reports a delay. Orthogonal to [classify] — see StatusVocabulary. */
    private fun isDelayed(raw: String?): Boolean = "delay" in (raw ?: "").lowercase().replace("_", "")

    private fun classify(raw: String?): String {
        val t = (raw ?: "").lowercase().replace("_", "")
        return when {
            t.isEmpty() -> UNKNOWN
            t == "creationconfirmed" || t == "readyforreceive" || "labelcreated" in t -> "LABEL_CREATED"
            t == "pickupdone" || t == "pickedup" || t == "shipped" || t == "packagereceived" -> "SHIPPED"
            "outfordelivery" in t || t == "ofd" -> "OUT_FOR_DELIVERY"
            "attempt" in t || "undeliverable" in t || "lost" in t || "damaged" in t ||
                "return" in t || "reject" in t -> "EXCEPTION"
            "delivered" in t -> "DELIVERED"
            "intransit" in t || "arrived" in t || "departed" in t -> "IN_TRANSIT"
            // No delay branch — a delay is a modifier, not a stage (2026-08-28, matching UPS and
            // Amazon). "InTransitDelayed" keeps the stage it names; a bare "Delayed" token is
            // UNKNOWN, leaving the stage to the event history or the stored status.
            else -> UNKNOWN
        }
    }

    // The API carries localisation ids (swa_rex_*), not English text — the SPA translates
    // client-side. Known event codes map to short English; the fallback splits CamelCase.
    private val EVENT_DESCRIPTIONS = mapOf(
        "CreationConfirmed" to "Label created",
        "PickupDone" to "Package picked up",
        "OutForDelivery" to "Out for delivery",
        "Delivered" to "Delivered",
    )

    private fun describe(code: String): String =
        EVENT_DESCRIPTIONS[code]
            ?: code.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").lowercase()
                .replaceFirstChar { it.uppercase() }

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    // "Aug 13, 2026, 3:00:00 AM" (time optional). NBSP/U+202F are normalized to plain spaces
    // first — newer date formatters insert a narrow no-break space before AM/PM.
    private val DATE_TIME = Regex(
        """([A-Za-z]{3,9}) (\d{1,2}), (\d{4})(?:, (\d{1,2}):(\d{2})(?::(\d{2}))? ?([AP])\.?M\.?)?""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseDateTime(raw: String?): Pair<LocalDate, LocalTime>? {
        val cleaned = (raw ?: "").replace('\u00A0', ' ').replace('\u202F', ' ')
        val m = DATE_TIME.find(cleaned) ?: return null
        val month = MONTHS.indexOf(m.groupValues[1].take(3).lowercase()) + 1
        if (month == 0) return null
        val date = runCatching { LocalDate(m.groupValues[3].toInt(), month, m.groupValues[2].toInt()) }
            .getOrNull() ?: return null
        val time = if (m.groupValues[4].isEmpty()) LocalTime(0, 0) else runCatching {
            val hour = m.groupValues[4].toInt() % 12 + if (m.groupValues[7].equals("P", ignoreCase = true)) 12 else 0
            LocalTime(hour, m.groupValues[5].toInt(), m.groupValues[6].toIntOrNull() ?: 0)
        }.getOrNull() ?: LocalTime(0, 0)
        return date to time
    }

    private fun AmzlLocation.render(): String? =
        listOfNotNull(city?.takeIf { it.isNotBlank() }, stateProvince?.takeIf { it.isNotBlank() })
            .joinToString(", ").ifEmpty { null }
}

package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate

/**
 * The fallback ladder every carrier used to copy, run once.
 *
 * Status: the [headline] as [vocabulary] reads it, else the newest event that classified, else
 * the carrier's [statusFallback] (UPS's type code), else UNKNOWN. Location: the explicit
 * [location] banner, else the newest event that names a place. Window: explicit bounds in
 * [etaWindow] (ignored when both are null), else [etaWindowText] parsed all-or-nothing.
 * [events] may arrive in any order; the snapshot's are ascending.
 */
fun assembleSnapshot(
    vocabulary: StatusVocabulary,
    headline: String?,
    events: List<TrackingEvent> = emptyList(),
    etaDate: LocalDate? = null,
    etaWindow: EtaWindow? = null,
    etaWindowText: String? = null,
    location: String? = null,
    delayNote: String? = null,
    statusFallback: TrackingStatus? = null,
): TrackingSnapshot {
    val ordered = events.sortedBy { it.timestamp }
    val window = etaWindow?.takeIf { it.start != null || it.end != null } ?: parseEtaWindow(etaWindowText)
    return TrackingSnapshot(
        status = vocabulary.classify(headline)
            ?: ordered.lastOrNull { it.status != null }?.status
            ?: statusFallback
            ?: TrackingStatus.UNKNOWN,
        events = ordered,
        etaDate = etaDate,
        etaWindowStart = window?.start,
        etaWindowEnd = window?.end,
        latestLocation = location ?: ordered.lastOrNull { it.location != null }?.location,
        delayNote = delayNote,
    )
}

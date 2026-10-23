package com.dgmltn.shiphappens.data

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.TrackingEvent
import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlin.time.Duration.Companion.hours

/**
 * One snapshot from a parcel's own carrier plus the carrier it was handed to.
 *
 * The side whose newest scan is later (the secondary on a tie) is treated as the one that
 * knows the parcel's current state: its status, location and delay wording win, the other
 * side's fill gaps. DELIVERED from
 * either side always wins. The delivering carrier's ETA wins whenever it has one, with its own
 * window; otherwise the primary's date and window travel together. Events are the union, with
 * the secondary's rows tagged by [secondaryCarrier] and rows both sides reported kept once.
 */
fun mergeSnapshots(primary: TrackingSnapshot, secondary: TrackingSnapshot, secondaryCarrier: Carrier): TrackingSnapshot {
    val theirs = secondary.events.map { it.copy(carrierCode = secondaryCarrier.code) }
    val events = unionEvents(primary.events, theirs).sortedBy { it.timestamp }
    val primaryNewest = primary.events.maxOfOrNull { it.timestamp }
    val secondaryNewest = secondary.events.maxOfOrNull { it.timestamp }
    // A tie goes to the secondary: the physical carrier's report, which the primary mirrors.
    val secondaryLeads = when {
        secondaryNewest == null -> false
        primaryNewest == null -> true
        else -> secondaryNewest >= primaryNewest
    }
    val (newest, other) = if (secondaryLeads) secondary to primary else primary to secondary
    val status = when {
        primary.status == TrackingStatus.DELIVERED || secondary.status == TrackingStatus.DELIVERED -> TrackingStatus.DELIVERED
        newest.status != TrackingStatus.UNKNOWN -> newest.status
        else -> other.status
    }
    val etaSide = if (secondary.etaDate != null) secondary else primary
    return TrackingSnapshot(
        status = status,
        events = events,
        etaDate = etaSide.etaDate,
        etaWindowStart = etaSide.etaWindowStart,
        etaWindowEnd = etaSide.etaWindowEnd,
        latestLocation = newest.latestLocation ?: other.latestLocation ?: events.lastOrNull { it.location != null }?.location,
        delayNote = newest.delayNote ?: other.delayNote,
        handoff = primary.handoff,
    )
}

/**
 * The union of both carriers' rows, rows both reported kept once. A row is only ever matched
 * against the other carrier's rows — a carrier's own trail repeats its wording at every hub,
 * and those are distinct scans. The secondary's row is the physical carrier's own and is kept,
 * unless only the primary's names a place.
 */
private fun unionEvents(mine: List<TrackingEvent>, theirs: List<TrackingEvent>): List<TrackingEvent> {
    val kept = theirs.toMutableList()
    val extra = mutableListOf<TrackingEvent>()
    for (m in mine) {
        val i = theirs.indexOfFirst { t -> sameScan(m, t) }
        when {
            i < 0 -> extra += m
            m.location != null && theirs[i].location == null -> kept[i] = m
        }
    }
    return kept + extra
}

/**
 * Two carriers' reports of one scan: the same minute and stage, or the same wording within
 * the hour at a compatible place (equal, or one side not naming one).
 */
private fun sameScan(a: TrackingEvent, b: TrackingEvent): Boolean {
    val gap = (a.timestamp - b.timestamp).absoluteValue
    val sameMinute = a.timestamp.toEpochMilliseconds() / 60_000 == b.timestamp.toEpochMilliseconds() / 60_000
    val sameStage = a.status != null && a.status == b.status
    val samePlace = a.location == null || b.location == null || a.location == b.location
    return (sameMinute && sameStage) ||
        (gap <= 1.hours && samePlace && wording(a.description) == wording(b.description))
}

private fun wording(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

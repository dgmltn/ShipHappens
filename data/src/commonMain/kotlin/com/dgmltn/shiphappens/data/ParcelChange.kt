package com.dgmltn.shiphappens.data

import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlinx.datetime.LocalDate

/**
 * What one refresh actually changed about a parcel. Produced by every successful refresh;
 * only [isNotable] ones are worth a notification (see DailyRefreshRunner).
 */
data class ParcelChange(
    val parcelId: String,
    val parcelName: String,
    val statusBefore: TrackingStatus,
    val statusAfter: TrackingStatus,
    val etaBefore: LocalDate?,
    val etaAfter: LocalDate?,
    val delayNoteBefore: String? = null,
    val delayNoteAfter: String? = null,
) {
    val statusChanged: Boolean get() = statusBefore != statusAfter
    val etaChanged: Boolean get() = etaBefore != etaAfter

    /**
     * A delay that wasn't there before. Deliberately not "the note changed": a carrier that
     * reworks its own sentence mid-delay shouldn't re-notify, and a delay ENDING is good news
     * that the accompanying status/ETA move already covers.
     */
    val becameDelayed: Boolean get() = delayNoteBefore == null && delayNoteAfter != null

    /**
     * Worth telling the user about: a real status transition, a moved delivery date, or a
     * newly-announced delay.
     * The UNKNOWN/null guards are belt-and-braces — applySnapshot never writes UNKNOWN over a
     * known status and never nulls an existing etaDate — but they keep a future source that
     * regresses a parcel from waking anyone at 8am.
     */
    val isNotable: Boolean get() =
        (statusChanged && statusAfter != TrackingStatus.UNKNOWN) ||
            (etaChanged && etaAfter != null) ||
            // A delay is worth waking someone for even when the stage and date both hold: UPS
            // announces "Delayed" before it moves the date.
            becameDelayed
}

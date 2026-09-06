package com.dgmltn.shiphappens.data

import com.dgmltn.shiphappens.domain.Imminence
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
    /** The day this refresh ran — the vantage point [etaAfter] is near or far from. */
    val checkedOn: LocalDate,
    /** The day the previous refresh ran; null when this parcel has never been refreshed before. */
    val previouslyCheckedOn: LocalDate? = null,
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

    /** How near the delivery looked at the previous check. */
    val imminenceBefore: Imminence?
        get() = etaBefore?.let { Imminence.of(previouslyCheckedOn ?: checkedOn, it) }

    /** How near it looks now. */
    val imminenceAfter: Imminence? get() = etaAfter?.let { Imminence.of(checkedOn, it) }

    /**
     * The delivery moved into striking distance — "tomorrow", "today", or a day past due —
     * since the last check. Usually the calendar's doing rather than the carrier's: an ETA of
     * the 11th is unremarkable on the 5th and worth a nudge on the 10th, and nothing in the
     * snapshot differs between those two mornings.
     *
     * Comparing against the previous CHECK date rather than a stored flag buys three things for
     * free: a second refresh the same day can't re-announce (both sides land in one band), a
     * parcel left unchecked for days announces once on the jump instead of replaying each step,
     * and a package that stays late says so only on the first day past due. The cost is that a
     * foreground refresh can consume the crossing before the daily pass sees it — acceptable,
     * since someone who just opened the app has already read the date.
     */
    val becameImminent: Boolean get() {
        val after = imminenceAfter ?: return false
        return after.isWorthAnnouncing && after != imminenceBefore
    }

    /**
     * Worth telling the user about: a real status transition, a moved delivery date, a delivery
     * that has drawn near without the date moving, or a newly-announced delay.
     * The UNKNOWN/null guards are belt-and-braces — applySnapshot never writes UNKNOWN over a
     * known status and never nulls an existing etaDate — but they keep a future source that
     * regresses a parcel from waking anyone at 8am.
     */
    val isNotable: Boolean get() =
        (statusChanged && statusAfter != TrackingStatus.UNKNOWN) ||
            (etaChanged && etaAfter != null) ||
            becameImminent ||
            // A delay is worth waking someone for even when the stage and date both hold: UPS
            // announces "Delayed" before it moves the date.
            becameDelayed
}

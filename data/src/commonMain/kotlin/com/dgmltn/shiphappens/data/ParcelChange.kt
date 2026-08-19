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
) {
    val statusChanged: Boolean get() = statusBefore != statusAfter
    val etaChanged: Boolean get() = etaBefore != etaAfter

    /**
     * Worth telling the user about: a real status transition, or a moved delivery date.
     * The UNKNOWN/null guards are belt-and-braces — applySnapshot never writes UNKNOWN over a
     * known status and never nulls an existing etaDate — but they keep a future source that
     * regresses a parcel from waking anyone at 8am.
     */
    val isNotable: Boolean get() =
        (statusChanged && statusAfter != TrackingStatus.UNKNOWN) || (etaChanged && etaAfter != null)
}

package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus

/**
 * Fills the fields a rich hop result is missing from the [coarse] order-page fallback, so an
 * impoverished ship-track page (UNKNOWN status, no ETA — tracker-selector drift, or a shipment
 * the tracker hasn't caught up on) can't shadow an ETA the order page already knew and blank the
 * card to "--". Rich still wins for every field it carries; only nulls (and an UNKNOWN status)
 * are backfilled. No-op when there is no coarse fallback.
 */
internal fun TrackingSnapshot.backfilledFrom(coarse: TrackingSnapshot?): TrackingSnapshot {
    if (coarse == null) return this
    return copy(
        status = if (status == TrackingStatus.UNKNOWN) coarse.status else status,
        etaDate = etaDate ?: coarse.etaDate,
        etaWindowStart = etaWindowStart ?: coarse.etaWindowStart,
        etaWindowEnd = etaWindowEnd ?: coarse.etaWindowEnd,
        delayNote = delayNote ?: coarse.delayNote,
    )
}

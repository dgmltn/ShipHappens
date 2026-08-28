package com.dgmltn.shiphappens.data.notify

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.domain.TRACKING_STEP_LABELS
import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.domain.designFormat
import com.dgmltn.shiphappens.domain.stepIndex

/**
 * Every user-facing string the daily run can post. Shared by the Android and iOS notifiers so
 * both platforms say the same thing, and unit-testable without either.
 */
object NotificationText {

    fun title(change: ParcelChange): String = change.parcelName

    fun body(change: ParcelChange): String = when {
        // A new delay outranks both: it's the most specific thing we know, and the carrier's own
        // sentence usually explains the date move that would otherwise be reported bare.
        change.becameDelayed -> "Delayed — ${change.delayNoteAfter}"
        change.statusChanged -> statusLine(change)
        else -> etaLine(change)
    }

    fun signInTitle(sourceDisplayName: String): String = "Sign in to $sourceDisplayName"

    fun signInBody(sourceDisplayName: String): String =
        "Ship Happens can't update your $sourceDisplayName packages until you sign in again."

    private fun statusLine(change: ParcelChange): String {
        val label = statusLabel(change.statusAfter)
        // A delivered parcel's ETA is history; anything still moving benefits from the date.
        val eta = change.etaAfter
        return if (eta != null && change.statusAfter != TrackingStatus.DELIVERED) {
            "$label · arriving ${eta.designFormat()}"
        } else {
            label
        }
    }

    private fun etaLine(change: ParcelChange): String {
        val after = change.etaAfter?.designFormat() ?: return statusLabel(change.statusAfter)
        val before = change.etaBefore?.designFormat()
        return if (before == null) "Now arriving $after" else "Now arriving $after (was $before)"
    }

    private fun statusLabel(status: TrackingStatus): String = when (status) {
        TrackingStatus.EXCEPTION -> "Delivery exception — check the carrier"
        TrackingStatus.UNKNOWN -> "Updated"
        else -> TRACKING_STEP_LABELS[status.stepIndex]
    }
}

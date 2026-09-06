package com.dgmltn.shiphappens.data.notify

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.RefreshSummary
import com.dgmltn.shiphappens.domain.Imminence
import com.dgmltn.shiphappens.domain.TRACKING_STEP_LABELS
import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.domain.designFormat
import com.dgmltn.shiphappens.domain.stepIndex
import com.dgmltn.shiphappens.domain.weekdayName
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * Every user-facing string the daily run can post. Shared by the Android and iOS notifiers so
 * both platforms say the same thing, and unit-testable without either.
 *
 * Dates are spoken relatively whenever they're near — "today", "tomorrow", "in 3 days, on
 * Saturday" — because a glanced notification should answer "do I need to be home?" without the
 * reader counting off a calendar. Only a date beyond the week is given as a date.
 */
object NotificationText {

    fun title(change: ParcelChange): String = change.parcelName

    fun body(change: ParcelChange): String = when {
        // A new delay outranks the rest: it's the most specific thing we know, and the carrier's
        // own sentence usually explains the date move that would otherwise be reported bare.
        change.becameDelayed -> "Delayed — ${change.delayNoteAfter}"
        change.statusChanged -> statusLine(change)
        else -> etaLine(change)
    }

    fun runSummaryTitle(): String = "Background check finished"

    /**
     * One-line summary for the collapsed notification. "updated" counts notable diffs — the same
     * rule that decides per-parcel notifications, so the number matches the update rows posted.
     */
    fun runSummaryShort(summary: RefreshSummary): String {
        if (summary.attempted == 0) return "No packages needed checking"
        val checked = if (summary.attempted == 1) "1 package" else "${summary.attempted} packages"
        val failed = failedLine(summary)?.let { " · $it" } ?: ""
        return "Checked $checked · ${summary.changes.count { it.isNotable }} updated$failed"
    }

    /**
     * Expanded body: one bullet per checked package with where it stands — a nearby ETA as
     * relative days, a far one as the date, and the stage when the carrier gave no date.
     */
    fun runSummaryBody(summary: RefreshSummary): String {
        if (summary.attempted == 0) return "No packages needed checking"
        val lines = summary.changes.map { "• ${it.parcelName} — ${whereItStands(it)}" }
        return (lines + listOfNotNull(failedLine(summary))).joinToString("\n")
    }

    /** Terser than [arrivalTail] — a bullet has a package name in front of it already. */
    private fun whereItStands(change: ParcelChange): String {
        val eta = change.etaAfter ?: return statusLabel(change.statusAfter)
        return when (Imminence.of(change.checkedOn, eta)) {
            Imminence.TODAY -> "today"
            Imminence.TOMORROW -> "tomorrow"
            Imminence.THIS_WEEK -> "${change.checkedOn.daysUntil(eta)} days"
            // Far out, or already past — the date says it best.
            Imminence.LATER, Imminence.OVERDUE -> eta.designFormat()
        }
    }

    private fun failedLine(summary: RefreshSummary): String? = when {
        summary.failed == 0 -> null
        else -> "${summary.failed} failed" + (summary.firstFailureReason?.let { " ($it)" } ?: "")
    }

    fun runProgressTitle(): String = "Checking packages…"

    fun runFailedTitle(): String = "Background check failed"

    fun signInTitle(sourceDisplayName: String): String = "Sign in to $sourceDisplayName"

    fun signInBody(sourceDisplayName: String): String =
        "Ship Happens can't update your $sourceDisplayName packages until you sign in again."

    private fun statusLine(change: ParcelChange): String {
        val label = statusLabel(change.statusAfter)
        // A delivered parcel's ETA is history; anything still moving benefits from the date.
        val eta = change.etaAfter
        return if (eta != null && change.statusAfter != TrackingStatus.DELIVERED) {
            "$label · ${arrivalTail(change.checkedOn, eta)}"
        } else {
            label
        }
    }

    /**
     * The ETA line, which also carries every notification fired purely because the date drew
     * near (see `ParcelChange.becameImminent`). The "(was ...)" clause is hung off the ACTUAL
     * date moving — on an imminence crossing both dates are the same day, and naming it twice
     * would read as a change that never happened.
     */
    private fun etaLine(change: ParcelChange): String {
        val eta = change.etaAfter ?: return statusLabel(change.statusAfter)
        val phrase = arrivalTail(change.checkedOn, eta).replaceFirstChar { it.uppercase() }
        val before = change.etaBefore
        return if (change.etaChanged && before != null) "$phrase (was ${before.designFormat()})" else phrase
    }

    /** Lower-case so it reads both alone ("Arriving today") and appended ("In transit · arriving today"). */
    private fun arrivalTail(today: LocalDate, eta: LocalDate): String =
        when (Imminence.of(today, eta)) {
            Imminence.TODAY -> "arriving today"
            Imminence.TOMORROW -> "arriving tomorrow"
            Imminence.THIS_WEEK -> "arriving in ${today.daysUntil(eta)} days, on ${eta.weekdayName()}"
            Imminence.LATER -> "arriving ${eta.designFormat()}"
            Imminence.OVERDUE -> "late — was due ${eta.designFormat()}"
        }

    private fun statusLabel(status: TrackingStatus): String = when (status) {
        TrackingStatus.EXCEPTION -> "Delivery exception — check the carrier"
        TrackingStatus.UNKNOWN -> "Updated"
        else -> TRACKING_STEP_LABELS[status.stepIndex]
    }
}

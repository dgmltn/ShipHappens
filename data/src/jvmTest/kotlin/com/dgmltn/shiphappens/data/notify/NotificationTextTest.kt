package com.dgmltn.shiphappens.data.notify

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.data.RefreshSummary
import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.api.FailureReason
import kotlinx.datetime.LocalDate
import kotlin.test.*

class NotificationTextTest {
    private fun change(
        before: TrackingStatus = TrackingStatus.IN_TRANSIT,
        after: TrackingStatus = TrackingStatus.OUT_FOR_DELIVERY,
        etaBefore: LocalDate? = null,
        etaAfter: LocalDate? = null,
    ) = ParcelChange("p1", "Nike shoes", before, after, etaBefore, etaAfter)

    @Test fun title_is_the_parcel_name() {
        assertEquals("Nike shoes", NotificationText.title(change()))
    }

    @Test fun status_change_body_uses_the_shared_step_label() {
        assertEquals("Out for delivery", NotificationText.body(change()))
    }

    @Test fun status_change_with_eta_appends_the_arrival_date() {
        val c = change(etaBefore = LocalDate(2026, 8, 19), etaAfter = LocalDate(2026, 8, 19))
        assertEquals("Out for delivery · arriving Wed, Aug 19", NotificationText.body(c))
    }

    @Test fun delivered_reads_as_delivered() {
        assertEquals("Delivered", NotificationText.body(change(after = TrackingStatus.DELIVERED)))
    }

    @Test fun exception_has_its_own_wording() {
        assertEquals("Delivery exception — check the carrier",
            NotificationText.body(change(after = TrackingStatus.EXCEPTION)))
    }

    @Test fun eta_only_change_reads_as_a_moved_date() {
        val c = change(before = TrackingStatus.IN_TRANSIT, after = TrackingStatus.IN_TRANSIT,
            etaBefore = LocalDate(2026, 8, 19), etaAfter = LocalDate(2026, 8, 21))
        assertEquals("Now arriving Fri, Aug 21 (was Wed, Aug 19)", NotificationText.body(c))
    }

    @Test fun eta_appearing_for_the_first_time_omits_the_was_clause() {
        val c = change(before = TrackingStatus.IN_TRANSIT, after = TrackingStatus.IN_TRANSIT,
            etaBefore = null, etaAfter = LocalDate(2026, 8, 21))
        assertEquals("Now arriving Fri, Aug 21", NotificationText.body(c))
    }

    @Test fun sign_in_copy_names_the_source() {
        assertEquals("Sign in to UPS", NotificationText.signInTitle("UPS"))
        assertEquals("Ship Happens can't update your UPS packages until you sign in again.",
            NotificationText.signInBody("UPS"))
    }

    @Test fun run_summary_short_counts_checked_updated_and_failed() {
        val summary = RefreshSummary(
            attempted = 5, failed = 1, firstFailureReason = FailureReason.NETWORK,
            changes = listOf(change(), change(after = TrackingStatus.DELIVERED)),
        )
        assertEquals("Checked 5 packages · 2 updated · 1 failed (NETWORK)",
            NotificationText.runSummaryShort(summary))
    }

    @Test fun run_summary_short_with_one_quiet_parcel_reads_singular_and_clean() {
        val summary = RefreshSummary(attempted = 1, failed = 0)
        assertEquals("Checked 1 package · 0 updated", NotificationText.runSummaryShort(summary))
    }

    @Test fun run_summary_body_lists_each_package_with_its_eta() {
        val today = LocalDate(2026, 9, 3)
        fun pkg(name: String, eta: LocalDate?, status: TrackingStatus = TrackingStatus.IN_TRANSIT) =
            ParcelChange(name, name, status, status, eta, eta)
        val summary = RefreshSummary(
            attempted = 4, failed = 0,
            changes = listOf(
                pkg("Keyboard", LocalDate(2026, 9, 3)),
                pkg("Mouse", LocalDate(2026, 9, 4)),
                pkg("Boots", LocalDate(2026, 9, 5)),
                pkg("Desk", LocalDate(2026, 9, 12)),
            ),
        )
        assertEquals(
            """
            • Keyboard — today
            • Mouse — tomorrow
            • Boots — 2 days
            • Desk — Sat, Sep 12
            """.trimIndent(),
            NotificationText.runSummaryBody(summary, today),
        )
    }

    @Test fun run_summary_body_falls_back_to_the_status_when_there_is_no_eta() {
        val today = LocalDate(2026, 9, 3)
        val summary = RefreshSummary(
            attempted = 1, failed = 0,
            changes = listOf(ParcelChange("p1", "Keyboard",
                TrackingStatus.IN_TRANSIT, TrackingStatus.IN_TRANSIT, null, null)),
        )
        assertEquals("• Keyboard — In transit", NotificationText.runSummaryBody(summary, today))
    }

    @Test fun run_summary_body_appends_a_failure_line() {
        val today = LocalDate(2026, 9, 3)
        val summary = RefreshSummary(
            attempted = 2, failed = 1, firstFailureReason = FailureReason.NETWORK,
            changes = listOf(ParcelChange("p1", "Keyboard",
                TrackingStatus.IN_TRANSIT, TrackingStatus.IN_TRANSIT, LocalDate(2026, 9, 3), LocalDate(2026, 9, 3))),
        )
        assertEquals("• Keyboard — today\n1 failed (NETWORK)",
            NotificationText.runSummaryBody(summary, today))
    }

    @Test fun run_summary_with_nothing_to_check_says_so() {
        assertEquals("No packages needed checking",
            NotificationText.runSummaryBody(RefreshSummary(attempted = 0, failed = 0), LocalDate(2026, 9, 3)))
        assertEquals("No packages needed checking",
            NotificationText.runSummaryShort(RefreshSummary(attempted = 0, failed = 0)))
    }

    @Test fun a_new_delay_leads_with_the_delay_not_the_unchanged_stage() {
        val body = NotificationText.body(ParcelChange(
            parcelId = "p1", parcelName = "Boots",
            statusBefore = TrackingStatus.IN_TRANSIT, statusAfter = TrackingStatus.IN_TRANSIT,
            etaBefore = LocalDate(2026, 8, 28), etaAfter = LocalDate(2026, 8, 29),
            delayNoteAfter = "Due to weather, your package is delayed by one business day.",
        ))
        // The carrier's own sentence is the most useful thing we have; lead with it.
        assertEquals("Delayed — Due to weather, your package is delayed by one business day.", body)
    }

    @Test fun a_delay_with_no_reason_sentence_still_says_delayed() {
        val body = NotificationText.body(ParcelChange(
            parcelId = "p1", parcelName = "Boots",
            statusBefore = TrackingStatus.IN_TRANSIT, statusAfter = TrackingStatus.IN_TRANSIT,
            etaBefore = LocalDate(2026, 8, 28), etaAfter = LocalDate(2026, 8, 29),
            delayNoteAfter = "On the Way: Delayed",
        ))
        assertEquals("Delayed — On the Way: Delayed", body)
    }
}

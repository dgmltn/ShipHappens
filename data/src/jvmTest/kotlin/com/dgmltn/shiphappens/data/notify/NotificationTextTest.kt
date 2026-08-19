package com.dgmltn.shiphappens.data.notify

import com.dgmltn.shiphappens.data.ParcelChange
import com.dgmltn.shiphappens.domain.TrackingStatus
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
}

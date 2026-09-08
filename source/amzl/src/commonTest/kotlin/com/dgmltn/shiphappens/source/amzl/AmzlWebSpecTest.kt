package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.PageOutcome
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.snapshotOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AmzlWebSpecTest {

    @Test fun tracking_url_normalizes_and_uses_amazons_link_shape() {
        assertEquals(
            "https://track.amazon.com/tracking/TBA333593378975?trackingId=TBA333593378975",
            AmzlWebSpec.trackingUrl("tba 3335-9337 8975"),
        )
    }

    @Test fun origin_rules_are_confined_to_the_tracking_subdomain() {
        // cookieDomain must be track.amazon.com: bridge injection and goto hops stay off
        // www.amazon.com, and clearForDomain can never touch the Amazon orders login.
        assertEquals("track.amazon.com", AmzlWebSpec.cookieDomain)
    }

    @Test fun raw_status_headlines_classify_in_kotlin() {
        fun statusOf(text: String) =
            resolveTrackerPage(DomRaw(kind = "tracker", statusText = text), AMZL_PAGE).snapshotOrNull()?.status
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, statusOf("Out for delivery"))
        assertEquals(TrackingStatus.DELIVERED, statusOf("Delivered today"))
        assertEquals(TrackingStatus.IN_TRANSIT, statusOf("Arriving Wednesday"))
        assertEquals(TrackingStatus.IN_TRANSIT, statusOf("Package is in transit"))
        assertEquals(TrackingStatus.LABEL_CREATED, statusOf("We have your package details"))
        assertEquals(TrackingStatus.EXCEPTION, statusOf("Delivery attempted"))
        assertEquals(TrackingStatus.UNKNOWN, statusOf("Some brand-new wording"))
    }

    @Test fun shipped_headline_now_names_its_own_stage() {
        // The old DOM chain had no SHIPPED lane and read "Shipped" as IN_TRANSIT; the shared
        // vocabulary has one.
        assertEquals(TrackingStatus.SHIPPED, resolveTrackerPage(DomRaw(kind = "tracker", statusText = "Shipped"), AMZL_PAGE).snapshotOrNull()?.status)
    }

    @Test fun delay_wording_keeps_the_stage_and_reports_the_delay() {
        fun trackingOf(text: String) =
            resolveTrackerPage(DomRaw(kind = "tracker", statusText = text), AMZL_PAGE).snapshotOrNull()
        // A delay is a modifier, not a stage (2026-08-28, matching UPS/Amazon).
        assertEquals(TrackingStatus.IN_TRANSIT, trackingOf("Arriving Wednesday, delayed")?.status)
        assertEquals("Arriving Wednesday, delayed", trackingOf("Arriving Wednesday, delayed")?.delayNote)
        // Stage-less delay wording asserts no stage; UNKNOWN leaves it to the stored status.
        assertEquals(TrackingStatus.UNKNOWN, trackingOf("Delayed")?.status)
        assertEquals("Delayed", trackingOf("Delayed")?.delayNote)
        // A real problem still outranks a delay.
        assertEquals(TrackingStatus.EXCEPTION, trackingOf("Delivery attempted, delayed")?.status)
        assertNull(trackingOf("Out for delivery")?.delayNote)
    }

    @Test fun raw_without_status_text_routes_to_unparsed() {
        assertIs<PageOutcome.Empty>(resolveTrackerPage(DomRaw(kind = "tracker"), AMZL_PAGE))
        // The wordings the JS blob used to decide on (moved to Kotlin 2026-08-20).
        assertIs<PageOutcome.NotFound>(
            resolveTrackerPage(DomRaw(kind = "tracker", pageText = "We couldn't find this tracking number"), AMZL_PAGE),
        )
        assertIs<PageOutcome.NotFound>(
            resolveTrackerPage(DomRaw(kind = "tracker", pageText = "This tracking information is no longer available"), AMZL_PAGE),
        )
        assertNull(resolveTrackerPage(DomRaw(kind = "cards"), AMZL_PAGE))
    }
}

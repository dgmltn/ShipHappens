package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.PageOutcome
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.snapshotOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DhlEcsVocabularyTest {

    private fun statusOf(text: String) =
        resolveTrackerPage(DomRaw(kind = "tracker", statusText = text), DHLECS_PAGE).snapshotOrNull()?.status

    @Test fun status_headlines_classify_in_kotlin() {
        assertEquals(TrackingStatus.DELIVERED, statusOf("Delivered"))
        assertEquals(TrackingStatus.IN_TRANSIT, statusOf("En Route"))
        assertEquals(TrackingStatus.LABEL_CREATED, statusOf("Electronic Notification"))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, statusOf("Out for Delivery"))
        assertEquals(TrackingStatus.EXCEPTION, statusOf("Notice Left"))
        assertEquals(TrackingStatus.UNKNOWN, statusOf("Some brand-new wording"))
    }

    @Test fun delay_wording_keeps_the_stage_and_reports_the_delay() {
        fun trackingOf(text: String) =
            resolveTrackerPage(DomRaw(kind = "tracker", statusText = text), DHLECS_PAGE).snapshotOrNull()
        assertEquals(TrackingStatus.IN_TRANSIT, trackingOf("En Route - Delayed")?.status)
        assertEquals("En Route - Delayed", trackingOf("En Route - Delayed")?.delayNote)
        // Stage-less delay wording asserts no stage; UNKNOWN leaves it to the stored status.
        assertEquals(TrackingStatus.UNKNOWN, trackingOf("Possible Delivery Delay")?.status)
        assertEquals("Possible Delivery Delay", trackingOf("Possible Delivery Delay")?.delayNote)
        assertNull(trackingOf("En Route")?.delayNote)
    }

    @Test fun progress_rail_text_is_refused_not_classified() {
        // Live-QA capture 2026-09-03 (number fabricated): on the SPA's details route the fallback
        // selector matched a container whose text concatenates the current status WITH the rail's
        // static step labels — and "Delivered" in the rail overwrote a label-only package via
        // scrape-on-view. Ambiguous multi-stage text must route to Unparsed (null), leaving the
        // truth to the API capture that fires on the same page.
        val rail = "Tracking Number: 420300019261234500000000000042Electronic Notification" +
            "FromPLAINFIELD, IN 46168, USNotifiedEn RouteDeliveredNotifiedEn RouteDeliveredNotifiedEn RouteDelivered"
        assertNull(resolveTrackerPage(DomRaw(kind = "tracker", statusText = rail), DHLECS_PAGE))
        assertNull(resolveTrackerPage(DomRaw(kind = "tracker", statusText = "En Route Delivered"), DHLECS_PAGE))
    }

    @Test fun not_found_wording_routes_to_not_found() {
        // "Unfortunately, no results found." — webtrack's en-US locale copy (recon 2026-09-03).
        assertIs<PageOutcome.NotFound>(
            resolveTrackerPage(DomRaw(kind = "tracker", pageText = "Unfortunately, no results found. Please confirm"), DHLECS_PAGE),
        )
    }

    @Test fun raw_without_status_text_is_empty_and_foreign_kind_is_null() {
        assertIs<PageOutcome.Empty>(resolveTrackerPage(DomRaw(kind = "tracker"), DHLECS_PAGE))
        assertNull(resolveTrackerPage(DomRaw(kind = "cards"), DHLECS_PAGE))
    }

    @Test fun undelivered_headline_is_an_exception_via_the_shared_lane() {
        assertEquals(TrackingStatus.EXCEPTION, DHLECS_VOCABULARY.classify("Undelivered - Processes for Local Disposal"))
    }
}

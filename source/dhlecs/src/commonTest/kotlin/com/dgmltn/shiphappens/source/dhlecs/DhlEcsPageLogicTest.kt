package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.source.webview.DomRaw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DhlEcsPageLogicTest {

    private fun statusOf(text: String) =
        parseDhlEcsRaw(DomRaw(kind = "tracker", statusText = text))?.tracking?.status

    @Test fun status_headlines_classify_in_kotlin() {
        assertEquals("DELIVERED", statusOf("Delivered"))
        assertEquals("IN_TRANSIT", statusOf("En Route"))
        assertEquals("LABEL_CREATED", statusOf("Electronic Notification"))
        assertEquals("OUT_FOR_DELIVERY", statusOf("Out for Delivery"))
        assertEquals("EXCEPTION", statusOf("Notice Left"))
        assertEquals("UNKNOWN", statusOf("Some brand-new wording"))
    }

    @Test fun delay_wording_keeps_the_stage_and_reports_the_delay() {
        fun trackingOf(text: String) = parseDhlEcsRaw(DomRaw(kind = "tracker", statusText = text))?.tracking
        assertEquals("IN_TRANSIT", trackingOf("En Route - Delayed")?.status)
        assertEquals("En Route - Delayed", trackingOf("En Route - Delayed")?.delayNote)
        // Stage-less delay wording asserts no stage; UNKNOWN leaves it to the stored status.
        assertEquals("UNKNOWN", trackingOf("Possible Delivery Delay")?.status)
        assertEquals("Possible Delivery Delay", trackingOf("Possible Delivery Delay")?.delayNote)
        assertNull(trackingOf("En Route")?.delayNote)
    }

    @Test fun not_found_wording_routes_to_not_found() {
        // "Unfortunately, no results found." — webtrack's en-US locale copy (recon 2026-09-03).
        assertEquals(
            "notFound",
            parseDhlEcsRaw(DomRaw(kind = "tracker", pageText = "Unfortunately, no results found. Please confirm"))?.page,
        )
    }

    @Test fun raw_without_status_text_routes_to_unparsed() {
        assertNull(parseDhlEcsRaw(DomRaw(kind = "tracker")))
        assertNull(parseDhlEcsRaw(DomRaw(kind = "cards")))
    }
}

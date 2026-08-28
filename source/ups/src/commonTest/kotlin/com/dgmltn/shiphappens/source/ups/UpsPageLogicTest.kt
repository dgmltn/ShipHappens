package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpsPageLogicTest {

    @Test fun classifies_the_wordings_the_old_js_blob_knew() {
        // The DOM blob's vocabulary, moved verbatim into Kotlin: these were the exact branches
        // in UPS_EXTRACTION_JS before the raw-reader conversion.
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, classifyUpsStatus("Out for Delivery Today"))
        assertEquals(TrackingStatus.DELIVERED, classifyUpsStatus("Delivered"))
        assertEquals(TrackingStatus.EXCEPTION, classifyUpsStatus("Delivery Exception"))
        assertEquals(TrackingStatus.EXCEPTION, classifyUpsStatus("Action Needed"))
        assertEquals(TrackingStatus.LABEL_CREATED, classifyUpsStatus("Label Created"))
        assertEquals(TrackingStatus.LABEL_CREATED, classifyUpsStatus("Order Processed: Ready for UPS"))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyUpsStatus("On the Way"))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyUpsStatus("In Transit"))
    }

    @Test fun classifies_the_api_scan_wordings() {
        assertEquals(TrackingStatus.SHIPPED, classifyUpsStatus("Origin Scan"))
        assertEquals(TrackingStatus.SHIPPED, classifyUpsStatus("Pickup Scan"))
        assertEquals(TrackingStatus.LABEL_CREATED, classifyUpsStatus("Shipper created a label, UPS has not received the package yet."))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyUpsStatus("Departed from Facility"))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyUpsStatus("Arrived at Facility"))
    }

    @Test fun unknown_wording_is_null_not_a_guess() {
        assertNull(classifyUpsStatus("Some New Wording"))
        assertNull(classifyUpsStatus(""))
        assertNull(classifyUpsStatus(null))
    }

    @Test fun tracker_page_classifies_the_status_headline() {
        val result = parseUpsRaw(DomRaw(kind = "tracker", statusText = "On the Way"))
        assertEquals("ok", result?.page)
        assertEquals("IN_TRANSIT", result?.tracking?.status)
    }

    @Test fun unrecognized_headline_is_ok_with_unknown_status() {
        // The pre-conversion JS reported {page:'ok', status:'UNKNOWN'} for a present-but-novel
        // headline; the DOM layer is UPS's coarse fallback and UNKNOWN routes further fallbacks.
        assertEquals("UNKNOWN", parseUpsRaw(DomRaw(kind = "tracker", statusText = "Novel wording"))?.tracking?.status)
    }

    @Test fun not_found_wording_routes_not_found_from_page_text() {
        // The wordings the JS blob used to decide on (moved to Kotlin 2026-08-20).
        assertEquals("notFound", parseUpsRaw(DomRaw(kind = "tracker", pageText = "The tracking number you entered is invalid"))?.page)
        assertEquals("notFound", parseUpsRaw(DomRaw(kind = "tracker", pageText = "Sorry, this tracking number was not found in our records"))?.page)
        assertEquals("ok", parseUpsRaw(DomRaw(kind = "tracker", statusText = "On the Way", pageText = "UPS tracking detail"))?.page)
    }

    @Test fun a_delayed_headline_reports_in_transit_and_carries_the_delay() {
        // The DOM fallback has no simplifiedText to quote, so the headline is the note.
        val result = parseUpsRaw(DomRaw(kind = "tracker", statusText = "On the Way: Delayed"))
        assertEquals("IN_TRANSIT", result?.tracking?.status)
        assertEquals("On the Way: Delayed", result?.tracking?.delayNote)
    }

    @Test fun an_undelayed_headline_has_no_delay_note() {
        assertNull(parseUpsRaw(DomRaw(kind = "tracker", statusText = "On the Way"))?.tracking?.delayNote)
    }

    @Test fun blank_page_is_empty_and_foreign_kind_is_null() {
        assertEquals("empty", parseUpsRaw(DomRaw(kind = "tracker"))?.page)
        assertNull(parseUpsRaw(DomRaw(kind = "cards", statusText = "On the Way")))
    }
}

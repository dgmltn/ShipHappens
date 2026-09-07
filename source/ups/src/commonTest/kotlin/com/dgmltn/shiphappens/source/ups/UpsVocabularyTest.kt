package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.PageOutcome
import com.dgmltn.shiphappens.source.webview.resolveTrackerPage
import com.dgmltn.shiphappens.source.webview.snapshotOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class UpsVocabularyTest {

    private fun page(raw: DomRaw) = resolveTrackerPage(raw, UPS_PAGE)

    @Test fun classifies_the_wordings_the_old_js_blob_knew() {
        // The DOM blob's vocabulary, moved verbatim into Kotlin: these were the exact branches
        // in UPS_EXTRACTION_JS before the raw-reader conversion.
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, UPS_VOCABULARY.classify("Out for Delivery Today"))
        assertEquals(TrackingStatus.DELIVERED, UPS_VOCABULARY.classify("Delivered"))
        assertEquals(TrackingStatus.EXCEPTION, UPS_VOCABULARY.classify("Delivery Exception"))
        assertEquals(TrackingStatus.EXCEPTION, UPS_VOCABULARY.classify("Action Needed"))
        assertEquals(TrackingStatus.LABEL_CREATED, UPS_VOCABULARY.classify("Label Created"))
        assertEquals(TrackingStatus.LABEL_CREATED, UPS_VOCABULARY.classify("Order Processed: Ready for UPS"))
        assertEquals(TrackingStatus.IN_TRANSIT, UPS_VOCABULARY.classify("On the Way"))
        assertEquals(TrackingStatus.IN_TRANSIT, UPS_VOCABULARY.classify("In Transit"))
    }

    @Test fun classifies_the_api_scan_wordings() {
        assertEquals(TrackingStatus.SHIPPED, UPS_VOCABULARY.classify("Origin Scan"))
        assertEquals(TrackingStatus.SHIPPED, UPS_VOCABULARY.classify("Pickup Scan"))
        assertEquals(TrackingStatus.LABEL_CREATED, UPS_VOCABULARY.classify("Shipper created a label, UPS has not received the package yet."))
        assertEquals(TrackingStatus.IN_TRANSIT, UPS_VOCABULARY.classify("Departed from Facility"))
        assertEquals(TrackingStatus.IN_TRANSIT, UPS_VOCABULARY.classify("Arrived at Facility"))
    }

    @Test fun unknown_wording_is_null_not_a_guess() {
        assertNull(UPS_VOCABULARY.classify("Some New Wording"))
        assertNull(UPS_VOCABULARY.classify(""))
        assertNull(UPS_VOCABULARY.classify(null))
    }

    @Test fun tracker_page_classifies_the_status_headline() {
        assertEquals(TrackingStatus.IN_TRANSIT, page(DomRaw(kind = "tracker", statusText = "On the Way")).snapshotOrNull()?.status)
    }

    @Test fun unrecognized_headline_is_a_result_with_unknown_status() {
        // The DOM layer is UPS's coarse fallback; UNKNOWN routes further fallbacks downstream.
        assertEquals(TrackingStatus.UNKNOWN, page(DomRaw(kind = "tracker", statusText = "Novel wording")).snapshotOrNull()?.status)
    }

    @Test fun not_found_wording_routes_not_found_from_page_text() {
        assertIs<PageOutcome.NotFound>(page(DomRaw(kind = "tracker", pageText = "The tracking number you entered is invalid")))
        assertIs<PageOutcome.NotFound>(page(DomRaw(kind = "tracker", pageText = "Sorry, this tracking number was not found in our records")))
        assertIs<PageOutcome.Tracking>(page(DomRaw(kind = "tracker", statusText = "On the Way", pageText = "UPS tracking detail")))
    }

    @Test fun a_delayed_headline_reports_in_transit_and_carries_the_delay() {
        val s = page(DomRaw(kind = "tracker", statusText = "On the Way: Delayed")).snapshotOrNull()!!
        assertEquals(TrackingStatus.IN_TRANSIT, s.status)
        assertEquals("On the Way: Delayed", s.delayNote)
    }

    @Test fun an_undelayed_headline_has_no_delay_note() {
        assertNull(page(DomRaw(kind = "tracker", statusText = "On the Way")).snapshotOrNull()?.delayNote)
    }

    @Test fun blank_page_is_empty_and_foreign_kind_is_null() {
        assertIs<PageOutcome.Empty>(page(DomRaw(kind = "tracker")))
        assertNull(page(DomRaw(kind = "cards", statusText = "On the Way")))
    }
}

package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.source.webview.DomCard
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.PageOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmazonWebSpecTest {

    @Test fun tracking_url_targets_order_details_with_hyphenated_id() {
        assertEquals(
            "https://www.amazon.com/gp/your-account/order-details?orderID=113-1234567-1234567",
            AmazonWebSpec.trackingUrl("113-1234567-1234567"),
        )
    }

    @Test fun tracking_url_rehyphenates_normalized_ids() {
        // normalizeTracking strips hyphens app-wide; the URL must restore the 3-7-7 shape.
        assertEquals(
            "https://www.amazon.com/gp/your-account/order-details?orderID=113-1234567-1234567",
            AmazonWebSpec.trackingUrl("11312345671234567"),
        )
    }

    @Test fun api_capture_is_disabled_in_v1() {
        assertTrue(AmazonWebSpec.apiUrlPatterns.isEmpty())
        assertNull(AmazonWebSpec.parseApi("https://www.amazon.com/x", """{"anything":true}"""))
    }

    @Test fun extraction_js_is_a_function_expression_covering_both_pages() {
        assertTrue(AmazonWebSpec.extractionJs.trimStart().startsWith("function"))
        assertTrue(AmazonWebSpec.extractionJs.contains("progress-tracker"))   // tracker-page branch
        assertTrue(AmazonWebSpec.extractionJs.contains("kind: 'cards'"))      // order-details branch
    }

    @Test fun extraction_js_decides_nothing_it_only_reads() {
        // The blob has no test harness (no JS engine in commonTest), so status vocabulary and
        // shipment selection must stay in AmazonPageLogic where AmazonPageLogicTest can reach them.
        // Guarding the absence keeps a "quick fix" from drifting back into the untestable layer.
        assertFalse(AmazonWebSpec.extractionJs.contains("DELIVERED"))
        assertFalse(AmazonWebSpec.extractionJs.contains("EXCEPTION"))
        assertFalse(AmazonWebSpec.extractionJs.contains("classify"))
    }

    @Test fun raw_extractions_are_routed_through_kotlin() {
        val cards = DomRaw(
            kind = "cards",
            cards = listOf(DomCard(head = "Arriving today", href = "https://www.amazon.com/progress-tracker/p")),
        )
        assertIs<PageOutcome.Goto>(AmazonWebSpec.parseRaw(cards))
    }

    @Test fun extraction_js_targets_the_data_component_order_details_layout() {
        // Regression guard for the 2026-07-19 QA failure: the class-based card/status selectors
        // matched the wrong elements on the live page, classifying every shipment UNKNOWN and
        // returning page:'empty'. These are the attributes the live DOM actually exposes.
        assertTrue(AmazonWebSpec.extractionJs.contains("""[data-component="shipmentCard"]"""))
        assertTrue(AmazonWebSpec.extractionJs.contains("""[data-component="shipmentStatus"]"""))
    }

    @Test fun extraction_js_reports_why_it_gave_up() {
        // page:'empty' alone can't be debugged off-device — each bail-out names its branch.
        assertTrue(AmazonWebSpec.extractionJs.contains("noShipmentCards"))
        assertTrue(AmazonWebSpec.extractionJs.contains("trackerNoStatusNoEvents"))
    }

    @Test fun extraction_js_treats_overnight_as_a_relative_day_word() {
        // "Arriving overnight 7 AM – 11 AM" means delivery in the coming night, i.e. tomorrow
        // morning. Without a relative-word branch, parseDay's calendar parse NaN'd and the card
        // lost its ETA entirely (device log 2026-07-26).
        assertTrue(AmazonWebSpec.extractionJs.contains("overnight"))
    }

    @Test fun extraction_js_emits_raw_eta_window_text() {
        // The blob only grabs the phrase; parsing lives in EtaWindowParser, where tests can reach it.
        assertTrue(AmazonWebSpec.extractionJs.contains("etaWindowText"))
        assertTrue(AmazonWebSpec.extractionJs.contains("windowText("))
    }
}

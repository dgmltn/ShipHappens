package com.shiphappens.source.amazon

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test fun spec_identity_and_origins() {
        assertEquals("amazon", AmazonWebSpec.sourceId)
        assertEquals("amazon.com", AmazonWebSpec.cookieDomain)
        assertEquals(listOf("https://*.amazon.com", "https://amazon.com"), AmazonWebSpec.allowedOriginRules())
        assertTrue(AmazonWebSpec.challengeMarkers.isNotEmpty())
    }

    @Test fun api_capture_is_disabled_in_v1() {
        assertTrue(AmazonWebSpec.apiUrlPatterns.isEmpty())
        assertNull(AmazonWebSpec.parseApi("https://www.amazon.com/x", """{"anything":true}"""))
    }

    @Test fun extraction_js_is_a_function_expression_covering_both_pages() {
        assertTrue(AmazonWebSpec.extractionJs.trimStart().startsWith("function"))
        assertTrue(AmazonWebSpec.extractionJs.contains("progress-tracker"))   // tracker-page branch
        assertTrue(AmazonWebSpec.extractionJs.contains("'goto'"))             // order-details hop
    }
}

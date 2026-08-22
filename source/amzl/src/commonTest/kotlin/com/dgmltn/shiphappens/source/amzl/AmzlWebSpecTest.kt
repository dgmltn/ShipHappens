package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.source.webview.DomRaw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmzlWebSpecTest {

    @Test fun tracking_url_normalizes_and_uses_amazons_link_shape() {
        assertEquals(
            "https://track.amazon.com/tracking/TBA333593378975?trackingId=TBA333593378975",
            AmzlWebSpec.trackingUrl("tba 3335-9337 8975"),
        )
    }

    @Test fun origin_rules_are_confined_to_the_tracking_subdomain() {
        // cookieDomain must be track.amazon.com: bridge injection and goto hops stay off
        // www.amazon.com, and clearForDomain can never touch the Amazon orders session.
        assertEquals("track.amazon.com", AmzlWebSpec.cookieDomain)
        assertEquals(
            listOf("https://*.track.amazon.com", "https://track.amazon.com"),
            AmzlWebSpec.allowedOriginRules(),
        )
    }

    @Test fun api_pattern_matches_the_tracker_endpoint() {
        val pattern = Regex(AmzlWebSpec.apiUrlPatterns.single())
        assertTrue(pattern.matches("https://track.amazon.com/api/tracker/TBA333593378975"))
        assertTrue(!pattern.matches("https://www.amazon.com/gp/your-account/order-details"))
    }

    @Test fun parse_api_is_wired_to_the_parser() {
        val body = """{"progressTracker": "{\"summary\": {\"status\": \"Delivered\", \"metadata\": {}}}"}"""
        assertEquals("DELIVERED", AmzlWebSpec.parseApi(null, body)?.status)
    }

    @Test fun raw_status_headlines_classify_in_kotlin() {
        fun statusOf(text: String) =
            parseAmzlRaw(DomRaw(kind = "tracker", statusText = text))?.tracking?.status
        assertEquals("OUT_FOR_DELIVERY", statusOf("Out for delivery"))
        assertEquals("DELIVERED", statusOf("Delivered today"))
        assertEquals("IN_TRANSIT", statusOf("Arriving Wednesday"))
        assertEquals("IN_TRANSIT", statusOf("Package is in transit"))
        assertEquals("LABEL_CREATED", statusOf("We have your package details"))
        assertEquals("EXCEPTION", statusOf("Delivery attempted"))
        assertEquals("UNKNOWN", statusOf("Some brand-new wording"))
    }

    @Test fun raw_without_status_text_routes_to_unparsed() {
        assertNull(parseAmzlRaw(DomRaw(kind = "tracker")))
        // The wordings the JS blob used to decide on (moved to Kotlin 2026-08-20).
        assertEquals("notFound", parseAmzlRaw(DomRaw(kind = "tracker", pageText = "We couldn't find this tracking number"))?.page)
        assertEquals("notFound", parseAmzlRaw(DomRaw(kind = "tracker", pageText = "This tracking information is no longer available"))?.page)
        assertNull(parseAmzlRaw(DomRaw(kind = "cards")))
    }
}

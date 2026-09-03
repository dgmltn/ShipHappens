package com.dgmltn.shiphappens.source.dhlecs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DhlEcsWebSpecTest {

    @Test fun tracking_url_normalizes_into_the_orders_page_shape() {
        assertEquals(
            "https://webtrack.dhlecs.com/orders?trackingNumber=420300019261234500000000000042",
            DhlEcsWebSpec.trackingUrl("420 30001 9261-2345 0000 0000 0000 42"),
        )
    }

    @Test fun origin_rules_are_confined_to_the_webtrack_subdomain() {
        assertEquals("webtrack.dhlecs.com", DhlEcsWebSpec.cookieDomain)
        assertEquals(
            listOf("https://*.webtrack.dhlecs.com", "https://webtrack.dhlecs.com"),
            DhlEcsWebSpec.allowedOriginRules(),
        )
    }

    @Test fun api_pattern_matches_the_tracking_endpoint_on_its_own_host() {
        // The SPA on webtrack.dhlecs.com calls api.dhlecs.com cross-host; the fetch hook sees it.
        val pattern = Regex(DhlEcsWebSpec.apiUrlPatterns.single())
        assertTrue(pattern.matches("https://api.dhlecs.com/webtrack/v4/tracking"))
        assertFalse(pattern.matches("https://webtrack.dhlecs.com/orders?trackingNumber=420300019261234500000000000042"))
        assertFalse(pattern.matches("https://api.dhlecs.com/webtrack/v4/utility/config"))
    }

    @Test fun parse_api_is_wired_to_the_parser() {
        val body = """{"total":1,"limit":10,"offset":0,"packages":[{"status":"Delivered","events":[]}]}"""
        assertEquals("DELIVERED", DhlEcsWebSpec.parseApi(null, body)?.status)
    }

    @Test fun login_is_never_reported() {
        // Anonymous tracker, AMZL-style: login state is a constant false.
        assertEquals("(function() { return false; })()", DhlEcsWebSpec.isLoggedInJs)
    }
}

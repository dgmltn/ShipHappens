package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.source.webview.DomRaw
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FedexWebSpecTest {

    @Test fun tracking_url_uses_fedextrack_path() {
        assertEquals(
            "https://www.fedex.com/fedextrack/?trknbr=123456789012",
            FedexWebSpec.trackingUrl("123456789012"),
        )
    }

    @Test fun dom_only_no_api_capture_and_a_long_settle() {
        // DOM-only is load-bearing, not an omission: WebSessions injects NO fetch/XHR capture
        // hooks for a spec without apiUrlPatterns, and the hooks are what fedex.com's bot
        // defense keys on (QA 2026-08-19 — hooks present sent every lookup to "system-error").
        assertTrue(FedexWebSpec.apiUrlPatterns.isEmpty())
        assertEquals(null, FedexWebSpec.parseApi(null, """{"anything":true}"""))
        // The fedextrack SPA client-routes and renders ~10s after onPageFinished; the default
        // 3s settle reads the app shell and reports an empty page.
        assertEquals(12.seconds, FedexWebSpec.settle)
    }

    @Test fun spec_identity_and_origins() {
        assertEquals("fedex", FedexWebSpec.sourceId)
        assertEquals("fedex.com", FedexWebSpec.cookieDomain)
        assertEquals(listOf("https://*.fedex.com", "https://fedex.com"), FedexWebSpec.allowedOriginRules())
        assertTrue(FedexWebSpec.challengeMarkers.isNotEmpty())
    }

    @Test fun parse_raw_delegates_to_fedex_page_logic() {
        val result = FedexWebSpec.parseRaw(DomRaw(kind = "tracker", statusText = "On the way"))
        assertEquals("ok", result?.page)
        assertEquals("IN_TRANSIT", result?.tracking?.status)
    }
}

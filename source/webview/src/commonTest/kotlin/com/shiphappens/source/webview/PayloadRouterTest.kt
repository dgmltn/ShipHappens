package com.shiphappens.source.webview

import com.shiphappens.domain.WellKnownCarriers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

internal fun testSpec(parseApi: (String?, String) -> ScrapedTracking? = { _, _ -> null }) = WebProviderSpec(
    sourceId = "test",
    carrier = WellKnownCarriers.UPS,
    cookieDomain = "example.com",
    trackingUrl = { "https://www.example.com/track?n=$it" },
    loginUrl = "https://www.example.com/login",
    isLoggedInJs = "(function(){return false})()",
    apiUrlPatterns = listOf(".*example\\.com/api/track.*"),
    challengeMarkers = listOf("verify you are a human"),
    extractionJs = "function(){return {page:'empty'}}",
    parseApi = parseApi,
)

class PayloadRouterTest {

    private fun domPayload(body: String) =
        """{"kind":"dom","body":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(body))}}"""

    @Test fun api_payload_routes_through_parseApi() {
        val router = PayloadRouter(testSpec(parseApi = { url, body ->
            if (url?.contains("/api/track") == true && body == "{...}") ScrapedTracking(status = "IN_TRANSIT") else null
        }))
        val result = router.route("""{"kind":"api","url":"https://www.example.com/api/track?x=1","body":"{...}"}""")
        assertEquals("IN_TRANSIT", assertIs<RouteResult.Tracking>(result).tracking.status)
    }

    @Test fun api_payload_parse_failure_is_unparsed() {
        val result = PayloadRouter(testSpec()).route("""{"kind":"api","url":"u","body":"junk"}""")
        assertIs<RouteResult.Unparsed>(result)
    }

    @Test fun dom_ok_payload_yields_tracking() {
        val body = """{"page":"ok","tracking":{"status":"DELIVERED"}}"""
        val result = PayloadRouter(testSpec()).route(
            """{"kind":"dom","body":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(body))}}""",
        )
        assertEquals("DELIVERED", assertIs<RouteResult.Tracking>(result).tracking.status)
    }

    @Test fun dom_page_states_route_to_signals() {
        val router = PayloadRouter(testSpec())
        fun dom(page: String) = """{"kind":"dom","body":"{\"page\":\"$page\"}"}"""
        assertIs<RouteResult.NotFound>(router.route(dom("notFound")))
        assertIs<RouteResult.LoginWall>(router.route(dom("loginWall")))
        assertIs<RouteResult.Challenge>(router.route(dom("challenge")))
        assertIs<RouteResult.Unparsed>(router.route(dom("empty")))
    }

    @Test fun garbage_payload_is_unparsed() {
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("not json at all"))
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("""{"kind":"mystery","body":""}"""))
    }

    @Test fun goto_payload_routes_to_goto_with_embedded_tracking() {
        val body = """{"page":"goto","url":"https://www.example.com/track/2","tracking":{"status":"IN_TRANSIT"}}"""
        val goto = assertIs<RouteResult.Goto>(PayloadRouter(testSpec()).route(domPayload(body)))
        assertEquals("https://www.example.com/track/2", goto.url)
        assertEquals("IN_TRANSIT", goto.tracking?.status)
    }

    @Test fun goto_without_tracking_still_routes_to_goto() {
        val body = """{"page":"goto","url":"https://example.com/track/2"}"""
        val goto = assertIs<RouteResult.Goto>(PayloadRouter(testSpec()).route(domPayload(body)))
        assertNull(goto.tracking)
    }

    @Test fun goto_with_disallowed_url_degrades_to_embedded_tracking() {
        val badUrls = listOf(
            "http://www.example.com/track/2",        // not https
            "https://evil.com/track/2",              // foreign host
            "https://evilexample.com/track/2",       // suffix trick — not a subdomain
            "https://example.com@evil.com/track/2",  // userinfo smuggling
        )
        for (bad in badUrls) {
            val body = """{"page":"goto","url":"$bad","tracking":{"status":"SHIPPED"}}"""
            val result = PayloadRouter(testSpec()).route(domPayload(body))
            assertEquals("SHIPPED", assertIs<RouteResult.Tracking>(result).tracking.status, "url: $bad")
        }
    }

    @Test fun goto_with_disallowed_or_missing_url_and_no_tracking_is_unparsed() {
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route(domPayload("""{"page":"goto","url":"https://evil.com/x"}""")))
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route(domPayload("""{"page":"goto"}""")))
    }

    @Test fun hop_url_allowlist_semantics() {
        assertTrue(isAllowedHopUrl("https://example.com/a", "example.com"))
        assertTrue(isAllowedHopUrl("https://www.example.com:443/a?b#c", "example.com"))
        assertFalse(isAllowedHopUrl("https://evilexample.com/a", "example.com"))
        assertFalse(isAllowedHopUrl("http://example.com/a", "example.com"))
        assertFalse(isAllowedHopUrl("https://example.com@evil.com/a", "example.com"))
    }
}

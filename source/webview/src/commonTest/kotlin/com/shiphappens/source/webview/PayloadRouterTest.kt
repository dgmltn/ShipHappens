package com.shiphappens.source.webview

import com.shiphappens.domain.WellKnownCarriers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
}

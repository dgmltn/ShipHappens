package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.domain.WellKnownCarriers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun testSpec(
    parseApi: (String?, String) -> TrackingSnapshot? = { _, _ -> null },
    parseRaw: (DomRaw) -> PageOutcome? = { null },
) = WebProviderSpec(
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
    parseRaw = parseRaw,
)

class PayloadRouterTest {
    private val json = Json
    private fun domPayload(body: String) = """{"kind":"dom","body":${json.encodeToString(JsonPrimitive(body))}}"""
    private fun rawPayload() = domPayload("""{"page":"raw","raw":{"kind":"tracker","statusText":"x"}}""")
    private fun snapshot(status: TrackingStatus) = TrackingSnapshot(status = status)

    @Test fun api_payload_routes_through_parseApi() {
        val router = PayloadRouter(testSpec(parseApi = { url, body ->
            if (url?.contains("/api/track") == true && body == "{...}") snapshot(TrackingStatus.IN_TRANSIT) else null
        }))
        val result = router.route("""{"kind":"api","url":"https://www.example.com/api/track?x=1","body":"{...}"}""")
        assertEquals(TrackingStatus.IN_TRANSIT, assertIs<RouteResult.Tracking>(result).snapshot.status)
    }

    @Test fun api_payload_parse_failure_is_unparsed() {
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("""{"kind":"api","url":"u","body":"junk"}"""))
    }

    @Test fun raw_payload_routes_through_parseRaw() {
        val router = PayloadRouter(testSpec(parseRaw = { raw ->
            if (raw.kind == "tracker" && raw.statusText == "x") PageOutcome.Tracking(snapshot(TrackingStatus.DELIVERED)) else null
        }))
        assertEquals(TrackingStatus.DELIVERED, assertIs<RouteResult.Tracking>(router.route(rawPayload())).snapshot.status)
    }

    @Test fun raw_outcomes_map_to_route_signals() {
        fun routeOf(outcome: PageOutcome) = PayloadRouter(testSpec(parseRaw = { outcome })).route(rawPayload())
        assertIs<RouteResult.NotFound>(routeOf(PageOutcome.NotFound))
        assertIs<RouteResult.LoginWall>(routeOf(PageOutcome.LoginWall))
        assertIs<RouteResult.Challenge>(routeOf(PageOutcome.Challenge))
        assertIs<RouteResult.Unparsed>(routeOf(PageOutcome.Empty))
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec(parseRaw = { null })).route(rawPayload()))
    }

    @Test fun dom_page_states_route_to_signals() {
        val router = PayloadRouter(testSpec())
        fun dom(page: String) = """{"kind":"dom","body":"{\"page\":\"$page\"}"}"""
        assertIs<RouteResult.NotFound>(router.route(dom("notFound")))
        assertIs<RouteResult.LoginWall>(router.route(dom("loginWall")))
        assertIs<RouteResult.Challenge>(router.route(dom("challenge")))
        assertIs<RouteResult.Unparsed>(router.route(dom("empty")))
        assertIs<RouteResult.Unparsed>(router.route(dom("ok")))   // no JS emits 'ok' any more; it carries nothing
    }

    @Test fun garbage_payload_is_unparsed() {
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("not json at all"))
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec()).route("""{"kind":"mystery","body":""}"""))
    }

    @Test fun kotlin_goto_routes_with_its_coarse_snapshot() {
        val outcome = PageOutcome.Goto("https://www.example.com/track/2", snapshot(TrackingStatus.IN_TRANSIT))
        val goto = assertIs<RouteResult.Goto>(PayloadRouter(testSpec(parseRaw = { outcome })).route(rawPayload()))
        assertEquals("https://www.example.com/track/2", goto.url)
        assertEquals(TrackingStatus.IN_TRANSIT, goto.coarse?.status)
    }

    @Test fun js_goto_routes_without_a_snapshot() {
        val goto = assertIs<RouteResult.Goto>(PayloadRouter(testSpec()).route(domPayload("""{"page":"goto","url":"https://example.com/track/2"}""")))
        assertNull(goto.coarse)
    }

    @Test fun goto_with_disallowed_url_degrades_to_the_coarse_snapshot() {
        val badUrls = listOf(
            "http://www.example.com/track/2",        // not https
            "https://evil.com/track/2",              // foreign host
            "https://evilexample.com/track/2",       // suffix trick — not a subdomain
            "https://example.com@evil.com/track/2",  // userinfo smuggling
        )
        for (bad in badUrls) {
            val outcome = PageOutcome.Goto(bad, snapshot(TrackingStatus.SHIPPED))
            val result = PayloadRouter(testSpec(parseRaw = { outcome })).route(rawPayload())
            assertEquals(TrackingStatus.SHIPPED, assertIs<RouteResult.Tracking>(result).snapshot.status, "url: $bad")
        }
    }

    @Test fun goto_with_disallowed_or_missing_url_and_no_snapshot_is_unparsed() {
        assertIs<RouteResult.Unparsed>(PayloadRouter(testSpec(parseRaw = { PageOutcome.Goto("https://evil.com/x", null) })).route(rawPayload()))
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

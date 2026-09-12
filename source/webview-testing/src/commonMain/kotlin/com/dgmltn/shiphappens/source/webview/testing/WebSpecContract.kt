package com.dgmltn.shiphappens.source.webview.testing

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DEFAULT_CHALLENGE_MARKERS
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.PageOutcome
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.isAllowedHopUrl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The carrier-supplied examples a spec contract is checked against. */
class SpecSamples(
    val trackingNumber: String,
    /** An in-page API URL the capture hooks must match; null for DOM-only carriers. */
    val capturedApiUrl: String? = null,
    /** Same-site URLs the capture hooks must NOT match; empty when the carrier's patterns are deliberately broad. */
    val uncapturedUrls: List<String> = emptyList(),
    /** Raw extractions (of whichever page kind the carrier decides not-found on) that must route NotFound. */
    val notFound: List<DomRaw> = emptyList(),
    /** A captured API body and the status it must yield, proving parseApi reaches the carrier's parser; null for DOM-only carriers. */
    val apiBody: Pair<String, TrackingStatus>? = null,
)

/** What every WebProviderSpec must satisfy, regardless of carrier. A new assertion here runs against all of them. */
object WebSpecContract {
    fun verify(spec: WebProviderSpec, samples: SpecSamples) {
        val d = spec.cookieDomain
        assertEquals(spec.carrier.code, spec.sourceId, "sourceId is the carrier code")
        assertTrue(isAllowedHopUrl(spec.trackingUrl(samples.trackingNumber), d), "tracking URL is https on the cookie domain")
        assertEquals(listOf("https://*.$d", "https://$d"), spec.allowedOriginRules(), "origin rules derive from the cookie domain")
        assertTrue(spec.challengeMarkers.containsAll(DEFAULT_CHALLENGE_MARKERS), "shared challenge markers present")
        assertTrue(spec.extractionJs.trimStart().startsWith("function"), "extractionJs is a function expression")
        for (forbidden in listOf("new Date(", "Date.parse", "getFullYear")) {
            assertFalse(spec.extractionJs.contains(forbidden), "extractionJs must not do date arithmetic ($forbidden); send the text and let Kotlin decide")
        }
        assertTrue(spec.isLoggedInJs.trimStart().startsWith("(function"), "isLoggedInJs is a self-invoking function expression")
        spec.login?.let { assertTrue(it.url.startsWith("https://"), "login URL is https") }

        val patterns = spec.apiUrlPatterns.map { Regex(it) }   // throws on a malformed pattern
        samples.capturedApiUrl?.let { url -> assertTrue(patterns.any { it.containsMatchIn(url) }, "some api pattern captures $url") }
        samples.uncapturedUrls.forEach { url -> assertFalse(patterns.any { it.containsMatchIn(url) }, "no api pattern captures $url") }
        if (samples.capturedApiUrl == null) assertTrue(spec.apiUrlPatterns.isEmpty(), "a DOM-only carrier declares no api patterns")

        assertNull(spec.parseApi(null, "not json"), "parseApi rejects non-JSON")
        assertNull(spec.parseApi(null, "{}"), "parseApi rejects foreign JSON")
        samples.apiBody?.let { (body, expected) ->
            assertEquals(expected, spec.parseApi(samples.capturedApiUrl, body)?.status, "parseApi is wired to the carrier's parser")
        }
        assertNull(spec.parseRaw(DomRaw(kind = "contract-foreign-kind")), "parseRaw refuses an unknown page kind")
        samples.notFound.forEach { raw -> assertIs<PageOutcome.NotFound>(spec.parseRaw(raw), "not-found wording: ${raw.pageText}") }
    }
}

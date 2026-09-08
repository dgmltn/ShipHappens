package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.api.FailureReason
import com.dgmltn.shiphappens.source.api.SourceResult
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeScraper(private val result: ScrapeResult) : WebScraper {
    override val isAvailable = true
    override suspend fun scrape(spec: WebProviderSpec, trackingNumber: String) = result
}

private fun dom(page: String) = """{"kind":"dom","body":"{\"page\":\"$page\"}"}"""

/** Wraps a DomExtraction JSON string as a `kind:"dom"` bridge payload (JSON-escaped body). */
private fun domBody(body: String) =
    """{"kind":"dom","body":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(body))}}"""

private fun raw(statusText: String) =
    domBody("""{"page":"raw","raw":{"kind":"tracker","statusText":"$statusText"}}""")

/** A spec whose Kotlin answers by headline: "goto" hops with a coarse IN_TRANSIT+ETA snapshot, anything else is a rich snapshot of that status name. */
private fun outcomeSpec() = testSpec(parseRaw = { r ->
    when (r.statusText) {
        "goto" -> PageOutcome.Goto("https://www.example.com/t/2", TrackingSnapshot(TrackingStatus.IN_TRANSIT, etaDate = LocalDate(2026, 7, 21)))
        "gotoBare" -> PageOutcome.Goto("https://www.example.com/t/2", null)
        "empty" -> PageOutcome.Empty
        else -> TrackingStatus.entries.firstOrNull { it.name == r.statusText }?.let { PageOutcome.Tracking(TrackingSnapshot(it, etaDate = if (it == TrackingStatus.OUT_FOR_DELIVERY) LocalDate(2026, 7, 20) else null)) }
    }
})

class WebSourceTest {

    @Test fun detects_by_the_carriers_number_pattern() {
        val spec = testSpec()   // same Carrier instance for the source and the expectation: Regex compares by identity
        val src = WebSource(spec, NoWebScraper)
        assertEquals(spec.carrier, src.detectCarrier("T 123 456"))
        assertNull(src.detectCarrier("1Z999AA10123456784"))
    }

    @Test fun descriptor_derives_from_spec_and_scraper() {
        val available = WebSource(testSpec(), FakeScraper(ScrapeResult.Unavailable))
        assertTrue(available.descriptor.implemented)
        assertEquals("test", available.descriptor.id)
        assertFalse(WebSource(testSpec(), NoWebScraper).descriptor.implemented)
    }

    @Test fun tracking_payload_becomes_success() = runTest {
        val spec = testSpec(parseApi = { _, _ -> TrackingSnapshot(TrackingStatus.IN_TRANSIT, latestLocation = "Louisville, KY") })
        val src = WebSource(spec, FakeScraper(ScrapeResult.Payloads(listOf("""{"kind":"api","url":"u","body":"b"}"""))))
        val result = assertIs<SourceResult.Success<TrackingSnapshot>>(src.track("1Z1", null))
        assertEquals(TrackingStatus.IN_TRANSIT, result.value.status)
        assertEquals("Louisville, KY", result.value.latestLocation)
    }

    @Test fun failure_taxonomy_mapping() = runTest {
        suspend fun reasonFor(r: ScrapeResult): FailureReason =
            assertIs<SourceResult.Failure>(WebSource(testSpec(), FakeScraper(r)).track("1Z1", null)).reason
        assertEquals(FailureReason.AUTH, reasonFor(ScrapeResult.Payloads(listOf(dom("loginWall")))))
        assertEquals(FailureReason.RATE_LIMITED, reasonFor(ScrapeResult.Payloads(listOf(dom("challenge")))))
        assertEquals(FailureReason.NOT_FOUND, reasonFor(ScrapeResult.Payloads(listOf(dom("notFound")))))
        assertEquals(FailureReason.UNKNOWN, reasonFor(ScrapeResult.Payloads(listOf(dom("empty")))))
        assertEquals(FailureReason.NETWORK, reasonFor(ScrapeResult.LoadError("dns")))
        assertEquals(FailureReason.NETWORK, reasonFor(ScrapeResult.Timeout))
        assertEquals(FailureReason.UNKNOWN, reasonFor(ScrapeResult.Unavailable))
    }

    @Test fun unavailable_scraper_fails_without_scraping() = runTest {
        val src = WebSource(testSpec(), NoWebScraper)
        assertEquals(FailureReason.UNKNOWN, assertIs<SourceResult.Failure>(src.track("1Z1", null)).reason)
    }

    @Test fun first_tracking_payload_wins() = runTest {
        val src = WebSource(outcomeSpec(), FakeScraper(ScrapeResult.Payloads(listOf(dom("loginWall"), raw("DELIVERED")))))
        val result = assertIs<SourceResult.Success<TrackingSnapshot>>(src.track("1Z1", null))
        assertEquals(TrackingStatus.DELIVERED, result.value.status)
    }

    @Test fun goto_coarse_tracking_is_the_fallback_result() = runTest {
        val src = WebSource(outcomeSpec(), FakeScraper(ScrapeResult.Payloads(listOf(raw("goto"), raw("empty")))))
        val result = assertIs<SourceResult.Success<TrackingSnapshot>>(src.track("113", null))
        assertEquals(TrackingStatus.IN_TRANSIT, result.value.status)
    }

    @Test fun rich_tracking_beats_goto_coarse() = runTest {
        val src = WebSource(outcomeSpec(), FakeScraper(ScrapeResult.Payloads(listOf(raw("goto"), raw("DELIVERED")))))
        val result = assertIs<SourceResult.Success<TrackingSnapshot>>(src.track("113", null))
        assertEquals(TrackingStatus.DELIVERED, result.value.status)
    }

    @Test fun goto_coarse_beats_error_signals() = runTest {
        // The order page proved we're logged in and produced a status; a confused post-hop page
        // must not turn that into an AUTH failure.
        val src = WebSource(outcomeSpec(), FakeScraper(ScrapeResult.Payloads(listOf(raw("goto"), dom("loginWall")))))
        assertIs<SourceResult.Success<TrackingSnapshot>>(src.track("113", null))
    }

    @Test fun rich_result_backfills_missing_eta_and_status_from_coarse() = runTest {
        // Order page knew "Arriving tomorrow" (IN_TRANSIT + ETA); the ship-track hop landed on a
        // page that read UNKNOWN with no ETA. The impoverished rich result must not blank the ETA.
        val src = WebSource(outcomeSpec(), FakeScraper(ScrapeResult.Payloads(listOf(raw("goto"), raw("UNKNOWN")))))
        val result = assertIs<SourceResult.Success<TrackingSnapshot>>(src.track("113", null))
        assertEquals(TrackingStatus.IN_TRANSIT, result.value.status)
        assertEquals(LocalDate(2026, 7, 21), result.value.etaDate)
    }

    @Test fun rich_result_keeps_its_own_eta_and_status_over_coarse() = runTest {
        val src = WebSource(outcomeSpec(), FakeScraper(ScrapeResult.Payloads(listOf(raw("goto"), raw("OUT_FOR_DELIVERY")))))
        val result = assertIs<SourceResult.Success<TrackingSnapshot>>(src.track("113", null))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, result.value.status)
        assertEquals(LocalDate(2026, 7, 20), result.value.etaDate)
    }

    @Test fun goto_without_tracking_alone_is_unknown_failure() = runTest {
        val src = WebSource(outcomeSpec(), FakeScraper(ScrapeResult.Payloads(listOf(raw("gotoBare")))))
        assertEquals(FailureReason.UNKNOWN, assertIs<SourceResult.Failure>(src.track("113", null)).reason)
    }
}

package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.api.FailureReason
import com.dgmltn.shiphappens.source.api.SourceResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class FakeScraper(private val result: ScrapeResult) : WebScraper {
    override val isAvailable = true
    override suspend fun scrape(spec: WebProviderSpec, trackingNumber: String) = result
}

private class TestWebSource(scraper: WebScraper, spec: WebProviderSpec = testSpec()) :
    WebViewBasedSource(spec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? = WellKnownCarriers.UPS
}

private fun dom(page: String) = """{"kind":"dom","body":"{\"page\":\"$page\"}"}"""

/** Wraps a DomExtraction JSON string as a `kind:"dom"` bridge payload (JSON-escaped body). */
private fun domBody(body: String) =
    """{"kind":"dom","body":${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(body))}}"""

private fun gotoDom(withTracking: Boolean): String {
    val tracking = if (withTracking) ""","tracking":{"status":"IN_TRANSIT"}""" else ""
    return domBody("""{"page":"goto","url":"https://www.example.com/t/2"$tracking}""")
}

class WebViewBasedSourceTest {

    @Test fun descriptor_derives_from_spec_and_scraper() {
        val available = TestWebSource(FakeScraper(ScrapeResult.Unavailable))
        assertTrue(available.descriptor.implemented)
        assertEquals("test", available.descriptor.id)
        assertFalse(TestWebSource(NoWebScraper).descriptor.implemented)
    }

    @Test fun tracking_payload_becomes_success() = runTest {
        val spec = testSpec(parseApi = { _, _ -> ScrapedTracking(status = "IN_TRANSIT", location = "Louisville, KY") })
        val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf("""{"kind":"api","url":"u","body":"b"}"""))), spec)
        val result = assertIs<SourceResult.Success<com.dgmltn.shiphappens.domain.TrackingSnapshot>>(src.track("1Z1", null))
        assertEquals(TrackingStatus.IN_TRANSIT, result.value.status)
        assertEquals("Louisville, KY", result.value.latestLocation)
    }

    @Test fun failure_taxonomy_mapping() = runTest {
        suspend fun reasonFor(r: ScrapeResult): FailureReason =
            assertIs<SourceResult.Failure>(TestWebSource(FakeScraper(r)).track("1Z1", null)).reason
        assertEquals(FailureReason.AUTH, reasonFor(ScrapeResult.Payloads(listOf(dom("loginWall")))))
        assertEquals(FailureReason.RATE_LIMITED, reasonFor(ScrapeResult.Payloads(listOf(dom("challenge")))))
        assertEquals(FailureReason.NOT_FOUND, reasonFor(ScrapeResult.Payloads(listOf(dom("notFound")))))
        assertEquals(FailureReason.UNKNOWN, reasonFor(ScrapeResult.Payloads(listOf(dom("empty")))))
        assertEquals(FailureReason.NETWORK, reasonFor(ScrapeResult.LoadError("dns")))
        assertEquals(FailureReason.NETWORK, reasonFor(ScrapeResult.Timeout))
        assertEquals(FailureReason.UNKNOWN, reasonFor(ScrapeResult.Unavailable))
    }

    @Test fun unavailable_scraper_fails_without_scraping() = runTest {
        val src = TestWebSource(NoWebScraper)
        assertEquals(FailureReason.UNKNOWN, assertIs<SourceResult.Failure>(src.track("1Z1", null)).reason)
    }

    @Test fun first_tracking_payload_wins() = runTest {
        val src = TestWebSource(
            FakeScraper(ScrapeResult.Payloads(listOf(dom("loginWall"), """{"kind":"dom","body":"{\"page\":\"ok\",\"tracking\":{\"status\":\"DELIVERED\"}}"}"""))),
        )
        val result = assertIs<SourceResult.Success<com.dgmltn.shiphappens.domain.TrackingSnapshot>>(src.track("1Z1", null))
        assertEquals(TrackingStatus.DELIVERED, result.value.status)
    }

    @Test fun goto_coarse_tracking_is_the_fallback_result() = runTest {
        val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(gotoDom(withTracking = true), dom("empty")))))
        val result = assertIs<SourceResult.Success<com.dgmltn.shiphappens.domain.TrackingSnapshot>>(src.track("113", null))
        assertEquals(TrackingStatus.IN_TRANSIT, result.value.status)
    }

    @Test fun rich_tracking_beats_goto_coarse() = runTest {
        val rich = """{"kind":"dom","body":"{\"page\":\"ok\",\"tracking\":{\"status\":\"DELIVERED\"}}"}"""
        val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(gotoDom(withTracking = true), rich))))
        val result = assertIs<SourceResult.Success<com.dgmltn.shiphappens.domain.TrackingSnapshot>>(src.track("113", null))
        assertEquals(TrackingStatus.DELIVERED, result.value.status)
    }

    @Test fun goto_coarse_beats_error_signals() = runTest {
        // The order page proved we're logged in and produced a status; a confused post-hop page
        // must not turn that into an AUTH failure.
        val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(gotoDom(withTracking = true), dom("loginWall")))))
        assertIs<SourceResult.Success<com.dgmltn.shiphappens.domain.TrackingSnapshot>>(src.track("113", null))
    }

    @Test fun rich_result_backfills_missing_eta_and_status_from_coarse() = runTest {
        // Order page knew "Arriving tomorrow" (IN_TRANSIT + ETA); the ship-track hop landed on a
        // page that read UNKNOWN with no ETA. The impoverished rich result must not blank the ETA.
        val coarse = domBody("""{"page":"goto","url":"https://www.example.com/t/2","tracking":{"status":"IN_TRANSIT","etaDate":"2026-07-21"}}""")
        val rich = domBody("""{"page":"ok","tracking":{"status":"UNKNOWN"}}""")
        val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(coarse, rich))))
        val result = assertIs<SourceResult.Success<com.dgmltn.shiphappens.domain.TrackingSnapshot>>(src.track("113", null))
        assertEquals(TrackingStatus.IN_TRANSIT, result.value.status)
        assertEquals(kotlinx.datetime.LocalDate(2026, 7, 21), result.value.etaDate)
    }

    @Test fun rich_result_keeps_its_own_eta_and_status_over_coarse() = runTest {
        val coarse = domBody("""{"page":"goto","url":"https://www.example.com/t/2","tracking":{"status":"IN_TRANSIT","etaDate":"2026-07-21"}}""")
        val rich = domBody("""{"page":"ok","tracking":{"status":"OUT_FOR_DELIVERY","etaDate":"2026-07-20"}}""")
        val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(coarse, rich))))
        val result = assertIs<SourceResult.Success<com.dgmltn.shiphappens.domain.TrackingSnapshot>>(src.track("113", null))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, result.value.status)
        assertEquals(kotlinx.datetime.LocalDate(2026, 7, 20), result.value.etaDate)
    }

    @Test fun goto_without_tracking_alone_is_unknown_failure() = runTest {
        val src = TestWebSource(FakeScraper(ScrapeResult.Payloads(listOf(gotoDom(withTracking = false)))))
        assertEquals(FailureReason.UNKNOWN, assertIs<SourceResult.Failure>(src.track("113", null)).reason)
    }
}

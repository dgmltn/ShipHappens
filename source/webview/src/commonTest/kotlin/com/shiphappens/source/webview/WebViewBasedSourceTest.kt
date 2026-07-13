package com.shiphappens.source.webview

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.TrackingStatus
import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.api.FailureReason
import com.shiphappens.source.api.SourceResult
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
        val result = assertIs<SourceResult.Success<com.shiphappens.domain.TrackingSnapshot>>(src.track("1Z1", null))
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
        val result = assertIs<SourceResult.Success<com.shiphappens.domain.TrackingSnapshot>>(src.track("1Z1", null))
        assertEquals(TrackingStatus.DELIVERED, result.value.status)
    }
}

package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.testing.SpecSamples
import com.dgmltn.shiphappens.source.webview.testing.WebSourceContract
import com.dgmltn.shiphappens.source.webview.testing.WebSpecContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DhlEcsContractTest {
    @Test fun spec_contract() = WebSpecContract.verify(
        DhlEcsWebSpec,
        SpecSamples(
            trackingNumber = "420300019261234500000000000042",
            capturedApiUrl = "https://api.dhlecs.com/webtrack/v4/tracking",
            uncapturedUrls = listOf(
                "https://api.dhlecs.com/webtrack/v4/utility/config",
                "https://webtrack.dhlecs.com/orders?trackingNumber=420300019261234500000000000042",
            ),
            notFound = listOf(DomRaw(kind = "tracker", pageText = "Unfortunately, no results found. Please confirm")),
            apiBody = """{"total":1,"limit":10,"offset":0,"packages":[{"status":"Delivered","events":[]}]}""" to TrackingStatus.DELIVERED,
        ),
    )

    @Test fun source_contract() = WebSourceContract.verify(
        DhlEcsWebSpec,
        claims = listOf("420300019261234500000000000042", "420 30001 9261-2345 0000 0000 0000 42"),
        rejects = listOf(
            "9261234500000000000042",
            "1Z999AA10123456784",
            "420300011234567890123456789012",
            "42030001926123",
            "42030001" + "9" + "1".repeat(26), // tail beyond 26 digits
        ),
    )

    @Test fun track_fails_without_a_scraper() = runTest {
        WebSourceContract.verifyTrackFailsWithoutScraper(DhlEcsWebSpec, "420300019261234500000000000042")
    }
}

package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.testing.SpecSamples
import com.dgmltn.shiphappens.source.webview.testing.WebSourceContract
import com.dgmltn.shiphappens.source.webview.testing.WebSpecContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AmzlContractTest {
    @Test fun spec_contract() = WebSpecContract.verify(
        AmzlWebSpec,
        SpecSamples(
            trackingNumber = "TBA333593378975",
            capturedApiUrl = "https://track.amazon.com/api/tracker/TBA333593378975",
            uncapturedUrls = listOf("https://www.amazon.com/gp/your-account/order-details"),
            notFound = listOf(DomRaw(kind = "tracker", pageText = "We couldn't find this tracking number")),
            apiBody = """{"progressTracker": "{\"summary\": {\"status\": \"Delivered\", \"metadata\": {}}}"}""" to TrackingStatus.DELIVERED,
        ),
    )

    @Test fun source_contract() = WebSourceContract.verify(
        AmzlWebSpec,
        claims = listOf("TBA333593378975", "tba 3335-9337-8975"),
        rejects = listOf(
            "113-1234567-1234567",
            "1Z999AA10123456784",
            "9434636106092288655003",
            "TBA12345678",
        ),
    )

    @Test fun track_fails_without_a_scraper() = runTest {
        WebSourceContract.verifyTrackFailsWithoutScraper(AmzlWebSpec, "TBA333593378975")
    }
}

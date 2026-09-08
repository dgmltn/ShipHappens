package com.dgmltn.shiphappens.source.usps

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.testing.SpecSamples
import com.dgmltn.shiphappens.source.webview.testing.WebSourceContract
import com.dgmltn.shiphappens.source.webview.testing.WebSpecContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class UspsContractTest {
    @Test fun spec_contract() = WebSpecContract.verify(
        UspsWebSpec,
        SpecSamples(
            trackingNumber = "9434636106092288655003",
            capturedApiUrl = "https://tools.usps.com/go/TrackConfirmAction?tLabels=9434636106092288655003",
            // pattern deliberately broad (design spec §Decisions); UspsWebSpecTest keeps its POLocator negative.
            notFound = listOf(DomRaw(kind = "tracker", pageText = "Status Not Available for this item")),
            apiBody = """{"statusCategory":"Delivered"}""" to TrackingStatus.DELIVERED,
        ),
    )

    @Test fun source_contract() = WebSourceContract.verify(
        UspsWebSpec,
        claims = listOf("9434 6361 0609 2288 6550 03", "EC123456789US"),
        rejects = listOf("1Z999AA10123456784", "941234"),
    )

    @Test fun track_fails_without_a_scraper() = runTest {
        WebSourceContract.verifyTrackFailsWithoutScraper(UspsWebSpec, "9434636106092288655003")
    }
}

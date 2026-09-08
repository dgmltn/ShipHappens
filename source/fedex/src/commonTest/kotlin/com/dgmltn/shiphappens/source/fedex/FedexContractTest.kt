package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.testing.SpecSamples
import com.dgmltn.shiphappens.source.webview.testing.WebSourceContract
import com.dgmltn.shiphappens.source.webview.testing.WebSpecContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class FedexContractTest {
    @Test fun spec_contract() = WebSpecContract.verify(
        FedexWebSpec,
        SpecSamples(
            trackingNumber = "123456789012",
            notFound = listOf(DomRaw(kind = "tracker", pageText = "The tracking number you entered can't be found right now")),
        ),
    )

    @Test fun source_contract() = WebSourceContract.verify(
        FedexWebSpec,
        claims = listOf("1234 5678 9012", "123456789012345", "12345678901234567890", "1234567890123456789012"),
        rejects = listOf(
            "1Z999AA10123456784",
            "TBA333593378975",
            "9434636106092288655003",
            "1234567890123",
            "12345678901234567",
        ),
    )

    @Test fun track_fails_without_a_scraper() = runTest {
        WebSourceContract.verifyTrackFailsWithoutScraper(FedexWebSpec, "123456789012")
    }
}

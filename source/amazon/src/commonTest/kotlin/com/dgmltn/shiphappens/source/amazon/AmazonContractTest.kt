package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.testing.SpecSamples
import com.dgmltn.shiphappens.source.webview.testing.WebSourceContract
import com.dgmltn.shiphappens.source.webview.testing.WebSpecContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AmazonContractTest {
    @Test fun spec_contract() = WebSpecContract.verify(
        AmazonWebSpec,
        SpecSamples(
            trackingNumber = "113-1234567-1234567",
            notFound = listOf(DomRaw(kind = "cards", pageText = "There's a problem finding this order")),
        ),
    )

    @Test fun source_contract() = WebSourceContract.verify(
        AmazonWebSpec,
        claims = listOf("113-1234567-1234567", "701 2345678 9012345", "11312345671234567"),
        rejects = listOf(
            "1Z999AA10123456784",
            "9434636106092288655003",
            "123456789012",
            "213-1234567-1234567",
            "113-1234567-123456",
        ),
    )

    @Test fun track_fails_without_a_scraper() = runTest {
        WebSourceContract.verifyTrackFailsWithoutScraper(AmazonWebSpec, "113-1234567-1234567")
    }
}

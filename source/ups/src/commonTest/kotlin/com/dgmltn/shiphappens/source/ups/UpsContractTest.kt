package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.testing.SpecSamples
import com.dgmltn.shiphappens.source.webview.testing.WebSourceContract
import com.dgmltn.shiphappens.source.webview.testing.WebSpecContract
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class UpsContractTest {
    @Test fun spec_contract() = WebSpecContract.verify(
        UpsWebSpec,
        SpecSamples(
            trackingNumber = "1Z999AA10123456784",
            capturedApiUrl = "https://www.ups.com/track/api/Track/GetStatus?loc=en_US",
            uncapturedUrls = listOf("https://www.ups.com/track?loc=en_US&tracknum=1Z999AA10123456784"),
            notFound = listOf(DomRaw(kind = "tracker", pageText = "The tracking number you entered is invalid")),
            apiBody = """{"trackDetails":[{"packageStatus":"Delivered","packageStatusType":"D"}]}""" to TrackingStatus.DELIVERED,
        ),
    )

    @Test fun source_contract() = WebSourceContract.verify(
        UpsWebSpec,
        claims = listOf("1Z 999 AA1 01 2345 6784"),
        rejects = listOf("9400111899223300112"),
    )

    @Test fun track_fails_without_a_scraper() = runTest {
        WebSourceContract.verifyTrackFailsWithoutScraper(UpsWebSpec, "1Z999AA10123456784")
    }
}

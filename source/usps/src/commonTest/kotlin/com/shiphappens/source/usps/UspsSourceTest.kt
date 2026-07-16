package com.shiphappens.source.usps

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.api.*
import com.shiphappens.source.webview.NoWebScraper
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class UspsSourceTest {
    private val src = UspsWebSource(NoWebScraper)

    @Test fun descriptor_is_web_carrier() {
        assertEquals("usps", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertFalse(src.descriptor.implemented)           // NoWebScraper => not implemented
    }
    @Test fun detects_domestic_and_international_numbers() {
        assertEquals(WellKnownCarriers.USPS, src.detectCarrier("9434 6361 0609 2288 6550 03"))
        assertEquals(WellKnownCarriers.USPS, src.detectCarrier("EC123456789US"))
        assertNull(src.detectCarrier("1Z999AA10123456784"))
        assertNull(src.detectCarrier("941234"))
    }
    @Test fun track_unavailable_without_scraper() = runTest {
        assertIs<SourceResult.Failure>(src.track("9434636106092288655003", null))
    }
}

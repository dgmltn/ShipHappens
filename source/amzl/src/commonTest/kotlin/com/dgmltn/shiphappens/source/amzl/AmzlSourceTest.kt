package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.api.SourceResult
import com.dgmltn.shiphappens.source.webview.NoWebScraper
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class AmzlSourceTest {
    private val src = AmzlWebSource(NoWebScraper)

    @Test fun descriptor_id_and_implemented_flag() {
        assertEquals("amzl", src.descriptor.id)
        assertEquals("Amazon Logistics", src.descriptor.displayName)
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }

    @Test fun detects_tba_numbers_only() {
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, src.detectCarrier("TBA333593378975"))
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, src.detectCarrier("tba 3335-9337-8975"))
        assertNull(src.detectCarrier("113-1234567-1234567"))  // Amazon order id
        assertNull(src.detectCarrier("1Z999AA10123456784"))   // UPS
        assertNull(src.detectCarrier("9434636106092288655003"))  // USPS
        assertNull(src.detectCarrier("TBA12345678"))          // too short (8 digits)
    }

    @Test fun track_is_not_implemented_without_a_scraper() = runTest {
        assertIs<SourceResult.Failure>(src.track("TBA333593378975", null))
    }
}

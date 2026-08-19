package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.api.SourceResult
import com.dgmltn.shiphappens.source.webview.NoWebScraper
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class FedexSourceTest {
    private val src = FedexWebSource(NoWebScraper)

    @Test fun descriptor_id_and_implemented_flag() {
        assertEquals("fedex", src.descriptor.id)
        assertFalse(src.descriptor.implemented)           // NoWebScraper => not implemented
    }

    @Test fun detects_12_15_and_20_to_22_digit_numbers() {
        assertEquals(WellKnownCarriers.FEDEX, src.detectCarrier("1234 5678 9012"))
        assertEquals(WellKnownCarriers.FEDEX, src.detectCarrier("123456789012345"))
        assertEquals(WellKnownCarriers.FEDEX, src.detectCarrier("12345678901234567890"))
        assertEquals(WellKnownCarriers.FEDEX, src.detectCarrier("1234567890123456789012"))
    }

    @Test fun rejects_other_carriers_and_off_lengths() {
        assertNull(src.detectCarrier("1Z999AA10123456784"))     // UPS
        assertNull(src.detectCarrier("TBA333593378975"))        // AMZL
        // 22 digits, so it fits FedEx's length pattern — but the 94 prefix is USPS's; FedEx
        // must not claim it or source resolution would depend on registration order.
        assertNull(src.detectCarrier("9434636106092288655003"))
        assertNull(src.detectCarrier("1234567890123"))          // 13 digits
        assertNull(src.detectCarrier("12345678901234567"))      // 17 digits
    }

    @Test fun track_unavailable_without_scraper() = runTest {
        assertIs<SourceResult.Failure>(src.track("123456789012", null))
    }
}

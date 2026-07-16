package com.shiphappens.source.amazon

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.api.SourceKind
import com.shiphappens.source.api.SourceResult
import com.shiphappens.source.webview.NoWebScraper
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmazonSourceTest {
    private val src = AmazonWebSource(NoWebScraper)

    @Test fun descriptor_is_web_carrier() {
        assertEquals("amazon", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }

    @Test fun detects_order_ids_hyphenated_spaced_and_bare() {
        assertEquals(WellKnownCarriers.AMAZON, src.detectCarrier("113-1234567-1234567"))
        assertEquals(WellKnownCarriers.AMAZON, src.detectCarrier("701 2345678 9012345"))
        assertEquals(WellKnownCarriers.AMAZON, src.detectCarrier("11312345671234567"))
    }

    @Test fun rejects_other_carrier_shapes() {
        assertNull(src.detectCarrier("1Z999AA10123456784"))      // UPS
        assertNull(src.detectCarrier("9434636106092288655003"))  // USPS
        assertNull(src.detectCarrier("123456789012"))            // FedEx 12-digit
        assertNull(src.detectCarrier("213-1234567-1234567"))     // US order ids start 1 or 7
        assertNull(src.detectCarrier("113-1234567-123456"))      // wrong length
    }

    @Test fun track_unavailable_without_scraper() = runTest {
        assertIs<SourceResult.Failure>(src.track("113-1234567-1234567", null))
    }
}

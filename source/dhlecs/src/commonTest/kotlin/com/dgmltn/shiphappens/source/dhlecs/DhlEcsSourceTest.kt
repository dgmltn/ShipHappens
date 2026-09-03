package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.source.api.SourceResult
import com.dgmltn.shiphappens.source.webview.NoWebScraper
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class DhlEcsSourceTest {
    private val src = DhlEcsWebSource(NoWebScraper)

    @Test fun descriptor_id_and_implemented_flag() {
        assertEquals("dhlecs", src.descriptor.id)
        assertEquals("DHL eCommerce", src.descriptor.displayName)
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }

    @Test fun detects_only_the_zip_prefixed_impb_form() {
        // The 420+ZIP+9x… form is unclaimed today; the bare 22-digit IMpb stays with USPS
        // (tools.usps.com tracks these too — decision 2026-09-03).
        assertEquals("dhlecs", src.detectCarrier("420300019261234500000000000042")?.code)
        assertEquals("dhlecs", src.detectCarrier("420 30001 9261-2345 0000 0000 0000 42")?.code)
        assertNull(src.detectCarrier("9261234500000000000042"))          // bare IMpb: USPS's
        assertNull(src.detectCarrier("1Z999AA10123456784"))              // UPS
        assertNull(src.detectCarrier("420300011234567890123456789012")) // remainder isn't 9x…
        assertNull(src.detectCarrier("42030001926123"))                  // too short
        assertNull(src.detectCarrier("42030001" + "9" + "1".repeat(26))) // tail beyond 26 digits
    }

    @Test fun track_is_not_implemented_without_a_scraper() = runTest {
        assertIs<SourceResult.Failure>(src.track("420300019261234500000000000042", null))
    }
}

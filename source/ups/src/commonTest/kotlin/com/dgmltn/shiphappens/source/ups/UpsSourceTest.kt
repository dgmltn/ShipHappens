package com.dgmltn.shiphappens.source.ups

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.source.api.*
import com.dgmltn.shiphappens.source.webview.NoWebScraper
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class UpsSourceTest {
    private val src = UpsWebSource(NoWebScraper)

    @Test fun descriptor_id_and_implemented_flag() {
        assertEquals("ups", src.descriptor.id)
        assertFalse(src.descriptor.implemented)  // NoWebScraper => not implemented
    }
    @Test fun detects_1z_numbers_only() {
        assertEquals(WellKnownCarriers.UPS, src.detectCarrier("1Z 999 AA1 01 2345 6784"))
        assertNull(src.detectCarrier("9400111899223300112"))
    }
    @Test fun track_is_not_implemented() = runTest {
        assertIs<SourceResult.Failure>(src.track("1Z999AA10123456784", null))
    }
}

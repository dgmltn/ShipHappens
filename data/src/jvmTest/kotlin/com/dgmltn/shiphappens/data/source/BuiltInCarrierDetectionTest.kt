package com.dgmltn.shiphappens.data.source

import com.dgmltn.shiphappens.domain.WellKnownCarriers
import kotlin.test.*

class BuiltInCarrierDetectionTest {
    @Test fun detects_ups() {
        assertEquals(WellKnownCarriers.UPS, BuiltInCarrierDetection.detect("1Z 999 AA1 01 2345 6784"))
    }
    @Test fun detects_usps_numeric_and_intl() {
        assertEquals(WellKnownCarriers.USPS, BuiltInCarrierDetection.detect("9400 1118 9922 3300 1122"))
        assertEquals(WellKnownCarriers.USPS, BuiltInCarrierDetection.detect("LK123456789US"))
    }
    @Test fun detects_fedex_12_15_20_22_digits() {
        assertEquals(WellKnownCarriers.FEDEX, BuiltInCarrierDetection.detect("123456789012"))
        assertEquals(WellKnownCarriers.FEDEX, BuiltInCarrierDetection.detect("123456789012345"))
        assertEquals(WellKnownCarriers.FEDEX, BuiltInCarrierDetection.detect("12345678901234567890"))
    }
    @Test fun detects_amazon_order_ids() {
        assertEquals(WellKnownCarriers.AMAZON, BuiltInCarrierDetection.detect("113-1234567-1234567"))
        assertEquals(WellKnownCarriers.AMAZON, BuiltInCarrierDetection.detect("701-2345678-9012345"))
        assertNull(BuiltInCarrierDetection.detect("213-1234567-1234567"))
    }
    @Test fun detects_dhl_ecommerce_zip_prefixed_impb_only() {
        assertEquals("dhlecs", BuiltInCarrierDetection.detect("420 30001 9261-2345 0000 0000 0000 42")?.code)
        // The bare 22-digit IMpb stays USPS's: tools.usps.com tracks these too (2026-09-03).
        assertEquals(WellKnownCarriers.USPS, BuiltInCarrierDetection.detect("9261234500000000000042"))
    }

    @Test fun rejects_short_and_garbage() {
        assertNull(BuiltInCarrierDetection.detect("123"))
        assertNull(BuiltInCarrierDetection.detect("hello world, meeting at 3pm"))
        assertNull(BuiltInCarrierDetection.detect(""))
    }
    @Test fun detects_amazon_logistics_tba() {
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, BuiltInCarrierDetection.detect("TBA333593378975"))
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, BuiltInCarrierDetection.detect("tba 3335 9337 8975"))
        // Boundaries: 9–15 digits after the TBA prefix.
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, BuiltInCarrierDetection.detect("TBA123456789"))
        assertNull(BuiltInCarrierDetection.detect("TBA12345678"))          // 8 digits: too short
        assertNull(BuiltInCarrierDetection.detect("TBA1234567890123456"))  // 16 digits: too long
        // Order ids must still detect as the Amazon orders carrier, not AMZL.
        assertEquals(WellKnownCarriers.AMAZON, BuiltInCarrierDetection.detect("113-1234567-1234567"))
    }
}

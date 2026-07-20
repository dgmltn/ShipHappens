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
    @Test fun rejects_short_and_garbage() {
        assertNull(BuiltInCarrierDetection.detect("123"))
        assertNull(BuiltInCarrierDetection.detect("hello world, meeting at 3pm"))
        assertNull(BuiltInCarrierDetection.detect(""))
    }
}

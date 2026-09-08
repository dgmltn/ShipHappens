package com.dgmltn.shiphappens.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarrierDetectionTest {
    private fun detect(raw: String) = WellKnownCarriers.detect(raw)

    @Test fun detects_ups() {
        assertEquals(WellKnownCarriers.UPS, detect("1Z 999 AA1 01 2345 6784"))
    }

    @Test fun detects_usps_numeric_and_intl() {
        assertEquals(WellKnownCarriers.USPS, detect("9400 1118 9922 3300 1122"))
        assertEquals(WellKnownCarriers.USPS, detect("LK123456789US"))
        assertEquals(WellKnownCarriers.USPS, detect("EC123456789US"))
        assertNull(detect("941234"))
    }

    @Test fun detects_fedex_12_15_20_22_digits() {
        assertEquals(WellKnownCarriers.FEDEX, detect("123456789012"))
        assertEquals(WellKnownCarriers.FEDEX, detect("1234 5678 9012"))
        assertEquals(WellKnownCarriers.FEDEX, detect("123456789012345"))
        assertEquals(WellKnownCarriers.FEDEX, detect("12345678901234567890"))
        assertEquals(WellKnownCarriers.FEDEX, detect("1234567890123456789012"))
        assertNull(detect("1234567890123"))        // 13 digits
        assertFalse(WellKnownCarriers.FEDEX.claims("12345678901234567"))  // 17 digits is not a FedEx shape
    }

    @Test fun fedex_does_not_claim_usps_prefixed_numbers_regardless_of_order() {
        // 22 digits fits FedEx's length pattern, but the 94 prefix is USPS's. The pattern itself
        // excludes it, so resolution can never depend on registration order.
        assertFalse(WellKnownCarriers.FEDEX.claims("9434636106092288655003"))
        assertTrue(WellKnownCarriers.USPS.claims("9434636106092288655003"))
        assertEquals(WellKnownCarriers.USPS, detect("9434636106092288655003"))
    }

    @Test fun detects_amazon_order_ids() {
        assertEquals(WellKnownCarriers.AMAZON, detect("113-1234567-1234567"))
        assertEquals(WellKnownCarriers.AMAZON, detect("701 2345678 9012345"))
        assertEquals(WellKnownCarriers.AMAZON, detect("11312345671234567"))
        assertEquals(WellKnownCarriers.AMAZON, detect("12345678901234567"))  // 17 digits starting with 1
        assertNull(detect("213-1234567-1234567"))   // US order ids start 1 or 7
        assertNull(detect("113-1234567-123456"))    // wrong length
    }

    @Test fun detects_amazon_logistics_tba() {
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, detect("TBA333593378975"))
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, detect("tba 3335 9337 8975"))
        assertEquals(WellKnownCarriers.AMAZON_LOGISTICS, detect("TBA123456789"))
        assertNull(detect("TBA12345678"))          // 8 digits: too short
        assertNull(detect("TBA1234567890123456"))  // 16 digits: too long
    }

    @Test fun detects_dhl_ecommerce_zip_prefixed_impb_only() {
        assertEquals(WellKnownCarriers.DHL_ECOMMERCE, detect("420 30001 9261-2345 0000 0000 0000 42"))
        assertEquals(WellKnownCarriers.DHL_ECOMMERCE, detect("420300019261234500000000000042"))
        // The bare 22-digit IMpb stays USPS's: tools.usps.com tracks these too (2026-09-03).
        assertEquals(WellKnownCarriers.USPS, detect("9261234500000000000042"))
        assertNull(detect("420300011234567890123456789012")) // remainder isn't 9x…
        assertNull(detect("42030001926123"))                  // too short
        assertNull(detect("42030001" + "9" + "1".repeat(26))) // tail beyond 26 digits
    }

    @Test fun rejects_short_and_garbage() {
        assertNull(detect("123"))
        assertNull(detect("hello world, meeting at 3pm"))
        assertNull(detect(""))
    }

    @Test fun a_carrier_without_a_pattern_claims_nothing() {
        assertFalse(Carrier("other", "Other").claims("1Z999AA10123456784"))
    }

    @Test fun carrier_codes_are_persistence_keys_and_stay_stable() {
        assertEquals(listOf("ups", "usps", "fedex", "amazon", "amzl", "dhlecs"), WellKnownCarriers.all.map { it.code })
    }
}

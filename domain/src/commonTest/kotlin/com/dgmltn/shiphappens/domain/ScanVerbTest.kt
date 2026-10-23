package com.dgmltn.shiphappens.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanVerbTest {
    @Test fun arrival_wordings() {
        assertEquals("Arrived", scanVerb("Arrived at USPS Regional Facility"))
        assertEquals("Arrived", scanVerb("Package arrived at a carrier facility."))
        assertEquals("Arrived", scanVerb("ARRIVAL DHL ECOMMERCE FACILITY"))
        assertEquals("Arrived", scanVerb("At FedEx destination facility"))
    }

    @Test fun departure_wordings() {
        assertEquals("Departed", scanVerb("Departed USPS Regional Facility"))
        assertEquals("Departed", scanVerb("Package left the shipper facility"))
        assertEquals("Departed", scanVerb("Left FedEx origin facility"))
        assertEquals("Departed", scanVerb("Shipment has departed"))
    }

    @Test fun processing_wordings() {
        assertEquals("Processed", scanVerb("Processed Through Facility"))
        assertEquals("Processed", scanVerb("Package being processed at carrier facility."))
        assertEquals("Processed", scanVerb("Processing at UPS Facility"))
    }

    @Test fun unrecognized_wording_has_no_verb() {
        assertNull(scanVerb("Out for delivery"))
        assertNull(scanVerb("Delivered, In/At Mailbox"))
        assertNull(scanVerb(""))
    }

    @Test fun delivery_wording_is_never_a_movement() {
        assertNull(scanVerb("Delivered, Left with Individual"))
        assertNull(scanVerb("Delivered, left at front door"))
        assertNull(scanVerb("Your package arrived at its destination and was delivered"))
    }

    @Test fun a_promise_of_arrival_is_not_an_arrival() {
        assertNull(scanVerb("Arriving On Time"))
        assertNull(scanVerb("Arriving Late"))
        assertNull(scanVerb("Your package will arrive later than expected"))
    }
}

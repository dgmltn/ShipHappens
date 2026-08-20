package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusVocabularyTest {

    // -- shared carrier-neutral vocabulary --

    @Test fun classifies_the_carrier_neutral_wordings() {
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, classifyStatusWording("Out for delivery"))
        assertEquals(TrackingStatus.DELIVERED, classifyStatusWording("Delivered: Left at front door"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Delivery exception"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Delivery attempted - notice left"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Action required"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Held at location"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Delivery updated - delay"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Unable to deliver"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Returning to shipper"))
        assertEquals(TrackingStatus.LABEL_CREATED, classifyStatusWording("Label created"))
        assertEquals(TrackingStatus.SHIPPED, classifyStatusWording("Picked up"))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyStatusWording("In transit"))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyStatusWording("On the way"))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyStatusWording("On its way to you"))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyStatusWording("Departed facility"))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyStatusWording("Arrived at facility"))
    }

    @Test fun whitespace_and_case_are_normalized() {
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, classifyStatusWording("  OUT   FOR\nDELIVERY  "))
    }

    @Test fun unknown_blank_and_null_are_null_not_a_guess() {
        assertNull(classifyStatusWording("Delivery date pending"))
        assertNull(classifyStatusWording(""))
        assertNull(classifyStatusWording("   "))
        assertNull(classifyStatusWording(null))
    }

    // -- precedence: one merged chain, canonical order --

    @Test fun out_for_delivery_wins_over_transit_wording_in_the_same_text() {
        assertEquals(
            TrackingStatus.OUT_FOR_DELIVERY,
            classifyStatusWording("Out for delivery — departed local facility"),
        )
    }

    @Test fun exception_wording_does_not_leak_into_delivery_branches() {
        // "delivery exception" contains "delivery"; "undeliverable" contains "deliver".
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Delivery exception"))
        assertNull(classifyStatusWording("deliverable soon"))
    }

    // -- provider extras merge INTO the shared chain, not around it --

    private val extras = StatusKeywords(
        outForDelivery = listOf("on fedex vehicle"),
        exception = listOf("incorrect address"),
        labelCreated = listOf("shipment information sent"),
        shipped = listOf("we have your package"),
        inTransit = listOf("processed"),
    )

    @Test fun extras_classify_alongside_shared_keywords() {
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, classifyStatusWording("On FedEx vehicle for delivery", extras))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Incorrect address provided", extras))
        assertEquals(TrackingStatus.LABEL_CREATED, classifyStatusWording("Shipment information sent to FedEx", extras))
        assertEquals(TrackingStatus.SHIPPED, classifyStatusWording("We have your package", extras))
        assertEquals(TrackingStatus.IN_TRANSIT, classifyStatusWording("Processed through facility", extras))
    }

    @Test fun shared_precedence_beats_a_later_stage_extra() {
        // The USPS trap that forbids naive local-first composition: a transit extra ("processed")
        // must not shadow the shared out-for-delivery match earlier in the chain.
        assertEquals(
            TrackingStatus.OUT_FOR_DELIVERY,
            classifyStatusWording("Out for delivery, processed through facility", extras),
        )
    }

    @Test fun extras_earlier_in_the_chain_beat_shared_later_stages() {
        // An extra exception keyword must win over a shared transit keyword in the same text.
        assertEquals(
            TrackingStatus.EXCEPTION,
            classifyStatusWording("Incorrect address — departed facility", extras),
        )
    }
}

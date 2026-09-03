package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusVocabularyTest {

    // -- shared carrier-neutral vocabulary --

    @Test fun classifies_the_carrier_neutral_wordings() {
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, classifyStatusWording("Out for delivery"))
        assertEquals(TrackingStatus.DELIVERED, classifyStatusWording("Delivered: Left at front door"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Delivery exception"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Delivery attempted - notice left"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Action required"))
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Held at location"))
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

    // -- delay is a modifier, not a stage --

    @Test fun delay_wording_does_not_hide_the_stage_the_package_is_actually_at() {
        // UPS's "On the Way: Delayed" is an in-transit package with a delay, not an exception.
        assertEquals(TrackingStatus.IN_TRANSIT, classifyStatusWording("On the Way: Delayed"))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, classifyStatusWording("Out for delivery, delayed"))
    }

    @Test fun delay_wording_alone_asserts_no_stage_at_all() {
        // A delay says nothing about WHERE the package is: an order can be late before it ships,
        // late in transit, or late out for delivery. Answering EXCEPTION (or IN_TRANSIT) here
        // would be a guess; null is the caller's cue to take the stage from the event rows or the
        // stored status instead. The delay itself still reports, via isDelayedWording.
        assertNull(classifyStatusWording("Delivery updated - delay"))
        assertNull(classifyStatusWording("Delayed"))
        assertNull(classifyStatusWording("Running late"))
        assertTrue(isDelayedWording("Delivery updated - delay"))
    }

    @Test fun a_real_exception_still_beats_delay_wording() {
        assertEquals(TrackingStatus.EXCEPTION, classifyStatusWording("Delivery attempted, delayed"))
    }

    @Test fun delay_is_detected_independently_of_the_stage() {
        assertTrue(isDelayedWording("On the Way: Delayed"))
        assertTrue(isDelayedWording("Delivery updated - delay"))
        assertTrue(isDelayedWording("  DELAYED  "))
        assertFalse(isDelayedWording("In transit"))
        assertFalse(isDelayedWording("Out for delivery"))
        assertFalse(isDelayedWording(""))
        assertFalse(isDelayedWording(null))
    }

    @Test fun provider_extras_add_delay_wordings() {
        val amazonish = StatusKeywords(delayed = listOf("now expected"))
        assertTrue(isDelayedWording("Now expected tomorrow by 8 AM", amazonish))
        assertFalse(isDelayedWording("Now expected tomorrow by 8 AM"))
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

    @Test fun multi_stage_wording_is_recognized_as_a_progress_rail() {
        // A scraped progress rail carries EVERY step label regardless of the package's state
        // (dhlecs live QA 2026-09-03: "…Electronic Notification…NotifiedEn RouteDelivered…"
        // classified DELIVERED for a label-only package). Two or more distinct stages in one
        // wording is that signature, and no real single-status sentence looks like it.
        val dhlish = StatusKeywords(labelCreated = listOf("electronic notification"), inTransit = listOf("en route"))
        assertTrue(isMultiStageWording("Electronic Notification Notified En Route Delivered", dhlish))
        assertTrue(isMultiStageWording("Label created In transit Delivered"))
        // Single-status sentences — including multi-keyword ones within a stage — are not rails.
        assertFalse(isMultiStageWording("Out for delivery"))
        assertFalse(isMultiStageWording("En Route - Delayed", dhlish))  // delay is a modifier, not a stage
        assertFalse(isMultiStageWording("Delivery attempted; returned for re-delivery"))
        assertFalse(isMultiStageWording("Electronic Notification", dhlish))
        assertFalse(isMultiStageWording(""))
        assertFalse(isMultiStageWording(null))
    }

    @Test fun extras_earlier_in_the_chain_beat_shared_later_stages() {
        // An extra exception keyword must win over a shared transit keyword in the same text.
        assertEquals(
            TrackingStatus.EXCEPTION,
            classifyStatusWording("Incorrect address — departed facility", extras),
        )
    }
}

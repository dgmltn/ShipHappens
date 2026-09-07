package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatusVocabularyTest {

    private val SHARED = StatusVocabulary()

    // -- shared carrier-neutral vocabulary --

    @Test fun classifies_the_carrier_neutral_wordings() {
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, SHARED.classify("Out for delivery"))
        assertEquals(TrackingStatus.DELIVERED, SHARED.classify("Delivered: Left at front door"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Delivery exception"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Delivery attempted - notice left"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Action required"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Held at location"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Unable to deliver"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Returning to shipper"))
        assertEquals(TrackingStatus.LABEL_CREATED, SHARED.classify("Label created"))
        assertEquals(TrackingStatus.SHIPPED, SHARED.classify("Picked up"))
        assertEquals(TrackingStatus.IN_TRANSIT, SHARED.classify("In transit"))
        assertEquals(TrackingStatus.IN_TRANSIT, SHARED.classify("On the way"))
        assertEquals(TrackingStatus.IN_TRANSIT, SHARED.classify("On its way to you"))
        assertEquals(TrackingStatus.IN_TRANSIT, SHARED.classify("Departed facility"))
        assertEquals(TrackingStatus.IN_TRANSIT, SHARED.classify("Arrived at facility"))
    }

    @Test fun whitespace_and_case_are_normalized() {
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, SHARED.classify("  OUT   FOR\nDELIVERY  "))
    }

    @Test fun unknown_blank_and_null_are_null_not_a_guess() {
        assertNull(SHARED.classify("Delivery date pending"))
        assertNull(SHARED.classify(""))
        assertNull(SHARED.classify("   "))
        assertNull(SHARED.classify(null))
    }

    // -- delay is a modifier, not a stage --

    @Test fun delay_wording_does_not_hide_the_stage_the_package_is_actually_at() {
        // UPS's "On the Way: Delayed" is an in-transit package with a delay, not an exception.
        assertEquals(TrackingStatus.IN_TRANSIT, SHARED.classify("On the Way: Delayed"))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, SHARED.classify("Out for delivery, delayed"))
    }

    @Test fun delay_wording_alone_asserts_no_stage_at_all() {
        // A delay says nothing about WHERE the package is: an order can be late before it ships,
        // late in transit, or late out for delivery. Answering EXCEPTION (or IN_TRANSIT) here
        // would be a guess; null is the caller's cue to take the stage from the event rows or the
        // stored status instead. The delay itself still reports, via isDelayed.
        assertNull(SHARED.classify("Delivery updated - delay"))
        assertNull(SHARED.classify("Delayed"))
        assertNull(SHARED.classify("Running late"))
        assertTrue(SHARED.isDelayed("Delivery updated - delay"))
    }

    @Test fun a_real_exception_still_beats_delay_wording() {
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Delivery attempted, delayed"))
    }

    @Test fun delay_is_detected_independently_of_the_stage() {
        assertTrue(SHARED.isDelayed("On the Way: Delayed"))
        assertTrue(SHARED.isDelayed("Delivery updated - delay"))
        assertTrue(SHARED.isDelayed("  DELAYED  "))
        assertFalse(SHARED.isDelayed("In transit"))
        assertFalse(SHARED.isDelayed("Out for delivery"))
        assertFalse(SHARED.isDelayed(""))
        assertFalse(SHARED.isDelayed(null))
    }

    @Test fun provider_extras_add_delay_wordings() {
        val amazonish = StatusKeywords(delayed = listOf("now expected"))
        assertTrue(StatusVocabulary(amazonish).isDelayed("Now expected tomorrow by 8 AM"))
        assertFalse(SHARED.isDelayed("Now expected tomorrow by 8 AM"))
    }

    // -- precedence: one merged chain, canonical order --

    @Test fun out_for_delivery_wins_over_transit_wording_in_the_same_text() {
        assertEquals(
            TrackingStatus.OUT_FOR_DELIVERY,
            SHARED.classify("Out for delivery — departed local facility"),
        )
    }

    @Test fun exception_wording_does_not_leak_into_delivery_branches() {
        // "delivery exception" contains "delivery"; "undeliverable" contains "deliver".
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Delivery exception"))
        assertNull(SHARED.classify("deliverable soon"))
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
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, StatusVocabulary(extras).classify("On FedEx vehicle for delivery"))
        assertEquals(TrackingStatus.EXCEPTION, StatusVocabulary(extras).classify("Incorrect address provided"))
        assertEquals(TrackingStatus.LABEL_CREATED, StatusVocabulary(extras).classify("Shipment information sent to FedEx"))
        assertEquals(TrackingStatus.SHIPPED, StatusVocabulary(extras).classify("We have your package"))
        assertEquals(TrackingStatus.IN_TRANSIT, StatusVocabulary(extras).classify("Processed through facility"))
    }

    @Test fun shared_precedence_beats_a_later_stage_extra() {
        // The USPS trap that forbids naive local-first composition: a transit extra ("processed")
        // must not shadow the shared out-for-delivery match earlier in the chain.
        assertEquals(
            TrackingStatus.OUT_FOR_DELIVERY,
            StatusVocabulary(extras).classify("Out for delivery, processed through facility"),
        )
    }

    @Test fun multi_stage_wording_is_recognized_as_a_progress_rail() {
        // A scraped progress rail carries EVERY step label regardless of the package's state
        // (dhlecs live QA 2026-09-03: "…Electronic Notification…NotifiedEn RouteDelivered…"
        // classified DELIVERED for a label-only package). Two or more distinct stages in one
        // wording is that signature, and no real single-status sentence looks like it.
        val dhlish = StatusKeywords(labelCreated = listOf("electronic notification"), inTransit = listOf("en route"))
        assertTrue(StatusVocabulary(dhlish).isMultiStage("Electronic Notification Notified En Route Delivered"))
        assertTrue(SHARED.isMultiStage("Label created In transit Delivered"))
        // Single-status sentences — including multi-keyword ones within a stage — are not rails.
        assertFalse(SHARED.isMultiStage("Out for delivery"))
        assertFalse(StatusVocabulary(dhlish).isMultiStage("En Route - Delayed"))  // delay is a modifier, not a stage
        assertFalse(SHARED.isMultiStage("Delivery attempted; returned for re-delivery"))
        assertFalse(StatusVocabulary(dhlish).isMultiStage("Electronic Notification"))
        assertFalse(SHARED.isMultiStage(""))
        assertFalse(SHARED.isMultiStage(null))
    }

    @Test fun extras_earlier_in_the_chain_beat_shared_later_stages() {
        // An extra exception keyword must win over a shared transit keyword in the same text.
        assertEquals(
            TrackingStatus.EXCEPTION,
            StatusVocabulary(extras).classify("Incorrect address — departed facility"),
        )
    }

    // -- negated delivery: checked on every vocabulary, ahead of the DELIVERED lane --

    @Test fun undelivered_wording_is_an_exception_not_a_delivery() {
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Undelivered - Processes for Local Disposal"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classify("Package not delivered: address unknown"))
        assertFalse(SHARED.isMultiStage("Undelivered - Processes for Local Disposal"))
    }

    @Test fun negation_guard_applies_without_the_carrier_base_too() {
        val bare = StatusVocabulary(StatusKeywords(delivered = listOf("delivered")), base = BaseKeywords.None)
        assertEquals(TrackingStatus.DELIVERED, bare.classify("Delivered today"))
        assertEquals(TrackingStatus.EXCEPTION, bare.classify("Undelivered"))
    }

    // -- BaseKeywords.None: only the carrier's own phrases --

    @Test fun a_none_base_vocabulary_knows_nothing_it_was_not_told() {
        val strict = StatusVocabulary(StatusKeywords(inTransit = listOf("on the way")), base = BaseKeywords.None)
        assertEquals(TrackingStatus.IN_TRANSIT, strict.classify("On the way"))
        assertNull(strict.classify("Out for delivery"))
        assertNull(strict.classify("We've received your return"))
        assertFalse(strict.isDelayed("Delayed"))
    }

    // -- API tokens --

    @Test fun tokens_are_humanized_before_classification() {
        assertEquals("out for delivery", humanizeToken("OutForDelivery"))
        assertEquals("ready for receive", humanizeToken("READY_FOR_RECEIVE"))
        assertEquals("in transit delayed", humanizeToken("InTransitDelayed"))
        assertEquals("", humanizeToken(null))
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, SHARED.classifyToken("OutForDelivery"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classifyToken("DeliveryAttempted"))
        assertEquals(TrackingStatus.EXCEPTION, SHARED.classifyToken("ReturnedToSeller"))
        assertEquals(TrackingStatus.IN_TRANSIT, SHARED.classifyToken("InTransitDelayed"))
        assertTrue(SHARED.isDelayedToken("InTransitDelayed"))
        assertNull(SHARED.classifyToken("Delayed"))
        assertNull(SHARED.classifyToken("SomeNewWording"))
    }
}

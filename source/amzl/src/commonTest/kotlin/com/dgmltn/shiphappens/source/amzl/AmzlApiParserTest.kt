package com.dgmltn.shiphappens.source.amzl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Live capture 2026-08-11, TBA333593378975 (pre-first-scan package: label created, ETA Aug 13).
private const val FIXTURE = """
{"progressTracker": "{\"summary\": {\"status\": \"CreationConfirmed\", \"metadata\": {\"promisedDeliveryDate\": {\"date\": \"Aug 13, 2026, 3:00:00 AM\", \"type\": \"DATE\"}, \"expectedDeliveryDate\": {\"date\": \"Aug 13, 2026, 3:00:00 AM\", \"type\": \"DATE\"}, \"trackingStatus\": {\"stringValue\": \"READY_FOR_RECEIVE\"}, \"lastLegCarrier\": {\"stringValue\": \"Amazon\", \"type\": \"STRING\"}}, \"containerStatusTags\": [\"READY_FOR_RECEIVE\"]}, \"expectedDeliveryDate\": \"Aug 13, 2026, 3:00:00 AM\", \"legType\": \"FORWARD\"}", "eventHistory": "{\"eventHistory\": [{\"eventCode\": \"CreationConfirmed\", \"statusSummary\": {\"localisedStringId\": \"swa_rex_detail_creation_confirmed\"}, \"eventTime\": \"Aug 10, 2026, 10:33:20 PM\", \"location\": {}, \"shipmentType\": \"FORWARD\", \"eventMetadata\": {}}], \"summary\": {\"status\": \"READY_FOR_RECEIVE\"}, \"trackerSource\": \"SWA\"}"}
"""

// Live capture 2026-08-11, unknown TBA: HTTP 200 with an in-band error, eventHistory null.
private const val NOT_FOUND_FIXTURE = """
{"progressTracker": "{\"errors\": [{\"errorCode\": \"TRACKING_ID_NOT_FOUND\", \"errorMessage\": \"INVALID TRACKING_ID\"}], \"summary\": {\"status\": null, \"metadata\": {}, \"proofOfDelivery\": null, \"containerStatusTags\": null, \"valueAddedServices\": null, \"trackingDetailCodes\": null, \"signedStatus\": null}}", "eventHistory": null}
"""

class AmzlApiParserTest {

    /** Builds a minimal double-encoded envelope with the given inner summary fields. */
    private fun envelope(summaryStatus: String?, trackingStatus: String? = null): String {
        val status = summaryStatus?.let { "\\\"$it\\\"" } ?: "null"
        val meta = trackingStatus?.let { """{\"trackingStatus\":{\"stringValue\":\"$it\"}}""" } ?: "{}"
        return """{"progressTracker": "{\"summary\": {\"status\": $status, \"metadata\": $meta}}"}"""
    }

    @Test fun parses_status_eta_and_events_from_live_fixture() {
        val t = assertNotNull(AmzlApiParser.parse(FIXTURE))
        assertEquals("LABEL_CREATED", t.status)
        assertEquals("2026-08-13", t.etaDate)     // date only — the 3:00 AM is not a delivery window
        assertNull(t.etaWindowStart)
        assertNull(t.etaWindowEnd)
        assertEquals(1, t.events.size)
        assertEquals("Label created", t.events.single().description)
        assertEquals("LABEL_CREATED", t.events.single().status)
        // Timestamps are ISO instants (parseable by the canonical layer).
        assertTrue(t.events.all { runCatching { kotlin.time.Instant.parse(it.timestamp) }.isSuccess })
    }

    @Test fun not_found_error_body_returns_null() {
        assertNull(AmzlApiParser.parse(NOT_FOUND_FIXTURE))
    }

    @Test fun summary_status_vocabulary_maps_to_canonical() {
        assertEquals("LABEL_CREATED", AmzlApiParser.parse(envelope("CreationConfirmed"))!!.status)
        assertEquals("SHIPPED", AmzlApiParser.parse(envelope("PickupDone"))!!.status)
        assertEquals("IN_TRANSIT", AmzlApiParser.parse(envelope("InTransit"))!!.status)
        assertEquals("IN_TRANSIT", AmzlApiParser.parse(envelope("ArrivedAtDeliveryCenter"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", AmzlApiParser.parse(envelope("OutForDelivery"))!!.status)
        assertEquals("DELIVERED", AmzlApiParser.parse(envelope("Delivered"))!!.status)
        assertEquals("EXCEPTION", AmzlApiParser.parse(envelope("DeliveryAttempted"))!!.status)
        assertEquals("EXCEPTION", AmzlApiParser.parse(envelope("Undeliverable"))!!.status)
        assertEquals("EXCEPTION", AmzlApiParser.parse(envelope("ReturnedToSeller"))!!.status)
        assertEquals("UNKNOWN", AmzlApiParser.parse(envelope("SomeNewWording"))!!.status)
    }

    @Test fun delay_tokens_keep_the_stage_and_report_the_delay() {
        // "InTransitDelayed" names its stage; only bare delay tokens fall through to EXCEPTION.
        assertEquals("IN_TRANSIT", AmzlApiParser.parse(envelope("InTransitDelayed"))!!.status)
        assertEquals("EXCEPTION", AmzlApiParser.parse(envelope("Delayed"))!!.status)
        assertEquals("EXCEPTION", AmzlApiParser.parse(envelope("DeliveryDelayed"))!!.status)
    }

    @Test fun a_delay_token_becomes_a_readable_note() {
        // The API carries codes, not prose, so the note reuses the CamelCase-splitting describe().
        assertEquals("Delivery delayed", AmzlApiParser.parse(envelope("DeliveryDelayed"))!!.delayNote)
        assertEquals("In transit delayed", AmzlApiParser.parse(envelope("InTransitDelayed"))!!.delayNote)
    }

    @Test fun an_undelayed_package_has_no_delay_note() {
        assertNull(AmzlApiParser.parse(envelope("OutForDelivery"))!!.delayNote)
        assertNull(AmzlApiParser.parse(FIXTURE)!!.delayNote)
    }

    @Test fun tracking_status_is_the_fallback_when_summary_status_is_missing() {
        assertEquals("LABEL_CREATED", AmzlApiParser.parse(envelope(null, "READY_FOR_RECEIVE"))!!.status)
        assertEquals("OUT_FOR_DELIVERY", AmzlApiParser.parse(envelope(null, "OUT_FOR_DELIVERY"))!!.status)
        assertEquals("DELIVERED", AmzlApiParser.parse(envelope(null, "DELIVERED"))!!.status)
    }

    @Test fun parses_dates_with_narrow_spaces() {
        // Newer JDK/ICU date formatting inserts U+202F before AM/PM; Amazon may follow.
        val nnbsp = '\u202F'
        val body = """{"progressTracker": "{\"summary\": {\"status\": \"InTransit\", \"metadata\": {\"promisedDeliveryDate\": {\"date\": \"Aug 13, 2026, 3:00:00${nnbsp}AM\"}}}}"}"""
        assertEquals("2026-08-13", AmzlApiParser.parse(body)!!.etaDate)
    }

    @Test fun unknown_event_codes_fall_back_to_split_camel_case() {
        val body = """{"progressTracker": "{\"summary\": {\"status\": \"InTransit\", \"metadata\": {}}}", "eventHistory": "{\"eventHistory\": [{\"eventCode\": \"ArrivedAtDeliveryStation\", \"eventTime\": \"Aug 11, 2026, 4:05:00 AM\", \"location\": {}}]}"}"""
        assertEquals("Arrived at delivery station", AmzlApiParser.parse(body)!!.events.single().description)
    }

    @Test fun rejects_non_tracking_json() {
        assertNull(AmzlApiParser.parse("not json"))
        assertNull(AmzlApiParser.parse("""{"unrelated": true}"""))
        // Envelope decodes but the inner progressTracker string is garbage.
        assertNull(AmzlApiParser.parse("""{"progressTracker": "not json either"}"""))
    }

    @Test fun tolerates_missing_fields() {
        val t = assertNotNull(AmzlApiParser.parse(envelope("Delivered")))
        assertEquals("DELIVERED", t.status)
        assertNull(t.etaDate)
        assertTrue(t.events.isEmpty())
    }
}

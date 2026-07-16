package com.shiphappens.domain

import kotlin.test.*

class ModelTest {
    @Test fun normalize_strips_whitespace_and_dashes_and_uppercases() {
        assertEquals("1Z999AA10123456784", normalizeTracking("1z 999-aa1 01 2345 6784"))
    }

    @Test fun wellKnown_carriers_have_brand_colors() {
        assertEquals("#1E3A8F", WellKnownCarriers.USPS.accentColorHex)
        assertEquals("#5A3A22", WellKnownCarriers.UPS.accentColorHex)
        assertEquals("#5A1B9A", WellKnownCarriers.FEDEX.accentColorHex)
        assertEquals(WellKnownCarriers.FEDEX, WellKnownCarriers.byCode("FEDEX"))
        assertNull(WellKnownCarriers.byCode("dhl"))
    }

    @Test fun amazon_carrier_is_well_known() {
        assertEquals("#995C00", WellKnownCarriers.AMAZON.accentColorHex)
        assertEquals(WellKnownCarriers.AMAZON, WellKnownCarriers.byCode("Amazon"))
    }

    @Test fun fallback_color_is_deterministic_and_from_palette() {
        assertEquals(fallbackAccentColor("dhl"), fallbackAccentColor("dhl"))
        assertTrue(fallbackAccentColor("dhl").startsWith("#"))
        assertNotEquals(fallbackAccentColor("dhl"), fallbackAccentColor("royal-mail"))
    }

    @Test fun status_step_index() {
        assertEquals(0, TrackingStatus.LABEL_CREATED.stepIndex)
        assertEquals(4, TrackingStatus.DELIVERED.stepIndex)
        assertEquals(-1, TrackingStatus.EXCEPTION.stepIndex)
    }

    @Test fun effective_step_index_uses_status_or_falls_back_to_events() {
        val base = Parcel(
            id = "p1", name = "Cap", trackingNumber = "94001118", carrier = WellKnownCarriers.USPS,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        )
        assertEquals(0, base.effectiveStepIndex)  // UNKNOWN, no events
        assertEquals(3, base.copy(status = TrackingStatus.OUT_FOR_DELIVERY).effectiveStepIndex)
        val events = listOf(
            TrackingEvent(kotlin.time.Instant.fromEpochMilliseconds(1), "Shipped", status = TrackingStatus.SHIPPED),
            TrackingEvent(kotlin.time.Instant.fromEpochMilliseconds(2), "In transit", status = TrackingStatus.IN_TRANSIT),
        )
        assertEquals(2, base.copy(status = TrackingStatus.EXCEPTION, events = events).effectiveStepIndex)
    }

    @Test fun parcel_exposes_normalized_tracking() {
        val p = Parcel(
            id = "p1", name = "Cap", trackingNumber = "94 001-118", carrier = WellKnownCarriers.USPS,
            createdAt = kotlin.time.Instant.fromEpochMilliseconds(0),
        )
        assertEquals("94001118", p.normalizedTracking)
    }
}

package com.shiphappens.source.demo

import com.shiphappens.domain.*
import com.shiphappens.source.api.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.test.*

class DemoSourceTest {
    private val src = DemoSource(
        today = { LocalDate(2026, 7, 10) },
        now = { Instant.fromEpochMilliseconds(1_752_148_800_000) },
    )

    @Test fun seeds_the_seven_design_parcels() {
        val seeds = src.seeds()
        assertEquals(7, seeds.size)
        assertTrue(seeds.any { it.name == "Baseball cap" })
        assertTrue(seeds.any { it.name == "Trail running shoes" })
    }

    @Test fun tracks_known_numbers_with_relative_etas() = runTest {
        val shoes = src.seeds().first { it.name == "Trail running shoes" }
        val r = src.track(shoes.trackingNumber, shoes.carrier)
        assertIs<SourceResult.Success<TrackingSnapshot>>(r)
        assertEquals(TrackingStatus.OUT_FOR_DELIVERY, r.value.status)
        assertEquals(LocalDate(2026, 7, 11), r.value.etaDate)   // today + 1
        assertTrue(r.value.events.isNotEmpty())
    }

    @Test fun delivered_seed_is_delivered() = runTest {
        val beans = src.seeds().first { it.name == "Oat-blend coffee beans" }
        val r = src.track(beans.trackingNumber, beans.carrier) as SourceResult.Success
        assertEquals(TrackingStatus.DELIVERED, r.value.status)
    }

    @Test fun unknown_number_is_not_found_and_detect_covers_seeds() = runTest {
        assertIs<SourceResult.Failure>(src.track("nope-123456789", null))
        val cap = src.seeds().first { it.name == "Baseball cap" }
        assertEquals(cap.carrier, src.detectCarrier(cap.trackingNumber))
        assertNull(src.detectCarrier("nope-123456789"))
    }
}

// geo/src/commonTest/kotlin/com/dgmltn/shiphappens/geo/RouteTest.kt
package com.dgmltn.shiphappens.geo

import com.dgmltn.shiphappens.domain.TrackingEvent
import kotlin.time.Instant
import kotlin.test.*

class RouteTest {
    private fun ev(t: Long, loc: String?, desc: String = "scan $t") = TrackingEvent(Instant.fromEpochSeconds(t), desc, loc)
    private val coords = mapOf(
        "FOSTER CITY, CA" to LatLng(37.55, -122.27),
        "SACRAMENTO, CA" to LatLng(38.58, -121.49),
        "CARLSBAD, CA" to LatLng(33.16, -117.35),
    )

    @Test fun orders_oldest_first_regardless_of_input_order() {
        val r = buildRoute(listOf(ev(3, "Carlsbad, CA"), ev(1, "Foster City, CA"), ev(2, "Sacramento, CA")), coords)
        assertEquals(listOf("FOSTER CITY, CA", "SACRAMENTO, CA", "CARLSBAD, CA"), r.map { it.key })
    }

    @Test fun consecutive_scans_at_one_place_collapse() {
        val r = buildRoute(listOf(ev(1, "Sacramento, CA", "Arrived"), ev(2, "SACRAMENTO, CA 95826", "Departed")), coords)
        assertEquals(1, r.size)
        assertEquals(listOf("Arrived", "Departed"), r[0].events.map { it.description })
    }

    @Test fun non_consecutive_revisit_is_its_own_stop() {
        val r = buildRoute(listOf(ev(1, "Sacramento, CA"), ev(2, "Carlsbad, CA"), ev(3, "Sacramento, CA")), coords)
        assertEquals(listOf("SACRAMENTO, CA", "CARLSBAD, CA", "SACRAMENTO, CA"), r.map { it.key })
    }

    @Test fun unresolved_and_missing_locations_are_skipped_without_splitting_a_stop() {
        val r = buildRoute(listOf(ev(1, "Sacramento, CA"), ev(2, null), ev(3, "Atlantis, ZZ"), ev(4, "Sacramento, CA")), coords)
        assertEquals(1, r.size)
        assertEquals(2, r[0].events.size)
    }

    @Test fun country_only_location_contributes_no_stop() {
        val r = buildRoute(listOf(ev(1, "United States"), ev(2, "Sacramento, CA")), coords + ("UNITED STATES" to LatLng(39.8, -98.6)))
        assertEquals(listOf("SACRAMENTO, CA"), r.map { it.key })
    }

    @Test fun empty_input_is_empty_route() = assertTrue(buildRoute(emptyList(), coords).isEmpty())

    @Test fun display_name_is_title_cased_from_shouting() {
        assertEquals("South San Francisco, CA", displayPlace("SOUTH SAN FRANCISCO CA 94080 US"))
        assertEquals("Foster City, CA", displayPlace("Foster City, CA"))
        assertEquals("Shenzhen, CN", displayPlace("SHENZHEN, CN"))
    }

    @Test fun stop_display_name_comes_from_its_first_event() {
        val r = buildRoute(listOf(ev(1, "SACRAMENTO, CA"), ev(2, "Sacramento, CA 95826")), coords)
        assertEquals("Sacramento, CA", r[0].displayName)
    }
}

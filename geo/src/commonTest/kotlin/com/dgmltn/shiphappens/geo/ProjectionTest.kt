package com.dgmltn.shiphappens.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.test.*

class ProjectionTest {
    private val sf = LatLng(37.77, -122.42)
    private val sac = LatLng(38.58, -121.49)
    private val carlsbad = LatLng(33.16, -117.35)

    @Test fun empty_has_no_bounds() = assertNull(routeBounds(emptyList(), aspect = 2.0))

    @Test fun bounds_contain_every_point_with_padding() {
        val b = assertNotNull(routeBounds(listOf(sf, sac, carlsbad), aspect = 2.0))
        assertTrue(b.minLat < 33.16 && b.maxLat > 38.58)
        assertTrue(b.minLng < -122.42 && b.maxLng > -117.35)
    }

    @Test fun single_point_gets_minimum_span() {
        val b = assertNotNull(routeBounds(listOf(sac), aspect = 1.0))
        assertTrue(b.maxLat - b.minLat >= 3.0 - 1e-9)
        assertEquals(sac.lat, (b.minLat + b.maxLat) / 2, 1e-6)
    }

    @Test fun bounds_match_requested_aspect_in_scaled_space() {
        val b = assertNotNull(routeBounds(listOf(sf, carlsbad), aspect = 2.4))
        val k = cos(toRadians((b.minLat + b.maxLat) / 2))
        val scaledW = (b.maxLng - b.minLng) * k
        val h = b.maxLat - b.minLat
        assertEquals(2.4, scaledW / h, 1e-6)
    }

    @Test fun projector_maps_corners_to_edges() {
        val b = assertNotNull(routeBounds(listOf(sf, carlsbad), aspect = 2.0))
        val p = Projector(b, width = 200f, height = 100f)
        assertEquals(0f, p.x(b.minLng), 1e-3f)
        assertEquals(200f, p.x(b.maxLng), 1e-3f)
        assertEquals(100f, p.y(b.minLat), 1e-3f)  // north is up
        assertEquals(0f, p.y(b.maxLat), 1e-3f)
    }

    @Test fun antimeridian_route_is_shifted() {
        val shenzhen = LatLng(22.54, 114.06)
        val la = LatLng(33.94, -118.41)
        val b = assertNotNull(routeBounds(listOf(shenzhen, la), aspect = 2.0))
        assertTrue(b.shifted)
        assertTrue(b.maxLng - b.minLng < 180.0, "span=${b.maxLng - b.minLng}")
        val p = Projector(b, 200f, 100f)
        assertTrue(p.x(la.lng) > p.x(shenzhen.lng), "LA should be east of Shenzhen across the Pacific")
    }

    @Test fun ordinary_route_is_not_shifted() =
        assertFalse(assertNotNull(routeBounds(listOf(sf, carlsbad), 2.0)).shifted)

    private fun toRadians(d: Double) = d * kotlin.math.PI / 180.0
}

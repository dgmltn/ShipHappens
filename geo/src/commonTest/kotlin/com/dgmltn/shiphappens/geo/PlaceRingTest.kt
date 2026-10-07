package com.dgmltn.shiphappens.geo

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceRingTest {
    private val pacific = assertNotNull(routeBounds(listOf(LatLng(22.54, 114.06), LatLng(33.94, -118.41)), 2.0))
    private val europe = assertNotNull(routeBounds(listOf(LatLng(52.5, 13.4), LatLng(40.4, -3.7)), 2.0))
    private val tokyoToLa = assertNotNull(routeBounds(listOf(LatLng(35.7, 139.7), LatLng(34.0, -118.2)), 2.0))

    private fun ring(vararg lngLat: Float) = floatArrayOf(*lngLat)

    private fun FloatArray.lngs() = filterIndexed { i, _ -> i % 2 == 0 }

    private fun assertContinuous(ring: FloatArray) {
        val lngs = ring.lngs()
        for (i in 1 until lngs.size) assertTrue(abs(lngs[i] - lngs[i - 1]) <= 180f, "jump at $i")
    }

    private val greenwich = ring(-5f, 50f, 2f, 50f, 2f, 58f, -5f, 58f)

    @Test fun greenwich_ring_under_pacific_view_is_out_of_view() {
        assertTrue(pacific.shifted)
        assertNull(placeRing(greenwich, pacific))
    }

    @Test fun greenwich_ring_under_shifted_view_that_covers_it_stays_contiguous() {
        val shiftedAroundGreenwich = GeoBounds(minLat = 40.0, maxLat = 65.0, minLng = 340.0, maxLng = 370.0, shifted = true)
        val placed = assertNotNull(placeRing(greenwich, shiftedAroundGreenwich))
        assertContinuous(placed)
        assertTrue(placed.lngs().all { it in 350f..365f }, "lngs ${placed.lngs()}")
        assertEquals(355f, placed.lngs().min())
        assertEquals(362f, placed.lngs().max())
    }

    @Test fun greenwich_ring_under_ordinary_view_is_unchanged() {
        assertTrue(!europe.shifted)
        val placed = assertNotNull(placeRing(greenwich, europe))
        assertContentEquals(greenwich, placed)
    }

    @Test fun antimeridian_halves_join_up_around_180() {
        assertTrue(tokyoToLa.shifted)
        val east = assertNotNull(placeRing(ring(170f, 35f, 180f, 35f, 180f, 40f, 170f, 40f), tokyoToLa))
        val west = assertNotNull(placeRing(ring(-180f, 35f, -170f, 35f, -170f, 40f, -180f, 40f), tokyoToLa))
        assertEquals(170f, east.lngs().min())
        assertEquals(180f, east.lngs().max())
        assertEquals(180f, west.lngs().min())
        assertEquals(190f, west.lngs().max())
    }

    @Test fun ring_split_by_the_antimeridian_is_made_continuous() {
        val placed = assertNotNull(placeRing(ring(175f, 36f, -175f, 36f, -175f, 39f, 175f, 39f), tokyoToLa))
        assertContinuous(placed)
        assertEquals(175f, placed.lngs().min())
        assertEquals(185f, placed.lngs().max())
    }

    @Test fun globe_wrapping_ring_is_skipped() {
        assertNull(placeRing(ring(-180f, -80f, -90f, -80f, 0f, -80f, 90f, -80f, 180f, -80f, 180f, -90f, -180f, -90f), europe))
    }

    @Test fun ring_far_outside_the_view_is_skipped() {
        assertNull(placeRing(ring(0f, -60f, 5f, -60f, 5f, -55f, 0f, -55f), europe))
    }
}

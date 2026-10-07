package com.dgmltn.shiphappens.geo

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class BundledGeocoderTest {
    private val bytes = encodePlaces(mapOf("SACRAMENTO, CA" to LatLng(38.5816, -121.4944)))

    @Test fun hit_and_miss() = runTest {
        val g = BundledGeocoder { bytes }
        assertIs<GeoResult.Found>(g.lookup("SACRAMENTO, CA"))
        assertEquals(GeoResult.NotFound, g.lookup("NOWHERE, ZZ"))
    }

    @Test fun loads_once() = runTest {
        var loads = 0
        val g = BundledGeocoder { loads++; bytes }
        g.lookup("SACRAMENTO, CA"); g.lookup("NOWHERE, ZZ")
        assertEquals(1, loads)
    }

    @Test fun broken_asset_answers_not_found_instead_of_crashing() = runTest {
        val g = BundledGeocoder { error("missing resource") }
        assertEquals(GeoResult.NotFound, g.lookup("SACRAMENTO, CA"))
    }

    @Test fun broken_asset_is_only_loaded_once() = runTest {
        var loads = 0
        val g = BundledGeocoder { loads++; error("missing resource") }
        g.lookup("SACRAMENTO, CA"); g.lookup("MEMPHIS, TN")
        assertEquals(1, loads)
    }
}

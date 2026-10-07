package com.dgmltn.shiphappens.geo

import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import kotlin.test.*

/** Pins the real generated assets: the trail from the FedEx QA parcel must resolve offline. */
class GeoAssetsTest {
    @Test fun fedex_qa_trail_resolves_offline() = runTest {
        val g = GeoAssets.bundledGeocoder()
        for (raw in listOf("FOSTER CITY, CA", "SOUTH SAN FRANCISCO CA 94080 US", "Sacramento, CA", "CARLSBAD, CA, US", "Memphis, TN")) {
            val key = assertNotNull(PlaceKey.normalize(raw), raw)
            assertIs<GeoResult.Found>(g.lookup(key), "expected $key in bundled table")
        }
    }

    @Test fun consolidated_and_alternate_spellings_resolve() = runTest {
        val g = GeoAssets.bundledGeocoder()
        for (key in listOf(
            "NASHVILLE, TN", "LOUISVILLE, KY", "LEXINGTON, KY", "INDIANAPOLIS, IN", "BOISE, ID",
            "CANON CITY, CO", "ST LOUIS, MO", "SAINT LOUIS, MO",
        )) {
            assertIs<GeoResult.Found>(g.lookup(key), "expected $key in bundled table")
        }
    }

    @Test fun invented_places_are_absent() = runTest {
        val g = GeoAssets.bundledGeocoder()
        for (key in listOf("FOSTER, CA", "KANSAS, MO", "SALT LAKE, UT")) {
            assertEquals(GeoResult.NotFound, g.lookup(key), "unexpected $key in bundled table")
        }
    }

    @Test fun every_key_is_ascii() = runTest {
        val bytes = GeoAssets.placesBytes()
        val buf = ByteBuffer.wrap(bytes)
        buf.position(4 + 4)
        val count = buf.getInt()
        repeat(count) {
            val key = ByteArray(buf.getShort().toInt() and 0xFFFF).also(buf::get)
            buf.position(buf.position() + 8)
            assertTrue(key.all { it in 0..127 }, "non-ASCII key: ${key.decodeToString()}")
        }
    }

    @Test fun sacramento_is_roughly_right() = runTest {
        val at = (GeoAssets.bundledGeocoder().lookup("SACRAMENTO, CA") as GeoResult.Found).at
        assertEquals(38.57, at.lat, 0.2)
        assertEquals(-121.47, at.lng, 0.2)
    }

    @Test fun big_cities_sit_on_their_downtowns_not_their_census_internal_points() = runTest {
        val g = GeoAssets.bundledGeocoder()
        val sf = (g.lookup("SAN FRANCISCO, CA") as GeoResult.Found).at
        assertEquals(37.7749, sf.lat, 0.1)
        assertEquals(-122.4194, sf.lng, 0.1)
        val anchorage = (g.lookup("ANCHORAGE, AK") as GeoResult.Found).at
        assertEquals(61.2181, anchorage.lat, 0.1)
        assertEquals(-149.9003, anchorage.lng, 0.1)
    }

    @Test fun carson_city_keeps_its_city() = runTest {
        val g = GeoAssets.bundledGeocoder()
        assertIs<GeoResult.Found>(g.lookup("CARSON CITY, NV"))
        assertEquals(GeoResult.NotFound, g.lookup("CARSON, NV"))
    }

    @Test fun common_carrier_spellings_resolve() = runTest {
        val g = GeoAssets.bundledGeocoder()
        for (key in listOf(
            "PORT ST LUCIE, FL", "PORT SAINT LUCIE, FL", "WINSTON SALEM, NC", "BROOKLYN, NY", "BRONX, NY",
            "QUEENS, NY", "STATEN ISLAND, NY", "FLUSHING, NY", "JAMAICA, NY", "LONG ISLAND CITY, NY",
            "CITY OF INDUSTRY, CA", "INDUSTRY, CA",
        )) {
            assertIs<GeoResult.Found>(g.lookup(key), "expected $key in bundled table")
        }
    }

    @Test fun land_outline_loads() = runTest {
        val land = GeoAssets.land()
        assertTrue(land.rings.size > 50, "rings=${land.rings.size}")
    }
}

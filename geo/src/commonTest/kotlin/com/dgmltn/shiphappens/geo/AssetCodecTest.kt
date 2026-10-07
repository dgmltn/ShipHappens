package com.dgmltn.shiphappens.geo

import kotlin.test.*

class AssetCodecTest {
    private val fixture = mapOf(
        "SACRAMENTO, CA" to LatLng(38.5816, -121.4944),
        "CARLSBAD, CA" to LatLng(33.1581, -117.3506),
        "MEMPHIS, TN" to LatLng(35.1495, -90.0490),
    )

    @Test fun places_roundtrip_and_lookup() {
        val t = PlacesTable.decode(encodePlaces(fixture))
        assertEquals(3, t.size)
        val s = assertNotNull(t.lookup("SACRAMENTO, CA"))
        assertEquals(38.5816, s.lat, 1e-4)
        assertEquals(-121.4944, s.lng, 1e-4)
        assertNull(t.lookup("NOWHERE, ZZ"))
        assertNull(t.lookup("A"))
        assertNull(t.lookup("ZZZZ, ZZ"))
    }

    @Test fun places_rejects_bad_magic() {
        val bytes = encodePlaces(fixture).also { it[0] = 'X'.code.toByte() }
        assertFailsWith<IllegalArgumentException> { PlacesTable.decode(bytes) }
    }

    @Test fun places_rejects_truncation() {
        val bytes = encodePlaces(fixture)
        assertFailsWith<IllegalArgumentException> { PlacesTable.decode(bytes.copyOf(bytes.size - 3)) }
    }

    @Test fun land_roundtrip() {
        val o = LandOutline.decode(encodeLand(listOf(listOf(0f to 0f, 10f to 0f, 10f to 10f), listOf(-5f to -5f, -4f to -5f, -4f to -4f))))
        assertEquals(2, o.rings.size)
        assertContentEquals(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f), o.rings[0])
    }
}

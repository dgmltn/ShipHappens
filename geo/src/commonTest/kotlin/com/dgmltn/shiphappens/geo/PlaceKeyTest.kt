package com.dgmltn.shiphappens.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaceKeyTest {
    @Test fun city_state_is_uppercased() = assertEquals("SACRAMENTO, CA", PlaceKey.normalize("Sacramento, CA"))
    @Test fun extra_whitespace_collapses() = assertEquals("FOSTER CITY, CA", PlaceKey.normalize("  Foster   City ,  ca "))
    @Test fun zip_is_stripped() = assertEquals("CARLSBAD, CA", PlaceKey.normalize("CARLSBAD, CA 92008"))
    @Test fun zip_plus_four_is_stripped() = assertEquals("CARLSBAD, CA", PlaceKey.normalize("Carlsbad, CA 92008-1234"))
    @Test fun trailing_us_country_is_stripped() = assertEquals("CARLSBAD, CA", PlaceKey.normalize("CARLSBAD, CA, US"))
    @Test fun trailing_usa_and_united_states_are_stripped() {
        assertEquals("MEMPHIS, TN", PlaceKey.normalize("Memphis, TN, USA"))
        assertEquals("MEMPHIS, TN", PlaceKey.normalize("Memphis, TN, United States"))
    }
    @Test fun missing_comma_before_state_is_inserted() =
        assertEquals("SOUTH SAN FRANCISCO, CA", PlaceKey.normalize("SOUTH SAN FRANCISCO CA 94080 US"))
    @Test fun full_state_name_maps_to_postal_code() =
        assertEquals("MEMPHIS, TN", PlaceKey.normalize("Memphis, Tennessee"))
    @Test fun already_normalized_is_stable() = assertEquals("MEMPHIS, TN", PlaceKey.normalize("MEMPHIS, TN"))
    @Test fun city_ending_in_us_is_not_stripped() = assertEquals("COLUMBUS, OH", PlaceKey.normalize("Columbus, OH"))
    @Test fun comma_less_city_names_ending_in_us_letters_are_kept() {
        assertEquals("CITRUS HEIGHTS, CA", PlaceKey.normalize("Citrus Heights CA"))
        assertEquals("COLUMBUS, OH", PlaceKey.normalize("Columbus OH"))
    }
    @Test fun country_only_location_has_no_place() {
        for (raw in listOf("United States", "US", "USA", "united states of america")) assertNull(PlaceKey.normalize(raw), raw)
    }
    @Test fun foreign_place_keeps_its_country() = assertEquals("SHENZHEN, CN", PlaceKey.normalize("Shenzhen, CN"))
    @Test fun usps_facility_names_reduce_to_their_city() {
        assertEquals("SAN DIEGO, CA", PlaceKey.normalize("SAN DIEGO CA DISTRIBUTION CENTER"))
        assertEquals("LOS ANGELES, CA", PlaceKey.normalize("LOS ANGELES CA DISTRIBUTION CENTER"))
        assertEquals("SANTA CLARITA, CA", PlaceKey.normalize("SANTA CLARITA CA PROCESSING AND DISTRIBUTION CENTER"))
        assertEquals("DENVER, CO", PlaceKey.normalize("DENVER CO NETWORK DISTRIBUTION CENTER"))
        assertEquals("OAKLAND, CA", PlaceKey.normalize("OAKLAND CA P&DC"))
    }
    @Test fun a_compass_word_naming_a_usps_facility_is_dropped() =
        assertEquals("ROCHESTER, NY", PlaceKey.normalize("NORTHWEST ROCHESTER NY DISTRIBUTION CENTER"))
    @Test fun a_compass_word_in_a_real_city_name_is_kept() {
        assertEquals("NORTH LAS VEGAS, NV", PlaceKey.normalize("North Las Vegas, NV"))
        assertEquals("WEST PALM BEACH, FL", PlaceKey.normalize("WEST PALM BEACH FL 33401"))
    }
    @Test fun a_city_with_only_a_country_keeps_just_the_city() = assertEquals("VISTA", PlaceKey.normalize("Vista, US"))
    @Test fun blank_and_null_are_null() {
        assertNull(PlaceKey.normalize(null))
        assertNull(PlaceKey.normalize("   "))
        assertNull(PlaceKey.normalize(", ,"))
    }
}

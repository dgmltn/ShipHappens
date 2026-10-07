package com.dgmltn.shiphappens.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CityChoiceTest {
    private val vistaCa = CityCandidate("VISTA, CA", LatLng(33.19, -117.24))
    private val vistaMo = CityCandidate("VISTA, MO", LatLng(37.98, -93.67))
    private val sanDiegoCa = CityCandidate("SAN DIEGO, CA", LatLng(32.72, -117.16))
    private val sanDiegoTx = CityCandidate("SAN DIEGO, TX", LatLng(27.76, -98.24))
    private val carlsbadCa = CityCandidate("CARLSBAD, CA", LatLng(33.13, -117.28))
    private val carlsbadNm = CityCandidate("CARLSBAD, NM", LatLng(32.42, -104.23))
    private val carlsbadTx = CityCandidate("CARLSBAD, TX", LatLng(31.60, -100.64))
    private val chicago = CityCandidate("CHICAGO, IL", LatLng(41.88, -87.63))

    @Test fun a_city_with_one_candidate_takes_it() {
        val chosen = chooseCities(listOf("CHICAGO"), mapOf("CHICAGO" to listOf(chicago)), known = emptyList())
        assertEquals(mapOf("CHICAGO" to chicago), chosen)
    }

    @Test fun an_ambiguous_city_takes_the_candidate_nearest_the_routes_known_stops() {
        val chosen = chooseCities(
            listOf("CARLSBAD"),
            mapOf("CARLSBAD" to listOf(carlsbadNm, carlsbadCa, carlsbadTx)),
            known = listOf(LatLng(33.22, -117.31)),  // Oceanside, CA
        )
        assertEquals(mapOf("CARLSBAD" to carlsbadCa), chosen)
    }

    @Test fun a_route_of_only_ambiguous_cities_takes_the_shortest_combination() {
        val chosen = chooseCities(
            listOf("VISTA", "SAN DIEGO", "CARLSBAD"),
            mapOf(
                "VISTA" to listOf(vistaMo, vistaCa),
                "SAN DIEGO" to listOf(sanDiegoTx, sanDiegoCa),
                "CARLSBAD" to listOf(carlsbadNm, carlsbadTx, carlsbadCa),
            ),
            known = emptyList(),
        )
        assertEquals(mapOf("VISTA" to vistaCa, "SAN DIEGO" to sanDiegoCa, "CARLSBAD" to carlsbadCa), chosen)
    }

    @Test fun a_lone_ambiguous_city_with_no_context_stays_unchosen() {
        val chosen = chooseCities(listOf("VISTA"), mapOf("VISTA" to listOf(vistaCa, vistaMo)), known = emptyList())
        assertTrue(chosen.isEmpty())
    }

    @Test fun a_lone_ambiguous_city_takes_the_candidate_nearest_home() {
        val home = LatLng(33.13, -117.28)  // Carlsbad, CA
        val chosen = chooseCities(listOf("VISTA"), mapOf("VISTA" to listOf(vistaMo, vistaCa)), known = emptyList(), home = home)
        assertEquals(mapOf("VISTA" to vistaCa), chosen)
    }

    @Test fun a_route_of_only_ambiguous_cities_is_measured_to_home() {
        // The far pair sits closer together than the near pair, so only the leg to home tells them apart.
        val farA = CityCandidate("ALPHA, KS", LatLng(40.0, -100.0))
        val nearA = CityCandidate("ALPHA, CA", LatLng(33.5, -117.5))
        val farB = CityCandidate("BETA, KS", LatLng(40.1, -100.0))
        val nearB = CityCandidate("BETA, CA", LatLng(33.0, -117.0))
        val options = mapOf("ALPHA" to listOf(farA, nearA), "BETA" to listOf(farB, nearB))
        assertEquals(mapOf("ALPHA" to farA, "BETA" to farB), chooseCities(listOf("ALPHA", "BETA"), options, known = emptyList()))
        val home = LatLng(33.13, -117.28)
        assertEquals(mapOf("ALPHA" to nearA, "BETA" to nearB), chooseCities(listOf("ALPHA", "BETA"), options, known = emptyList(), home = home))
    }

    @Test fun a_city_with_no_candidates_stays_unchosen() {
        val chosen = chooseCities(listOf("ATLANTIS"), mapOf("ATLANTIS" to emptyList()), known = emptyList())
        assertTrue(chosen.isEmpty())
    }

    @Test fun an_unambiguous_city_anchors_an_ambiguous_one() {
        val chosen = chooseCities(
            listOf("CHICAGO", "VISTA"),
            mapOf("CHICAGO" to listOf(chicago), "VISTA" to listOf(vistaCa, vistaMo)),
            known = emptyList(),
        )
        assertEquals(vistaMo, chosen["VISTA"])
    }

    @Test fun table_lists_every_state_with_a_city_of_that_name() {
        val table = PlacesTable.decode(encodePlaces(mapOf(
            "VISTA, CA" to vistaCa.at, "VISTA, MO" to vistaMo.at,
            "VISTA HEIGHTS, NY" to LatLng(1.0, 1.0), "VISTAS, TX" to LatLng(2.0, 2.0), "CHICAGO, IL" to chicago.at,
        )))
        assertEquals(listOf("VISTA, CA", "VISTA, MO"), table.candidates("VISTA").map { it.key })
        assertTrue(table.candidates("ATLANTIS").isEmpty())
    }
}

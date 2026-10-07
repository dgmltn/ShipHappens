package com.dgmltn.shiphappens.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One state's place of a given city name, e.g. "VISTA, CA". */
data class CityCandidate(val key: String, val at: LatLng)

/** Every bundled place sharing a bare city name ("VISTA" → CA and MO). */
fun interface CityIndex {
    suspend fun candidates(city: String): List<CityCandidate>
}

/** True for keys that name a city but no state or country, e.g. Amazon's "Vista, US" → "VISTA". */
fun isCityOnly(key: String): Boolean = ", " !in key

/**
 * Picks a state for each city-only stop on one route. A name with one candidate takes it; an
 * ambiguous one takes the candidate nearest the route's [known] stops (and its unambiguous
 * neighbours). With nothing on the route to go by, the ambiguous names take the combination with
 * the shortest path, ending at [home] when it's known. A name that can't be decided that way, or
 * that has no candidates, is left out.
 *
 * @param cities the route's city-only keys, in route order
 * @param home where the user's parcels usually end up
 */
fun chooseCities(
    cities: List<String>,
    candidates: Map<String, List<CityCandidate>>,
    known: List<LatLng>,
    home: LatLng? = null,
): Map<String, CityCandidate> {
    val chosen = mutableMapOf<String, CityCandidate>()
    val ambiguous = ArrayList<String>()
    for (city in cities) {
        val options = candidates[city].orEmpty()
        when (options.size) {
            0 -> Unit
            1 -> chosen[city] = options.single()
            else -> ambiguous += city
        }
    }
    val anchors = known + chosen.values.map { it.at }
    if (anchors.isNotEmpty()) {
        for (city in ambiguous) {
            chosen[city] = candidates.getValue(city).minBy { c -> anchors.minOf { it.distanceKm(c.at) } }
        }
    } else if (ambiguous.isNotEmpty() && (home != null || ambiguous.size >= 2)) {
        chosen += shortestCombination(ambiguous, candidates, end = home)
    }
    return chosen
}

private const val MAX_COMBINATIONS = 4096

private fun shortestCombination(
    cities: List<String>,
    candidates: Map<String, List<CityCandidate>>,
    end: LatLng?,
): Map<String, CityCandidate> {
    val options = cities.map { candidates.getValue(it) }
    val combinations = options.fold(1L) { n, o -> n * o.size }
    val picks = if (combinations <= MAX_COMBINATIONS) bestOfAll(options, end) else greedy(options, end)
    return cities.zip(picks).toMap()
}

private fun bestOfAll(options: List<List<CityCandidate>>, end: LatLng?): List<CityCandidate> {
    var best: List<CityCandidate> = emptyList()
    var bestLength = Double.MAX_VALUE
    fun walk(i: Int, path: List<CityCandidate>, length: Double) {
        if (length >= bestLength) return
        if (i == options.size) {
            val total = length + (end?.let { path.last().at.distanceKm(it) } ?: 0.0)
            if (total < bestLength) {
                best = path
                bestLength = total
            }
            return
        }
        for (c in options[i]) {
            walk(i + 1, path + c, length + (path.lastOrNull()?.at?.distanceKm(c.at) ?: 0.0))
        }
    }
    walk(0, emptyList(), 0.0)
    return best
}

/** Each stop nearest the previous pick; the first takes whichever candidate starts the shortest such chain. */
private fun greedy(options: List<List<CityCandidate>>, end: LatLng?): List<CityCandidate> =
    options.first().map { start ->
        options.drop(1).fold(listOf(start)) { path, next -> path + next.minBy { path.last().at.distanceKm(it.at) } }
    }.minBy { path ->
        path.zipWithNext().sumOf { (a, b) -> a.at.distanceKm(b.at) } + (end?.let { path.last().at.distanceKm(it) } ?: 0.0)
    }

internal fun LatLng.distanceKm(other: LatLng): Double {
    fun rad(d: Double) = d * PI / 180.0
    val dLat = rad(other.lat - lat)
    val dLng = rad(other.lng - lng)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat)) * cos(rad(other.lat)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * 6371.0 * asin(sqrt(h))
}

// geo/src/commonMain/kotlin/com/dgmltn/shiphappens/geo/Route.kt
package com.dgmltn.shiphappens.geo

import com.dgmltn.shiphappens.domain.TrackingEvent

data class RouteStop(
    val key: String,
    val displayName: String,
    val at: LatLng,
    /** Oldest first. */
    val events: List<TrackingEvent>,
)

/**
 * Carriers log several scans per hub (arrived, departed, processed), so consecutive scans at
 * one place are one stop. A later return to the same hub is a separate stop — the route really
 * did double back. Scans with no usable location don't break a stop apart.
 */
fun buildRoute(events: List<TrackingEvent>, coords: Map<String, LatLng>): List<RouteStop> {
    val stops = ArrayList<RouteStop>()
    for (e in events.sortedBy { it.timestamp }) {
        val key = PlaceKey.normalize(e.location) ?: continue
        val at = coords[key] ?: continue
        val last = stops.lastOrNull()
        if (last != null && last.key == key) {
            stops[stops.lastIndex] = last.copy(events = last.events + e)
        } else {
            val location = e.location
            stops += RouteStop(key, displayPlace(location!!), at, listOf(e))
        }
    }
    return stops
}

fun displayPlace(raw: String): String {
    val key = PlaceKey.normalize(raw) ?: return raw.trim()
    return key.split(", ").joinToString(", ") { part ->
        if (part.length == 2 && part.all(Char::isLetter)) part
        else part.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar(Char::titlecase) }
    }
}

package com.dgmltn.shiphappens.ui.detail

import androidx.compose.ui.geometry.Offset

internal fun nearestStop(points: List<Offset>, tap: Offset, radiusPx: Float): Int? {
    var best: Int? = null
    var bestD = radiusPx * radiusPx
    for (i in points.indices) {
        val d = (points[i] - tap).getDistanceSquared()
        if (d <= bestD) {
            best = i
            bestD = d
        }
    }
    return best
}

internal fun nextSelection(current: Int?, hit: Int?): Int? = if (hit == null || hit == current) null else hit

/** A selection index only means something for the stop list it was made against. */
internal fun selectionFor(previousStops: List<StopUi>, previous: Int?, stops: List<StopUi>): Int? =
    if (stops == previousStops && previous != null && previous in stops.indices) previous else null

internal fun calloutLines(stop: StopUi, max: Int = 4): List<String> {
    val lines = stop.events.take(max).map { "${it.whenText} · ${it.description}" }
    val more = stop.events.size - max
    return if (more > 0) lines + "+$more more" else lines
}

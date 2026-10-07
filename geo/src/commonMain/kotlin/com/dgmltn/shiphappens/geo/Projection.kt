package com.dgmltn.shiphappens.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round

data class GeoBounds(
    val minLat: Double, val maxLat: Double,
    val minLng: Double, val maxLng: Double,
    /** Longitudes west of Greenwich were moved +360 so a Pacific crossing stays contiguous. */
    val shifted: Boolean,
) {
    val midLat: Double get() = (minLat + maxLat) / 2
}

fun GeoBounds.adjustLng(lng: Double): Double = if (shifted && lng < 0) lng + 360 else lng

private fun lngScale(midLat: Double) = cos(midLat * PI / 180.0).coerceAtLeast(0.2)

fun routeBounds(points: List<LatLng>, aspect: Double, padFraction: Double = 0.15, minSpanDeg: Double = 3.0): GeoBounds? {
    if (points.isEmpty()) return null
    val rawMin = points.minOf { it.lng }
    val rawMax = points.maxOf { it.lng }
    val shifted = rawMax - rawMin > 180.0
    val lngs = points.map { if (shifted && it.lng < 0) it.lng + 360 else it.lng }

    var minLat = points.minOf { it.lat }; var maxLat = points.maxOf { it.lat }
    var minLng = lngs.min(); var maxLng = lngs.max()

    fun widen(lo: Double, hi: Double, span: Double): Pair<Double, Double> {
        val mid = (lo + hi) / 2
        return (mid - span / 2) to (mid + span / 2)
    }

    val padLat = (maxLat - minLat) * padFraction
    val padLng = (maxLng - minLng) * padFraction
    minLat -= padLat; maxLat += padLat; minLng -= padLng; maxLng += padLng
    if (maxLat - minLat < minSpanDeg) widen(minLat, maxLat, minSpanDeg).let { minLat = it.first; maxLat = it.second }
    if (maxLng - minLng < minSpanDeg) widen(minLng, maxLng, minSpanDeg).let { minLng = it.first; maxLng = it.second }

    // Match the drawing area's aspect in scaled space so the map isn't stretched.
    val k = lngScale((minLat + maxLat) / 2)
    val scaledW = (maxLng - minLng) * k
    val h = maxLat - minLat
    if (scaledW / h < aspect) {
        widen(minLng, maxLng, aspect * h / k).let { minLng = it.first; maxLng = it.second }
    } else {
        widen(minLat, maxLat, scaledW / aspect).let { minLat = it.first; maxLat = it.second }
    }
    return GeoBounds(minLat, maxLat, minLng, maxLng, shifted)
}

class Projector(private val bounds: GeoBounds, private val width: Float, private val height: Float) {
    fun x(lng: Double): Float =
        ((bounds.adjustLng(lng) - bounds.minLng) / (bounds.maxLng - bounds.minLng) * width).toFloat()

    /** Projects a longitude already in the bounds' coordinate space, e.g. from [placeRing]. */
    fun xPlaced(lng: Double): Float =
        ((lng - bounds.minLng) / (bounds.maxLng - bounds.minLng) * width).toFloat()

    fun y(lat: Double): Float =
        ((bounds.maxLat - lat) / (bounds.maxLat - bounds.minLat) * height).toFloat()
}

private const val GlobeSpan = 359.0

/**
 * Positions a land ring (interleaved lng, lat) as one piece in the bounds' longitude space, so rings
 * crossing Greenwich or the antimeridian stay contiguous. Returns null for rings that wrap the globe
 * or miss the view.
 */
fun placeRing(ring: FloatArray, bounds: GeoBounds): FloatArray? {
    if (ring.size < 6 || ring.size % 2 != 0) return null
    val out = FloatArray(ring.size)
    var minLng = Double.MAX_VALUE
    var maxLng = -Double.MAX_VALUE
    var minLat = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var prev = ring[0].toDouble()
    var lng = prev
    var i = 0
    while (i < ring.size) {
        val raw = ring[i].toDouble()
        lng += raw - prev - 360.0 * round((raw - prev) / 360.0)
        prev = raw
        val lat = ring[i + 1].toDouble()
        out[i] = lng.toFloat()
        out[i + 1] = ring[i + 1]
        if (lng < minLng) minLng = lng
        if (lng > maxLng) maxLng = lng
        if (lat < minLat) minLat = lat
        if (lat > maxLat) maxLat = lat
        i += 2
    }
    if (maxLng - minLng >= GlobeSpan) return null

    val viewCenter = (bounds.minLng + bounds.maxLng) / 2
    val k = round((viewCenter - (minLng + maxLng) / 2) / 360.0)
    val offset = 360.0 * k
    if (maxLng + offset < bounds.minLng || minLng + offset > bounds.maxLng) return null
    if (maxLat < bounds.minLat || minLat > bounds.maxLat) return null
    if (offset != 0.0) {
        i = 0
        while (i < out.size) {
            out[i] = (out[i] + offset).toFloat()
            i += 2
        }
    }
    return out
}

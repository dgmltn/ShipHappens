package com.dgmltn.shiphappens.geo

data class LatLng(val lat: Double, val lng: Double) {
    val isValid: Boolean
        get() = lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0
}

sealed interface GeoResult {
    data class Found(val at: LatLng) : GeoResult

    /** The geocoder answered and has no such place. Safe to remember. */
    data object NotFound : GeoResult

    /** The geocoder couldn't answer (offline, throttled, absent). Worth asking again later. */
    data object Unavailable : GeoResult
}

/** Looks up a key already produced by [PlaceKey.normalize]. */
fun interface Geocoder {
    suspend fun lookup(key: String): GeoResult
}

object UnavailableGeocoder : Geocoder {
    override suspend fun lookup(key: String): GeoResult = GeoResult.Unavailable
}

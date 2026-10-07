package com.dgmltn.shiphappens.geo

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLPlacemark
import platform.CoreLocation.kCLErrorDomain
import platform.CoreLocation.kCLErrorGeocodeFoundNoResult
import kotlin.coroutines.resume

/** Apple throttles per-app geocoding, so callers must serialize lookups. */
@OptIn(ExperimentalForeignApi::class)
class IosGeocoder : Geocoder {
    override suspend fun lookup(key: String): GeoResult = suspendCancellableCoroutine { cont ->
        if (!cont.isActive) return@suspendCancellableCoroutine
        val geocoder = CLGeocoder()
        cont.invokeOnCancellation { geocoder.cancelGeocode() }
        geocoder.geocodeAddressString(key) { placemarks, error ->
            val result = when {
                error == null -> firstCoordinate(placemarks)
                error.domain == kCLErrorDomain && error.code == kCLErrorGeocodeFoundNoResult -> GeoResult.NotFound
                else -> GeoResult.Unavailable
            }
            if (cont.isActive) cont.resume(result)
        }
    }

    private fun firstCoordinate(placemarks: List<*>?): GeoResult {
        val placemark = placemarks?.firstOrNull() as? CLPlacemark ?: return GeoResult.NotFound
        val coordinate = placemark.location?.coordinate ?: return GeoResult.NotFound
        return coordinate.useContents { GeoResult.Found(LatLng(latitude, longitude)) }
    }
}

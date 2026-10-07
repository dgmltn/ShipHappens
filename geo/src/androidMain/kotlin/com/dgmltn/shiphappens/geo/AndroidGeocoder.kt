package com.dgmltn.shiphappens.geo

import android.content.Context
import android.location.Address
import android.os.Build
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.location.Geocoder as SystemGeocoder

class AndroidGeocoder(context: Context) : Geocoder {
    private val system = SystemGeocoder(context.applicationContext, Locale.US)

    override suspend fun lookup(key: String): GeoResult {
        if (!SystemGeocoder.isPresent()) return GeoResult.Unavailable
        return try {
            val first = if (Build.VERSION.SDK_INT >= 33) queryAsync(key) else queryBlocking(key)
            if (first == null || !first.hasLatitude() || !first.hasLongitude()) {
                GeoResult.NotFound
            } else {
                GeoResult.Found(LatLng(first.latitude, first.longitude))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Offline, or the backing service is down; worth retrying later.
            GeoResult.Unavailable
        } catch (e: IllegalArgumentException) {
            GeoResult.NotFound
        } catch (e: RuntimeException) {
            // OEM geocoder backends throw arbitrary runtime failures; a lookup must never crash the caller.
            Logger.withTag("geo").w(e) { "Platform geocoder failed for $key" }
            GeoResult.Unavailable
        }
    }

    private suspend fun queryAsync(key: String): Address? = suspendCancellableCoroutine { cont ->
        system.getFromLocationName(
            key,
            1,
            object : SystemGeocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    if (cont.isActive) cont.resume(addresses.firstOrNull())
                }

                override fun onError(errorMessage: String?) {
                    if (cont.isActive) cont.resumeWithException(IOException(errorMessage))
                }
            },
        )
    }

    @Suppress("DEPRECATION")
    private suspend fun queryBlocking(key: String): Address? = withContext(Dispatchers.IO) {
        system.getFromLocationName(key, 1)?.firstOrNull()
    }
}

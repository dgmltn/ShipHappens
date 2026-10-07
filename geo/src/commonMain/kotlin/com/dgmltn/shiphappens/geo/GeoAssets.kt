package com.dgmltn.shiphappens.geo

import co.touchlab.kermit.Logger
import com.dgmltn.shiphappens.geo.res.Res
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.ExperimentalResourceApi

@OptIn(ExperimentalResourceApi::class)
object GeoAssets {
    private val landMutex = Mutex()
    private var land: LandOutline? = null

    suspend fun placesBytes(): ByteArray = Res.readBytes("files/places.bin")

    fun bundledGeocoder(): BundledGeocoder = BundledGeocoder(::placesBytes)

    /** A missing outline just means a plain background behind the route. */
    suspend fun land(): LandOutline = landMutex.withLock {
        land ?: loadLand().also { land = it }
    }

    private suspend fun loadLand(): LandOutline = try {
        LandOutline.decode(Res.readBytes("files/land110m.bin"))
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Logger.withTag("geo").w(e) { "bundled land outline failed to load; maps will have a plain background" }
        LandOutline.EMPTY
    }
}

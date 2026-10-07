package com.dgmltn.shiphappens.geo

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The table is large, so it's read on first use rather than at startup. A broken or missing
 * asset degrades to "knows nothing" — every place then falls through to the platform geocoder
 * instead of taking the detail screen down.
 */
class BundledGeocoder(private val load: suspend () -> ByteArray) : Geocoder, CityIndex {
    private val mutex = Mutex()
    private var table: PlacesTable? = null

    override suspend fun lookup(key: String): GeoResult =
        table().lookup(key)?.let { GeoResult.Found(it) } ?: GeoResult.NotFound

    override suspend fun candidates(city: String): List<CityCandidate> = table().candidates(city)

    private suspend fun table(): PlacesTable = mutex.withLock { table ?: loadTable().also { table = it } }

    private suspend fun loadTable(): PlacesTable = try {
        withContext(Dispatchers.Default) { PlacesTable.decode(load()) }
    } catch (e: Throwable) {
        if (e is CancellationException) throw e
        Logger.withTag("geo").w(e) { "bundled places table failed to load; offline geocoding disabled" }
        PlacesTable.EMPTY
    }
}

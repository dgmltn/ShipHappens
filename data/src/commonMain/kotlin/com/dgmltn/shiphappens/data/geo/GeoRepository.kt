package com.dgmltn.shiphappens.data.geo

import co.touchlab.kermit.Logger
import com.dgmltn.shiphappens.data.AppClock
import com.dgmltn.shiphappens.data.db.GeoCacheEntity
import com.dgmltn.shiphappens.data.db.GeoDao
import com.dgmltn.shiphappens.geo.GeoResult
import com.dgmltn.shiphappens.geo.Geocoder
import com.dgmltn.shiphappens.geo.LatLng
import com.dgmltn.shiphappens.geo.PlaceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Coordinates for carrier place names, remembered across parcels (hubs repeat constantly).
 * Resolution happens only while someone collects [observe] — i.e. while a detail screen is
 * open — so background refresh never spends geocoder quota.
 */
class GeoRepository(
    private val dao: GeoDao,
    private val bundled: Geocoder,
    private val platform: Geocoder,
    private val clock: AppClock,
    private val platformTimeout: Duration = 15.seconds,
) {
    fun observe(places: List<String>): Flow<Map<String, LatLng>> {
        val keys = places.mapNotNull(PlaceKey::normalize).distinct()
        if (keys.isEmpty()) return flowOf(emptyMap())
        return channelFlow {
            launch { resolveKeysBestEffort(keys) }
            dao.observe(keys)
                .map { rows ->
                    val found = mutableMapOf<String, LatLng>()
                    for (r in rows) {
                        val lat = r.lat
                        val lng = r.lng
                        if (lat != null && lng != null) found[r.key] = LatLng(lat, lng)
                    }
                    found.toMap()
                }
                .distinctUntilChanged()
                .collect { send(it) }
        }
    }

    /** Unlike [observe], this lets failures propagate so tests can see them. */
    internal suspend fun resolve(places: List<String>) = resolveKeys(places.mapNotNull(PlaceKey::normalize).distinct())

    /** Geocoding is best-effort: a failure must not end the flow of already-cached coordinates. */
    private suspend fun resolveKeysBestEffort(keys: List<String>) {
        try {
            resolveKeys(keys)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.withTag("geo").w(e) { "Place resolution failed" }
        }
    }

    private suspend fun resolveKeys(keys: List<String>) {
        val now = clock.now()
        val cached = dao.get(keys).associateBy { it.key }
        val unresolved = ArrayList<String>()
        val bundledHits = ArrayList<GeoCacheEntity>()
        for (k in keys) {
            val row = cached[k]
            val stale = row == null || (row.lat == null && now - Instant.fromEpochMilliseconds(row.resolvedAt) > MISS_TTL)
            if (!stale) continue
            val local = lookupOrUnavailable { bundled.lookup(k) }
            if (local is GeoResult.Found && local.at.isValid) bundledHits += hit(k, local.at, now) else unresolved += k
        }
        // One write so the map shows the whole bundled route at once instead of growing a stop at a time.
        if (bundledHits.isNotEmpty()) dao.upsertAll(bundledHits)
        var platformBudget = MAX_PLATFORM_LOOKUPS
        for (k in unresolved) {
            if (platformBudget == 0) break  // the rest resolve next time the screen opens
            platformBudget--
            val result = lookupOrUnavailable { withTimeoutOrNull(platformTimeout) { platform.lookup(k) } ?: GeoResult.Unavailable }
            when (val r = result) {
                is GeoResult.Found -> dao.upsert(if (r.at.isValid) hit(k, r.at, now) else miss(k, now))
                GeoResult.NotFound -> dao.upsert(miss(k, now))
                GeoResult.Unavailable -> Unit  // offline or throttled: ask again next time
            }
        }
    }

    /** One misbehaving lookup must not cost the rest of the batch their answers. */
    private suspend fun lookupOrUnavailable(lookup: suspend () -> GeoResult): GeoResult =
        try {
            lookup()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.withTag("geo").w(e) { "Lookup failed for a place" }
            GeoResult.Unavailable
        }

    private fun hit(key: String, at: LatLng, now: Instant) = GeoCacheEntity(key, at.lat, at.lng, now.toEpochMilliseconds())

    private fun miss(key: String, now: Instant) = GeoCacheEntity(key, null, null, now.toEpochMilliseconds())

    private companion object {
        val MISS_TTL = 30.days
        const val MAX_PLATFORM_LOOKUPS = 10
    }
}

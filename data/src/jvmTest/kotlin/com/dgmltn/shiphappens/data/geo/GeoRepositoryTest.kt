package com.dgmltn.shiphappens.data.geo

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.dgmltn.shiphappens.data.AppClock
import com.dgmltn.shiphappens.data.db.GeoCacheEntity
import com.dgmltn.shiphappens.data.db.ShipHappensDb
import com.dgmltn.shiphappens.geo.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDate
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.test.*

class GeoRepositoryTest {
    private class Clock(var at: Instant = Instant.fromEpochMilliseconds(100.days.inWholeMilliseconds)) : AppClock {
        override fun now() = at
        override fun today() = LocalDate(2026, 10, 7)
    }

    private class FakeGeocoder(val answers: Map<String, GeoResult>) : Geocoder {
        val calls = mutableListOf<String>()
        override suspend fun lookup(key: String): GeoResult { calls += key; return answers[key] ?: GeoResult.NotFound }
    }

    private val sac = LatLng(38.58, -121.49)
    private lateinit var db: ShipHappensDb

    @BeforeTest fun setUp() { db = Room.inMemoryDatabaseBuilder<ShipHappensDb>().setDriver(BundledSQLiteDriver()).build() }
    @AfterTest fun tearDown() { db.close() }

    private suspend fun <T> real(block: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }

    private val bundledSac = Geocoder { k -> if (k == "SACRAMENTO, CA") GeoResult.Found(sac) else GeoResult.NotFound }
    private val bundledNone = Geocoder { GeoResult.NotFound }

    @Test fun resolves_and_emits_normalized_keys() = runTest {
        val repo = GeoRepository(db.geoDao(), bundledSac, FakeGeocoder(emptyMap()), Clock())
        val m = real { repo.observe(listOf("Sacramento, CA 95826")).first { it.isNotEmpty() } }
        assertEquals(sac, m["SACRAMENTO, CA"])
    }

    @Test fun bundled_hit_skips_platform() = runTest {
        val platform = FakeGeocoder(emptyMap())
        real { GeoRepository(db.geoDao(), bundledSac, platform, Clock()).resolve(listOf("Sacramento, CA")) }
        assertTrue(platform.calls.isEmpty())
        assertNotNull(db.geoDao().get(listOf("SACRAMENTO, CA")).single().lat)
    }

    @Test fun platform_answers_bundled_misses() = runTest {
        val platform = FakeGeocoder(mapOf("SHENZHEN, CN" to GeoResult.Found(LatLng(22.54, 114.06))))
        real { GeoRepository(db.geoDao(), bundledNone, platform, Clock()).resolve(listOf("Shenzhen, CN")) }
        assertEquals(22.54, db.geoDao().get(listOf("SHENZHEN, CN")).single().lat!!, 1e-6)
    }

    @Test fun invalid_platform_coordinates_are_a_miss() = runTest {
        val platform = FakeGeocoder(mapOf("ATLANTIS, ZZ" to GeoResult.Found(LatLng(Double.NaN, 0.0))))
        real { GeoRepository(db.geoDao(), bundledNone, platform, Clock()).resolve(listOf("Atlantis, ZZ")) }
        assertNull(db.geoDao().get(listOf("ATLANTIS, ZZ")).single().lat)
    }

    @Test fun cached_hit_emits_without_lookup() = runTest {
        db.geoDao().upsert(GeoCacheEntity("SACRAMENTO, CA", sac.lat, sac.lng, 0))
        val platform = FakeGeocoder(emptyMap())
        val m = real { GeoRepository(db.geoDao(), bundledNone, platform, Clock()).observe(listOf("Sacramento, CA")).first { it.isNotEmpty() } }
        assertEquals(sac, m["SACRAMENTO, CA"])
        assertTrue(platform.calls.isEmpty())
    }

    @Test fun miss_is_cached_and_not_retried_within_30_days() = runTest {
        val clock = Clock()
        val platform = FakeGeocoder(emptyMap())
        val repo = GeoRepository(db.geoDao(), bundledNone, platform, clock)
        real { repo.resolve(listOf("Atlantis, ZZ")) }
        clock.at += 29.days
        real { repo.resolve(listOf("Atlantis, ZZ")) }
        assertEquals(1, platform.calls.size)
        assertNull(db.geoDao().get(listOf("ATLANTIS, ZZ")).single().lat)
    }

    @Test fun miss_is_retried_after_30_days() = runTest {
        val clock = Clock()
        val platform = FakeGeocoder(emptyMap())
        val repo = GeoRepository(db.geoDao(), bundledNone, platform, clock)
        real { repo.resolve(listOf("Atlantis, ZZ")) }
        clock.at += 31.days
        real { repo.resolve(listOf("Atlantis, ZZ")) }
        assertEquals(2, platform.calls.size)
    }

    @Test fun unavailable_is_not_cached() = runTest {
        val platform = FakeGeocoder(mapOf("ATLANTIS, ZZ" to GeoResult.Unavailable))
        real { GeoRepository(db.geoDao(), bundledNone, platform, Clock()).resolve(listOf("Atlantis, ZZ")) }
        assertTrue(db.geoDao().get(listOf("ATLANTIS, ZZ")).isEmpty())
    }

    @Test fun platform_lookups_are_capped_at_ten_but_bundled_hits_are_free() = runTest {
        val platform = FakeGeocoder(emptyMap())
        val places = listOf("Sacramento, CA") + (1..15).map { "Town$it, CA" }
        real { GeoRepository(db.geoDao(), bundledSac, platform, Clock()).resolve(places) }
        assertEquals(10, platform.calls.size)
        assertNotNull(db.geoDao().get(listOf("SACRAMENTO, CA")).single().lat)
    }

    @Test fun resolution_failure_does_not_end_observed_flow() = runTest {
        db.geoDao().upsert(GeoCacheEntity("SACRAMENTO, CA", sac.lat, sac.lng, 0))
        val threw = CompletableDeferred<Unit>()
        val throwing = Geocoder { threw.complete(Unit); error("boom") }
        val emissions = Channel<Map<String, LatLng>>(Channel.UNLIMITED)
        real {
            val job = launch {
                GeoRepository(db.geoDao(), bundledNone, throwing, Clock())
                    .observe(listOf("Sacramento, CA", "Atlantis, ZZ")).collect { emissions.send(it) }
            }
            threw.await()
            delay(200)
            db.geoDao().upsert(GeoCacheEntity("ATLANTIS, ZZ", 1.0, 2.0, 0))
            var latest = emptyMap<String, LatLng>()
            while ("ATLANTIS, ZZ" !in latest) latest = emissions.receive()
            job.cancel()
        }
    }

    @Test fun one_throwing_key_does_not_skip_the_rest_of_the_batch() = runTest {
        val platform = Geocoder { k -> if (k == "ATLANTIS, ZZ") error("boom") else GeoResult.Found(LatLng(22.54, 114.06)) }
        real { GeoRepository(db.geoDao(), bundledNone, platform, Clock()).resolve(listOf("Atlantis, ZZ", "Shenzhen, CN")) }
        assertEquals(22.54, db.geoDao().get(listOf("SHENZHEN, CN")).single().lat!!, 1e-6)
    }

    @Test fun blank_and_duplicate_places_are_looked_up_once() = runTest {
        val platform = FakeGeocoder(emptyMap())
        real { GeoRepository(db.geoDao(), bundledNone, platform, Clock()).resolve(listOf("Atlantis, ZZ", "ATLANTIS, ZZ", "  ", "Atlantis, ZZ 12345")) }
        assertEquals(listOf("ATLANTIS, ZZ"), platform.calls)
    }

    @Test fun a_lookup_that_never_returns_times_out_without_blocking_later_keys() = runTest {
        val platform = Geocoder { k -> if (k == "ATLANTIS, ZZ") awaitCancellation() else GeoResult.Found(LatLng(22.54, 114.06)) }
        real {
            GeoRepository(db.geoDao(), bundledNone, platform, Clock(), platformTimeout = 100.milliseconds)
                .resolve(listOf("Atlantis, ZZ", "Shenzhen, CN"))
        }
        assertTrue(db.geoDao().get(listOf("ATLANTIS, ZZ")).isEmpty())
        assertEquals(22.54, db.geoDao().get(listOf("SHENZHEN, CN")).single().lat!!, 1e-6)
    }

    @Test fun cancelling_the_collector_stops_further_platform_lookups() = runTest {
        val calls = mutableListOf<String>()
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val platform = Geocoder { k -> calls += k; started.complete(Unit); gate.await(); GeoResult.NotFound }
        real {
            val job = launch {
                GeoRepository(db.geoDao(), bundledNone, platform, Clock())
                    .observe(listOf("Atlantis, ZZ", "Lemuria, ZZ", "Mu, ZZ")).collect { }
            }
            started.await()
            job.cancelAndJoin()
            gate.complete(Unit)
            delay(200)
        }
        assertEquals(1, calls.size)
    }
}

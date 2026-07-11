package com.shiphappens.source.trackingmore

import com.shiphappens.domain.TrackingStatus
import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.api.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.*

private class FixedConfig(private val cfg: SourceConfig) : SourceConfigProvider {
    override suspend fun current(sourceId: String) = cfg
}

private val WITH_KEY = FixedConfig(SourceConfig(enabled = true, values = mapOf("apiKey" to "tm-key")))

private const val TRANSIT_FIXTURE = """
{"meta":{"code":200,"message":"Request response is successful"},
 "data":[{"tracking_number":"9400111899223300112",
          "courier_code":"usps",
          "delivery_status":"transit",
          "expected_delivery":"2026-07-14",
          "latest_checkpoint_time":"2026-07-10T08:12:00-05:00",
          "origin_info":{"trackinfo":[
            {"checkpoint_date":"2026-07-10T08:12:00-05:00","tracking_detail":"In transit to next facility","location":"Des Moines, IA","checkpoint_delivery_status":"transit"},
            {"checkpoint_date":"2026-07-09T18:03:00-05:00","tracking_detail":"Accepted at USPS origin facility","location":"Seattle, WA","checkpoint_delivery_status":"pending"}]}}]}
"""

private const val EMPTY_FIXTURE = """{"meta":{"code":200,"message":"ok"},"data":[]}"""
private const val CREATED_FIXTURE = """{"meta":{"code":200,"message":"ok"},"data":{"tracking_number":"1Z999AA10123456784","courier_code":"ups"}}"""

class TrackingMoreSourceTest {
    private fun source(handler: MockRequestHandler): TrackingMoreSource =
        TrackingMoreSource(MockEngine(handler), WITH_KEY)

    @Test fun maps_transit_response_to_snapshot() = runTest {
        val src = source { request ->
            assertEquals("tm-key", request.headers["Tracking-Api-Key"])
            respond(TRANSIT_FIXTURE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val r = src.track("9400111899223300112", WellKnownCarriers.USPS)
        assertIs<SourceResult.Success<*>>(r)
        val snap = (r as SourceResult.Success).value
        assertEquals(TrackingStatus.IN_TRANSIT, snap.status)
        assertEquals(LocalDate(2026, 7, 14), snap.etaDate)
        assertEquals(2, snap.events.size)
        assertEquals("Des Moines, IA", snap.latestLocation)
        assertEquals("Accepted at USPS origin facility", snap.events.first().description) // sorted oldest first
    }

    @Test fun empty_get_creates_tracking_then_returns_unknown() = runTest {
        var created = false
        val src = source { request ->
            when {
                request.url.encodedPath.endsWith("/v4/trackings/get") ->
                    respond(EMPTY_FIXTURE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.encodedPath.endsWith("/v4/trackings/create") -> {
                    created = true
                    respond(CREATED_FIXTURE, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                else -> respondError(HttpStatusCode.NotFound)
            }
        }
        val r = src.track("1Z999AA10123456784", null)
        assertTrue(created)
        assertIs<SourceResult.Success<*>>(r)
        assertEquals(TrackingStatus.UNKNOWN, (r as SourceResult.Success).value.status)
    }

    @Test fun http_401_maps_to_auth_and_429_to_rate_limited() = runTest {
        val auth = source { respondError(HttpStatusCode.Unauthorized) }.track("9400111899223300112", null)
        assertEquals(FailureReason.AUTH, (auth as SourceResult.Failure).reason)
        val rate = source { respondError(HttpStatusCode.TooManyRequests) }.track("9400111899223300112", null)
        assertEquals(FailureReason.RATE_LIMITED, (rate as SourceResult.Failure).reason)
    }

    @Test fun missing_key_fails_fast_without_http() = runTest {
        val src = TrackingMoreSource(
            MockEngine { fail("no HTTP call expected") },
            FixedConfig(SourceConfig(enabled = true)),
        )
        val r = src.track("9400111899223300112", null)
        assertEquals(FailureReason.AUTH, (r as SourceResult.Failure).reason)
    }

    @Test fun testConnection_ok_and_auth_failure() = runTest {
        val ok = source { respond("""{"meta":{"code":200},"data":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        assertIs<SourceResult.Success<Unit>>(ok.testConnection(SourceConfig(enabled = true, values = mapOf("apiKey" to "k"))))
        val bad = source { respondError(HttpStatusCode.Unauthorized) }
        val r = bad.testConnection(SourceConfig(enabled = true, values = mapOf("apiKey" to "wrong")))
        assertEquals(FailureReason.AUTH, (r as SourceResult.Failure).reason)
    }
}

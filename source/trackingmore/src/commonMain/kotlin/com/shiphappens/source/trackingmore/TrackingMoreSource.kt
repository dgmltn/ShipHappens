package com.shiphappens.source.trackingmore

import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json

private fun mapStatus(s: String?): TrackingStatus = when (s?.lowercase()) {
    "pending", "inforeceived" -> TrackingStatus.LABEL_CREATED
    "transit" -> TrackingStatus.IN_TRANSIT
    "pickup" -> TrackingStatus.OUT_FOR_DELIVERY
    "delivered" -> TrackingStatus.DELIVERED
    "undelivered", "exception" -> TrackingStatus.EXCEPTION
    else -> TrackingStatus.UNKNOWN
}

class TrackingMoreSource(
    engine: HttpClientEngine,
    private val configProvider: SourceConfigProvider,
    private val baseUrl: String = "https://api.trackingmore.com",
) : TrackingSource {

    override val descriptor = SourceDescriptor(
        id = "trackingmore", displayName = "TrackingMore", kind = SourceKind.UNIVERSAL,
        accentColorHex = "#0F766E",
        configSpec = listOf(ConfigField("apiKey", "API key", "Paste your TrackingMore API key", isSecret = true)),
    )

    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
        expectSuccess = false
    }

    override fun detectCarrier(trackingNumber: String): Carrier? = null  // server-side auto-detect via create

    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val key = apiKey() ?: return SourceResult.Failure(FailureReason.AUTH, "Enter your TrackingMore API key first")
        return runCatching<SourceResult<TrackingSnapshot>> {
            val resp = client.get("$baseUrl/v4/trackings/get") {
                header("Tracking-Api-Key", key)
                parameter("tracking_numbers", normalizeTracking(trackingNumber))
            }
            httpFailure(resp.status)?.let { return it }
            val body: TmGetResponse = resp.body()
            if (body.meta.code == 401) return SourceResult.Failure(FailureReason.AUTH, body.meta.message)
            val item = body.data.firstOrNull() ?: return createTracking(key, trackingNumber, carrier)
            SourceResult.Success(item.toSnapshot())
        }.getOrElse { SourceResult.Failure(FailureReason.NETWORK, it.message) }
    }

    private suspend fun createTracking(key: String, trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val resp = client.post("$baseUrl/v4/trackings/create") {
            header("Tracking-Api-Key", key)
            contentType(ContentType.Application.Json)
            setBody(TmCreateRequest(normalizeTracking(trackingNumber), carrier?.code))
        }
        httpFailure(resp.status)?.let { return it }
        val body: TmCreateResponse = resp.body()
        if (body.meta.code == 401) return SourceResult.Failure(FailureReason.AUTH, body.meta.message)
        // Registered; carrier data arrives on the next refresh.
        return SourceResult.Success(TrackingSnapshot(TrackingStatus.UNKNOWN))
    }

    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> {
        val key = config["apiKey"] ?: return SourceResult.Failure(FailureReason.AUTH, "Enter your TrackingMore API key first")
        return runCatching<SourceResult<Unit>> {
            val resp = client.get("$baseUrl/v4/couriers/all") { header("Tracking-Api-Key", key) }
            httpFailure(resp.status)?.let { return it }
            SourceResult.Success(Unit)
        }.getOrElse { SourceResult.Failure(FailureReason.NETWORK, it.message) }
    }

    private suspend fun apiKey(): String? = configProvider.current(descriptor.id)["apiKey"]

    private fun httpFailure(status: HttpStatusCode): SourceResult.Failure? = when {
        status.isSuccess() -> null
        status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden ->
            SourceResult.Failure(FailureReason.AUTH, "TrackingMore rejected the API key")
        status == HttpStatusCode.TooManyRequests -> SourceResult.Failure(FailureReason.RATE_LIMITED, "Rate limited")
        status == HttpStatusCode.NotFound -> SourceResult.Failure(FailureReason.NOT_FOUND, "Not found")
        else -> SourceResult.Failure(FailureReason.UNKNOWN, "HTTP ${status.value}")
    }
}

private fun TmTracking.toSnapshot(): TrackingSnapshot {
    val checkpoints = (originInfo?.trackInfo.orEmpty() + destinationInfo?.trackInfo.orEmpty())
    val events = checkpoints.mapNotNull { cp ->
        val ts = cp.checkpointDate?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@mapNotNull null
        TrackingEvent(ts, cp.trackingDetail ?: "Update", cp.location, mapStatus(cp.checkpointDeliveryStatus))
    }.sortedBy { it.timestamp }
    return TrackingSnapshot(
        status = mapStatus(deliveryStatus),
        events = events,
        etaDate = expectedDelivery?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        etaTime = expectedDelivery?.drop(11)?.take(8)?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalTime.parse(it) }.getOrNull() },
        latestLocation = events.lastOrNull()?.location,
    )
}

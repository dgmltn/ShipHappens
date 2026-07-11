package com.shiphappens.source.trackingmore

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class TmMeta(val code: Int = 0, val message: String? = null)

@Serializable data class TmGetResponse(val meta: TmMeta = TmMeta(), val data: List<TmTracking> = emptyList())

// create returns `data` as an object; we only care about meta, so keep it loose.
@Serializable data class TmCreateResponse(val meta: TmMeta = TmMeta(), val data: JsonElement? = null)

@Serializable data class TmTracking(
    @SerialName("tracking_number") val trackingNumber: String = "",
    @SerialName("courier_code") val courierCode: String? = null,
    @SerialName("delivery_status") val deliveryStatus: String? = null,
    @SerialName("expected_delivery") val expectedDelivery: String? = null,
    @SerialName("origin_info") val originInfo: TmOriginInfo? = null,
    @SerialName("destination_info") val destinationInfo: TmOriginInfo? = null,
)

@Serializable data class TmOriginInfo(@SerialName("trackinfo") val trackInfo: List<TmCheckpoint> = emptyList())

@Serializable data class TmCheckpoint(
    @SerialName("checkpoint_date") val checkpointDate: String? = null,
    @SerialName("tracking_detail") val trackingDetail: String? = null,
    @SerialName("location") val location: String? = null,
    @SerialName("checkpoint_delivery_status") val checkpointDeliveryStatus: String? = null,
)

@Serializable data class TmCreateRequest(
    @SerialName("tracking_number") val trackingNumber: String,
    @SerialName("courier_code") val courierCode: String? = null,
)

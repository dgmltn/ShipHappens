package com.shiphappens.core.data.source

import com.shiphappens.core.model.*
import com.shiphappens.source.api.*

class FakeSource(
    id: String,
    kind: SourceKind = SourceKind.CARRIER,
    private val detects: Carrier? = null,
    var trackResult: SourceResult<TrackingSnapshot> = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)),
) : TrackingSource {
    override val descriptor = SourceDescriptor(id, id.uppercase(), kind)
    val trackedNumbers = mutableListOf<String>()
    override fun detectCarrier(trackingNumber: String): Carrier? = detects
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        trackedNumbers += trackingNumber
        return trackResult
    }
    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> = SourceResult.Success(Unit)
}

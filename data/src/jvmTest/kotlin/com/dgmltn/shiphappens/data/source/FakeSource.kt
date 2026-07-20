package com.dgmltn.shiphappens.data.source

import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*

class FakeSource(
    id: String,
    private val detects: Carrier? = null,
    var trackResult: SourceResult<TrackingSnapshot> = SourceResult.Success(TrackingSnapshot(TrackingStatus.IN_TRANSIT)),
    implemented: Boolean = true,
) : TrackingSource {
    override val descriptor = SourceDescriptor(id, id.uppercase(), implemented = implemented)
    val trackedNumbers = mutableListOf<String>()
    override fun detectCarrier(trackingNumber: String): Carrier? = detects
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        trackedNumbers += trackingNumber
        return trackResult
    }
}

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
    // Claims only numbers the carrier's own pattern does, as the real sources do; a fake that
    // claimed everything would answer for a handed-off number meant for another source.
    override fun detectCarrier(trackingNumber: String): Carrier? =
        detects?.takeIf { it.numberPattern == null || it.claims(normalizeTracking(trackingNumber)) }
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        trackedNumbers += trackingNumber
        return trackResult
    }
}

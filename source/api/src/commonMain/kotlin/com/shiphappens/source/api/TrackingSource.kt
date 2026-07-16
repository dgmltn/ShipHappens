package com.shiphappens.source.api

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.TrackingSnapshot

enum class SourceKind { UNIVERSAL, CARRIER }

data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val kind: SourceKind,
    val accentColorHex: String? = null,
    /** False for stub sources that declare themselves but don't actually fetch live data yet. */
    val implemented: Boolean = true,
)

enum class FailureReason { AUTH, NETWORK, NOT_FOUND, RATE_LIMITED, UNKNOWN }

sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val reason: FailureReason, val message: String? = null) : SourceResult<Nothing>
}

interface TrackingSource {
    val descriptor: SourceDescriptor
    /** Cheap, local-only recognition. Null = "not mine / don't know". */
    fun detectCarrier(trackingNumber: String): Carrier?
    /**
     * Implementations should return [SourceResult.Failure] rather than throwing; the repository
     * additionally guards against thrown exceptions.
     */
    suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot>
}

data class SeedParcel(val name: String, val trackingNumber: String, val carrier: Carrier)

/** Optional capability: a source that seeds parcels when enabled (the demo source). */
interface SeedingSource {
    fun seeds(): List<SeedParcel>
}

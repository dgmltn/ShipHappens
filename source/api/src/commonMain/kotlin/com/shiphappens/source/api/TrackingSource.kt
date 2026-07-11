package com.shiphappens.source.api

import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.TrackingSnapshot

enum class SourceKind { UNIVERSAL, CARRIER }

data class ConfigField(
    val key: String,
    val label: String,
    val placeholder: String,
    val isSecret: Boolean = false,
)

data class SourceDescriptor(
    val id: String,
    val displayName: String,
    val kind: SourceKind,
    val accentColorHex: String? = null,
    val configSpec: List<ConfigField> = emptyList(),
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
    suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot>
    suspend fun testConnection(config: SourceConfig): SourceResult<Unit>
}

data class SeedParcel(val name: String, val trackingNumber: String, val carrier: Carrier)

/** Optional capability: a source that seeds parcels when enabled (the demo source). */
interface SeedingSource {
    fun seeds(): List<SeedParcel>
}

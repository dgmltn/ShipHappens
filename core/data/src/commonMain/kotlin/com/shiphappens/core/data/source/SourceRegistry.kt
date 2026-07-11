package com.shiphappens.core.data.source

import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.Parcel
import com.shiphappens.source.api.SourceKind
import com.shiphappens.source.api.TrackingSource
import kotlinx.coroutines.flow.first

class SourceRegistry(
    private val sources: List<TrackingSource>,
    private val settingsRepository: SettingsRepository,
) {
    fun all(): List<TrackingSource> = sources

    suspend fun enabled(): List<TrackingSource> {
        val configs = settingsRepository.settings.first().sourceConfigs
        return sources.filter { configs[it.descriptor.id]?.enabled == true }
    }

    suspend fun sourceFor(parcel: Parcel): TrackingSource? {
        // Only resolve among sources that actually implement live tracking — stub carrier
        // sources (implemented = false) must not intercept parcels that a universal source
        // could otherwise track.
        val enabled = enabled().filter { it.descriptor.implemented }
        parcel.sourceId?.let { pinned -> enabled.firstOrNull { it.descriptor.id == pinned }?.let { return it } }
        enabled.firstOrNull { it.detectCarrier(parcel.trackingNumber) != null }?.let { return it }
        return enabled.firstOrNull { it.descriptor.kind == SourceKind.UNIVERSAL }
    }

    suspend fun detectCarrier(trackingNumber: String): Carrier? =
        BuiltInCarrierDetection.detect(trackingNumber)
            ?: enabled().firstNotNullOfOrNull { it.detectCarrier(trackingNumber) }
}

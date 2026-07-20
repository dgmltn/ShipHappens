package com.dgmltn.shiphappens.data.source

import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.Parcel
import com.dgmltn.shiphappens.source.api.TrackingSource
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
        // sources (implemented = false) must not intercept parcels.
        val enabled = enabled().filter { it.descriptor.implemented }
        parcel.sourceId?.let { pinned -> enabled.firstOrNull { it.descriptor.id == pinned }?.let { return it } }
        return enabled.firstOrNull { it.detectCarrier(parcel.trackingNumber) != null }
    }

    suspend fun detectCarrier(trackingNumber: String): Carrier? =
        BuiltInCarrierDetection.detect(trackingNumber)
            ?: enabled().firstNotNullOfOrNull { it.detectCarrier(trackingNumber) }
}

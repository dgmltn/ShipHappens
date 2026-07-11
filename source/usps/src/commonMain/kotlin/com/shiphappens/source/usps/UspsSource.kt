package com.shiphappens.source.usps

import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.WellKnownCarriers
import com.shiphappens.core.model.normalizeTracking
import com.shiphappens.core.model.TrackingSnapshot
import com.shiphappens.source.api.*
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

class UspsSource : TrackingSource {
    override val descriptor = SourceDescriptor(
        id = "usps", displayName = "USPS", kind = SourceKind.CARRIER,
        accentColorHex = WellKnownCarriers.USPS.accentColorHex,
        configSpec = listOf(
            ConfigField("consumerKey", "Consumer key", "USPS consumer key"),
            ConfigField("consumerSecret", "Consumer secret", "••••••••", isSecret = true),
        ),
    )
    override fun detectCarrier(trackingNumber: String): Carrier? {
        val normalized = normalizeTracking(trackingNumber)
        return WellKnownCarriers.USPS.takeIf {
            Regex("^(94|93|92|95|82)\\d{14,24}$").matches(normalized) ||
            Regex("^[A-Z]{2}\\d{9}US$").matches(normalized)
        }
    }
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> =
        SourceResult.Failure(FailureReason.UNKNOWN, "USPS direct API not implemented yet")
    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> =
        if (descriptor.configSpec.all { config[it.key] != null }) SourceResult.Success(Unit)
        else SourceResult.Failure(FailureReason.AUTH, "Enter USPS credentials first")
}

val uspsSourceModule: Module = module { single { UspsSource() } bind TrackingSource::class }

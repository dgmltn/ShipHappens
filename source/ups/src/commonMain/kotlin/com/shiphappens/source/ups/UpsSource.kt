package com.shiphappens.source.ups

import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.WellKnownCarriers
import com.shiphappens.core.model.normalizeTracking
import com.shiphappens.core.model.TrackingSnapshot
import com.shiphappens.source.api.*
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

class UpsSource : TrackingSource {
    override val descriptor = SourceDescriptor(
        id = "ups", displayName = "UPS", kind = SourceKind.CARRIER,
        accentColorHex = WellKnownCarriers.UPS.accentColorHex,
        configSpec = listOf(
            ConfigField("clientId", "Client ID", "UPS OAuth client ID"),
            ConfigField("clientSecret", "Client secret", "••••••••", isSecret = true),
        ),
        implemented = false,
    )
    override fun detectCarrier(trackingNumber: String): Carrier? =
        WellKnownCarriers.UPS.takeIf { Regex("^1Z[0-9A-Z]{10,}$").matches(normalizeTracking(trackingNumber)) }
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> =
        SourceResult.Failure(FailureReason.UNKNOWN, "UPS direct API not implemented yet")
    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> =
        if (descriptor.configSpec.all { config[it.key] != null }) SourceResult.Success(Unit)
        else SourceResult.Failure(FailureReason.AUTH, "Enter UPS credentials first")
}

val upsSourceModule: Module = module { single { UpsSource() } bind TrackingSource::class }

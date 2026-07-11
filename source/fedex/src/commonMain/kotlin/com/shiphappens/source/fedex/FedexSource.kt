package com.shiphappens.source.fedex

import com.shiphappens.core.model.Carrier
import com.shiphappens.core.model.WellKnownCarriers
import com.shiphappens.core.model.normalizeTracking
import com.shiphappens.core.model.TrackingSnapshot
import com.shiphappens.source.api.*
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

class FedexSource : TrackingSource {
    override val descriptor = SourceDescriptor(
        id = "fedex", displayName = "FedEx", kind = SourceKind.CARRIER,
        accentColorHex = WellKnownCarriers.FEDEX.accentColorHex,
        configSpec = listOf(
            ConfigField("apiKey", "API key", "FedEx API key"),
            ConfigField("secretKey", "Secret key", "••••••••", isSecret = true),
        ),
        implemented = false,
    )
    override fun detectCarrier(trackingNumber: String): Carrier? =
        WellKnownCarriers.FEDEX.takeIf { Regex("^\\d{12}$|^\\d{15}$|^\\d{20,22}$").matches(normalizeTracking(trackingNumber)) }
    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> =
        SourceResult.Failure(FailureReason.UNKNOWN, "FedEx direct API not implemented yet")
    override suspend fun testConnection(config: SourceConfig): SourceResult<Unit> =
        if (descriptor.configSpec.all { config[it.key] != null }) SourceResult.Success(Unit)
        else SourceResult.Failure(FailureReason.AUTH, "Enter FedEx credentials first")
}

val fedexSourceModule: Module = module { single { FedexSource() } bind TrackingSource::class }

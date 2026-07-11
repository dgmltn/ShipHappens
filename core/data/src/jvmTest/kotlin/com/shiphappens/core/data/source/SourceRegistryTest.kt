package com.shiphappens.core.data.source

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.shiphappens.core.data.settings.SettingsRepository
import com.shiphappens.core.model.*
import com.shiphappens.source.api.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import kotlin.time.Instant
import kotlin.test.*

class SourceRegistryTest {
    private fun settings(scope: CoroutineScope): SettingsRepository {
        val dir = kotlin.io.path.createTempDirectory("reg").toString()
        return SettingsRepository(PreferenceDataStoreFactory.createWithPath(scope = scope) { "$dir/s.preferences_pb".toPath() })
    }
    private fun parcel(sourceId: String? = null, tracking: String = "1Z999AA10123456784") = Parcel(
        id = "p", name = "P", trackingNumber = tracking, carrier = WellKnownCarriers.UPS,
        sourceId = sourceId, createdAt = Instant.fromEpochMilliseconds(0),
    )

    @Test fun pinned_source_wins_when_enabled() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val s = settings(scope)
        val pinned = FakeSource("pinned")
        val other = FakeSource("other", detects = WellKnownCarriers.UPS)
        s.setSourceConfig("pinned", SourceConfig(enabled = true))
        s.setSourceConfig("other", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(other, pinned), s)
        assertEquals("pinned", reg.sourceFor(parcel(sourceId = "pinned"))!!.descriptor.id)
        scope.cancel()
    }

    @Test fun falls_back_to_detecting_source_then_universal() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val s = settings(scope)
        val carrier = FakeSource("carrier-src", detects = WellKnownCarriers.UPS)
        val universal = FakeSource("universal-src", kind = SourceKind.UNIVERSAL)
        s.setSourceConfig("universal-src", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(carrier, universal), s)
        // carrier-src disabled -> universal catches it
        assertEquals("universal-src", reg.sourceFor(parcel())!!.descriptor.id)
        s.setSourceConfig("carrier-src", SourceConfig(enabled = true))
        assertEquals("carrier-src", reg.sourceFor(parcel())!!.descriptor.id)
        scope.cancel()
    }

    @Test fun unimplemented_carrier_source_is_skipped_for_universal_fallback() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val s = settings(scope)
        val stubCarrier = FakeSource("stub-carrier", detects = WellKnownCarriers.UPS, implemented = false)
        val universal = FakeSource("universal-src", kind = SourceKind.UNIVERSAL)
        s.setSourceConfig("stub-carrier", SourceConfig(enabled = true))
        s.setSourceConfig("universal-src", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(stubCarrier, universal), s)
        // stub-carrier would normally win by detectCarrier match, but it's not implemented,
        // so the universal source must win instead.
        assertEquals("universal-src", reg.sourceFor(parcel())!!.descriptor.id)
        scope.cancel()
    }

    @Test fun no_enabled_source_returns_null() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val reg = SourceRegistry(listOf(FakeSource("x")), settings(scope))
        assertNull(reg.sourceFor(parcel()))
        scope.cancel()
    }

    @Test fun detectCarrier_uses_builtins_before_sources() = runTest {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val s = settings(scope)
        val dhl = Carrier("dhl", "DHL")
        val src = FakeSource("u", kind = SourceKind.UNIVERSAL, detects = dhl)
        s.setSourceConfig("u", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(src), s)
        assertEquals(WellKnownCarriers.UPS, reg.detectCarrier("1Z999AA10123456784"))
        assertEquals(dhl, reg.detectCarrier("XX99887766554433"))
        scope.cancel()
    }
}

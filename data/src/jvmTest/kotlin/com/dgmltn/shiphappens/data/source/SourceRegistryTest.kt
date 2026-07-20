package com.dgmltn.shiphappens.data.source

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.domain.*
import com.dgmltn.shiphappens.source.api.*
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
        val src = FakeSource("u", detects = dhl)
        s.setSourceConfig("u", SourceConfig(enabled = true))
        val reg = SourceRegistry(listOf(src), s)
        assertEquals(WellKnownCarriers.UPS, reg.detectCarrier("1Z999AA10123456784"))
        assertEquals(dhl, reg.detectCarrier("XX99887766554433"))
        scope.cancel()
    }
}

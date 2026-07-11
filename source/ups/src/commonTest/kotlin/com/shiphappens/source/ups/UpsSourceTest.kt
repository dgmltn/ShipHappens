package com.shiphappens.source.ups

import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.source.api.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class UpsSourceTest {
    private val src = UpsSource()

    @Test fun descriptor_declares_oauth_fields() {
        assertEquals("ups", src.descriptor.id)
        assertEquals(SourceKind.CARRIER, src.descriptor.kind)
        assertEquals(listOf("clientId", "clientSecret"), src.descriptor.configSpec.map { it.key })
        assertFalse(src.descriptor.implemented)
    }
    @Test fun detects_1z_numbers_only() {
        assertEquals(WellKnownCarriers.UPS, src.detectCarrier("1Z 999 AA1 01 2345 6784"))
        assertNull(src.detectCarrier("9400111899223300112"))
    }
    @Test fun track_is_not_implemented_and_test_connection_validates_fields() = runTest {
        assertIs<SourceResult.Failure>(src.track("1Z999AA10123456784", null))
        val empty = src.testConnection(SourceConfig(enabled = true))
        assertEquals(FailureReason.AUTH, (empty as SourceResult.Failure).reason)
        assertIs<SourceResult.Success<Unit>>(
            src.testConnection(SourceConfig(enabled = true, values = mapOf("clientId" to "a", "clientSecret" to "b")))
        )
    }
}

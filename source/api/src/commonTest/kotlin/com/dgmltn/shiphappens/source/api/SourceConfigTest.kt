package com.dgmltn.shiphappens.source.api

import kotlinx.serialization.json.Json
import kotlin.test.*

class SourceConfigTest {
    @Test fun sourceConfig_roundtrips_through_json() {
        val cfg = SourceConfig(enabled = true, values = mapOf("apiKey" to "tm-123"))
        val json = Json.encodeToString(SourceConfig.serializer(), cfg)
        assertEquals(cfg, Json.decodeFromString(SourceConfig.serializer(), json))
    }

    @Test fun failure_carries_reason() {
        val f: SourceResult<Unit> = SourceResult.Failure(FailureReason.AUTH, "bad key")
        assertTrue(f is SourceResult.Failure && f.reason == FailureReason.AUTH)
    }
}

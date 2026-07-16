package com.shiphappens.source.api

import kotlinx.serialization.Serializable

@Serializable
data class SourceConfig(
    val enabled: Boolean = false,
    val values: Map<String, String> = emptyMap(),
) {
    operator fun get(key: String): String? = values[key]?.takeIf { it.isNotBlank() }
}

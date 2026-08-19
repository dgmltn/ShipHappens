package com.dgmltn.shiphappens.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dgmltn.shiphappens.source.api.SourceConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalTime
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class RefreshFrequency(val staleAfterMinutes: Long?) {
    FIFTEEN_MIN(15), ONE_HOUR(60), MANUAL(null)
}

data class AppSettings(
    val sourceConfigs: Map<String, SourceConfig> = emptyMap(),
    val autoClipboardImport: Boolean = true,
    val refreshFrequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN,
    val dailyUpdateEnabled: Boolean = false,
    val dailyUpdateTime: LocalTime = DEFAULT_DAILY_UPDATE_TIME,
    /** Sources already nagged about an expired session; cleared when they next succeed. */
    val signInNaggedSourceIds: Set<String> = emptySet(),
)

val DEFAULT_DAILY_UPDATE_TIME = LocalTime(8, 0)

private val KEY_SOURCE_CONFIGS = stringPreferencesKey("source_configs")
private val KEY_AUTO_CLIPBOARD = booleanPreferencesKey("auto_clipboard_import")
private val KEY_FREQUENCY = stringPreferencesKey("refresh_frequency")
private val KEY_DAILY_ENABLED = booleanPreferencesKey("daily_update_enabled")
private val KEY_DAILY_TIME = stringPreferencesKey("daily_update_time")
private val KEY_SIGNIN_NAGGED = stringPreferencesKey("sign_in_nagged_source_ids")
private val configsSerializer = MapSerializer(String.serializer(), SourceConfig.serializer())

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            sourceConfigs = prefs[KEY_SOURCE_CONFIGS]
                ?.let { runCatching { json.decodeFromString(configsSerializer, it) }.getOrNull() }
                ?: emptyMap(),
            autoClipboardImport = prefs[KEY_AUTO_CLIPBOARD] ?: true,
            refreshFrequency = prefs[KEY_FREQUENCY]
                ?.let { runCatching { RefreshFrequency.valueOf(it) }.getOrNull() }
                ?: RefreshFrequency.FIFTEEN_MIN,
            dailyUpdateEnabled = prefs[KEY_DAILY_ENABLED] ?: false,
            dailyUpdateTime = prefs[KEY_DAILY_TIME]
                ?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                ?: DEFAULT_DAILY_UPDATE_TIME,
            signInNaggedSourceIds = prefs[KEY_SIGNIN_NAGGED]
                ?.split(',')?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet(),
        )
    }

    suspend fun setSourceConfig(sourceId: String, config: SourceConfig) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SOURCE_CONFIGS]
                ?.let { runCatching { json.decodeFromString(configsSerializer, it) }.getOrNull() }
                ?: emptyMap()
            prefs[KEY_SOURCE_CONFIGS] = json.encodeToString(configsSerializer, current + (sourceId to config))
        }
    }

    /** Atomically transform one source's config inside a single DataStore edit. Returns the new value. */
    suspend fun updateSourceConfig(sourceId: String, transform: (SourceConfig) -> SourceConfig): SourceConfig {
        var result = SourceConfig()
        dataStore.edit { prefs ->
            val current = prefs[KEY_SOURCE_CONFIGS]
                ?.let { runCatching { json.decodeFromString(configsSerializer, it) }.getOrNull() }
                ?: emptyMap()
            result = transform(current[sourceId] ?: SourceConfig())
            prefs[KEY_SOURCE_CONFIGS] = json.encodeToString(configsSerializer, current + (sourceId to result))
        }
        return result
    }

    suspend fun setAutoClipboardImport(enabled: Boolean) {
        dataStore.edit { it[KEY_AUTO_CLIPBOARD] = enabled }
    }

    suspend fun setRefreshFrequency(freq: RefreshFrequency) {
        dataStore.edit { it[KEY_FREQUENCY] = freq.name }
    }

    suspend fun setDailyUpdateEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_DAILY_ENABLED] = enabled }
    }

    suspend fun setDailyUpdateTime(time: LocalTime) {
        // LocalTime.toString() is ISO ("06:45"), which LocalTime.parse round-trips.
        dataStore.edit { it[KEY_DAILY_TIME] = time.toString() }
    }

    suspend fun setSignInNaggedSourceIds(ids: Set<String>) {
        dataStore.edit { it[KEY_SIGNIN_NAGGED] = ids.joinToString(",") }
    }

    /** Test hook for the corrupt-value fallback path; not used by production code. */
    internal suspend fun writeRawDailyUpdateTimeForTest(raw: String) {
        dataStore.edit { it[KEY_DAILY_TIME] = raw }
    }
}

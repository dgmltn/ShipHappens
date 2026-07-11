package com.shiphappens.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.data.ParcelRepository
import com.shiphappens.data.settings.RefreshFrequency
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.source.api.*
import com.shiphappens.design.accentHex
import com.shiphappens.domain.Carrier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class FieldUi(val key: String, val label: String, val placeholder: String, val isSecret: Boolean, val value: String)

data class SourceCardUi(
    val id: String, val name: String, val accentHex: String, val enabled: Boolean,
    val statusText: String, val statusColorHex: String, val fields: List<FieldUi>, val endpointText: String?,
)

data class SettingsUiState(
    val universal: List<SourceCardUi> = emptyList(),
    val carriers: List<SourceCardUi> = emptyList(),
    val autoImport: Boolean = true,
    val frequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN,
    val toast: String? = null,
)

class SettingsViewModel(
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val repository: ParcelRepository,
) : ViewModel() {

    private val toast = MutableStateFlow<String?>(null)
    private var toastJob: Job? = null

    val state: StateFlow<SettingsUiState> = combine(settings.settings, toast) { s, t ->
        val cards = registry.all().map { src ->
            val d = src.descriptor
            val cfg = s.sourceConfigs[d.id] ?: SourceConfig()
            val configured = d.configSpec.all { cfg[it.key] != null }
            val (statusText, statusColor) = when {
                !cfg.enabled -> "Not connected" to "#A8A296"
                !d.implemented -> "Direct API coming soon" to "#A8A296"
                !configured -> "Enabled · add your credentials" to "#C2410C"
                d.kind == SourceKind.UNIVERSAL -> "Connected · 1,000+ couriers" to (d.accentColorHex ?: "#1F7A4D")
                else -> "Connected · syncing" to "#1F7A4D"
            }
            SourceCardUi(
                id = d.id, name = d.displayName,
                accentHex = d.accentColorHex ?: Carrier(d.id, d.displayName).accentHex(),
                enabled = cfg.enabled, statusText = statusText, statusColorHex = statusColor,
                fields = d.configSpec.map { f -> FieldUi(f.key, f.label, f.placeholder, f.isSecret, cfg.values[f.key] ?: "") },
                endpointText = when {
                    d.id == "trackingmore" -> "api.trackingmore.com/v4"
                    d.kind == SourceKind.CARRIER -> "Production endpoint"
                    else -> null
                },
            )
        }
        SettingsUiState(
            universal = cards.filter { c -> registry.all().first { it.descriptor.id == c.id }.descriptor.kind == SourceKind.UNIVERSAL },
            carriers = cards.filter { c -> registry.all().first { it.descriptor.id == c.id }.descriptor.kind == SourceKind.CARRIER },
            autoImport = s.autoClipboardImport,
            frequency = s.refreshFrequency,
            toast = t,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onToggle(sourceId: String) {
        viewModelScope.launch {
            val new = settings.updateSourceConfig(sourceId) { it.copy(enabled = !it.enabled) }
            if (new.enabled) repository.refreshAll(force = false)  // just turned ON: seed + refresh
        }
    }

    fun onField(sourceId: String, key: String, value: String) {
        viewModelScope.launch {
            settings.updateSourceConfig(sourceId) { it.copy(values = it.values + (key to value)) }
        }
    }

    fun onTest(sourceId: String) {
        viewModelScope.launch {
            val src = registry.all().firstOrNull { it.descriptor.id == sourceId } ?: return@launch
            when (val r = src.testConnection(settings.current(sourceId))) {
                is SourceResult.Success -> flash("${src.descriptor.displayName} credentials look valid")
                is SourceResult.Failure -> flash(r.message ?: "Couldn't reach ${src.descriptor.displayName}")
            }
        }
    }

    fun onAutoImport(enabled: Boolean) { viewModelScope.launch { settings.setAutoClipboardImport(enabled) } }
    fun onFrequency(freq: RefreshFrequency) { viewModelScope.launch { settings.setRefreshFrequency(freq) } }

    private fun flash(message: String) {
        toastJob?.cancel()
        toast.value = message
        toastJob = viewModelScope.launch { delay(2_600); toast.value = null }
    }
}

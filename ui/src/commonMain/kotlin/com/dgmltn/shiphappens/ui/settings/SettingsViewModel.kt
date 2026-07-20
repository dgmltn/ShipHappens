package com.dgmltn.shiphappens.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dgmltn.shiphappens.data.ParcelRepository
import com.dgmltn.shiphappens.data.settings.RefreshFrequency
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.source.api.*
import com.dgmltn.shiphappens.source.webview.WebCapableSource
import com.dgmltn.shiphappens.source.webview.WebCookieJar
import com.dgmltn.shiphappens.design.accentHex
import com.dgmltn.shiphappens.domain.Carrier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class SourceCardUi(
    val id: String, val name: String, val accentHex: String, val enabled: Boolean,
    val statusText: String, val statusColorHex: String,
    val webCapable: Boolean = false, val signedIn: Boolean = false,
)

data class SettingsUiState(
    val carriers: List<SourceCardUi> = emptyList(),
    val autoImport: Boolean = true,
    val frequency: RefreshFrequency = RefreshFrequency.FIFTEEN_MIN,
    val toast: String? = null,
)

class SettingsViewModel(
    private val registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val repository: ParcelRepository,
    private val cookieJar: WebCookieJar,
) : ViewModel() {

    private val toast = MutableStateFlow<String?>(null)
    private var toastJob: Job? = null

    val state: StateFlow<SettingsUiState> = combine(settings.settings, toast) { s, t ->
        val cards = registry.all().map { src ->
            val d = src.descriptor
            val cfg = s.sourceConfigs[d.id] ?: SourceConfig()
            val (statusText, statusColor) = when {
                !cfg.enabled -> "Not connected" to "#A8A296"
                !d.implemented -> "Coming soon" to "#A8A296"
                else -> "Connected · syncing" to "#1F7A4D"
            }
            val webSpec = (src as? WebCapableSource)?.webSpec
            SourceCardUi(
                id = d.id, name = d.displayName,
                accentHex = d.accentColorHex ?: Carrier(d.id, d.displayName).accentHex(),
                enabled = cfg.enabled, statusText = statusText, statusColorHex = statusColor,
                webCapable = webSpec != null,
                signedIn = webSpec != null && cfg.values["loggedIn"] == "true",
            )
        }
        SettingsUiState(
            carriers = cards,
            autoImport = s.autoClipboardImport,
            frequency = s.refreshFrequency,
            toast = t,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onToggle(sourceId: String) {
        viewModelScope.launch {
            val new = settings.updateSourceConfig(sourceId) { it.copy(enabled = !it.enabled) }
            if (new.enabled) repository.refreshAll(force = false)  // just turned ON: refresh immediately
        }
    }

    /**
     * Returns the write Job (null if there's no web-capable source to sign out of) so tests can
     * await it — settings.updateSourceConfig suspends through DataStore's own dispatchers and can
     * otherwise resume onto Dispatchers.Main after a test's tearDown() has called resetMain(),
     * crashing a later test (same hazard as WebDetailViewModel.onPayload, see Task 7). UI call
     * sites coerce the reference to Unit.
     */
    fun onSignOut(sourceId: String): Job? {
        val spec = (registry.all().firstOrNull { it.descriptor.id == sourceId } as? WebCapableSource)?.webSpec ?: return null
        return viewModelScope.launch {
            cookieJar.clearForDomain(spec.cookieDomain)
            settings.updateSourceConfig(sourceId) { it.copy(values = it.values - "loggedIn") }
            flash("Signed out of ${spec.carrier.displayName}")
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

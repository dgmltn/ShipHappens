package com.dgmltn.shiphappens.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dgmltn.shiphappens.data.ParcelRepository
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.design.accentHex
import com.dgmltn.shiphappens.source.webview.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class WebDetailUiState(
    val loaded: Boolean = false,
    val url: String = "",
    val carrierName: String = "",
    val accentHex: String = "#17150F",
    val spec: WebProviderSpec? = null,
    val showLoginHint: Boolean = false,
)

class WebDetailViewModel(
    private val parcelId: String,
    private val repository: ParcelRepository,
    registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val cookieJar: WebCookieJar,
) : ViewModel() {

    private val webSources = registry.all().filterIsInstance<WebCapableSource>()

    // Serializes onPayload's read-modify-write against repository.applySnapshot: two Tracking
    // payloads routed close together each launch their own coroutine, and without this lock both
    // could read the same baseline row and clobber each other's field-preserve branches.
    private val applyMutex = Mutex()

    val state: StateFlow<WebDetailUiState> =
        combine(repository.observeParcel(parcelId), settings.settings) { parcel, appSettings ->
            val spec = parcel?.let { p -> webSources.firstOrNull { it.webSpec.carrier.code == p.carrier.code }?.webSpec }
            if (parcel == null || spec == null) WebDetailUiState()
            else WebDetailUiState(
                loaded = true,
                url = spec.trackingUrl(parcel.normalizedTracking),
                carrierName = spec.carrier.displayName,
                accentHex = parcel.carrier.accentHex(),
                spec = spec,
                showLoginHint = appSettings.sourceConfigs[spec.sourceId]?.values?.get("loggedIn") != "true",
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebDetailUiState())

    /**
     * Scrape-on-view: every Tracking payload the page produces is persisted immediately.
     * Returns the write Job (null for non-tracking payloads) so tests can await the write;
     * UI call sites coerce the reference to Unit.
     */
    fun onPayload(json: String): Job? {
        val spec = state.value.spec ?: return null
        val routed = PayloadRouter(spec).route(json)
        if (routed !is RouteResult.Tracking) return null
        return viewModelScope.launch {
            applyMutex.withLock { repository.applySnapshot(parcelId, routed.tracking.toSnapshot(), spec.sourceId) }
        }
    }

    /** A login that happens mid-browse also flips the persisted flag and flushes cookies. */
    fun onEvent(event: PageEvent): Job? {
        val spec = state.value.spec ?: return null
        if (event is PageEvent.LoggedIn && event.loggedIn && state.value.showLoginHint) {
            return viewModelScope.launch {
                settings.updateSourceConfig(spec.sourceId) { it.copy(values = it.values + ("loggedIn" to "true")) }
                cookieJar.flush()  // force cookie persistence so the session survives process death
            }
        }
        return null
    }
}

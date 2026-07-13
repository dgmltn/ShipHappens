package com.shiphappens.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shiphappens.data.settings.SettingsRepository
import com.shiphappens.data.source.SourceRegistry
import com.shiphappens.source.webview.PageEvent
import com.shiphappens.source.webview.WebCapableSource
import com.shiphappens.source.webview.WebCookieJar
import com.shiphappens.source.webview.WebProviderSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WebLoginUiState(
    val url: String = "",
    val name: String = "",
    val accentHex: String = "#17150F",
    val spec: WebProviderSpec? = null,
    val done: Boolean = false,
)

class WebLoginViewModel(
    private val sourceId: String,
    registry: SourceRegistry,
    private val settings: SettingsRepository,
    private val cookieJar: WebCookieJar,
) : ViewModel() {

    private val spec: WebProviderSpec? =
        registry.all().filterIsInstance<WebCapableSource>().firstOrNull { it.webSpec.sourceId == sourceId }?.webSpec

    private val done = MutableStateFlow(false)

    val state: StateFlow<WebLoginUiState> = done.map { d ->
        spec?.let {
            WebLoginUiState(
                url = it.loginUrl, name = it.carrier.displayName,
                accentHex = it.carrier.accentColorHex ?: "#17150F", spec = it, done = d,
            )
        } ?: WebLoginUiState(done = d)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WebLoginUiState())

    fun onEvent(event: PageEvent) {
        if (event is PageEvent.LoggedIn && event.loggedIn && !done.value) {
            viewModelScope.launch {
                settings.updateSourceConfig(sourceId) { it.copy(values = it.values + ("loggedIn" to "true")) }
                cookieJar.flush()  // force cookie persistence so the session survives process death
                done.value = true
            }
        }
    }
}

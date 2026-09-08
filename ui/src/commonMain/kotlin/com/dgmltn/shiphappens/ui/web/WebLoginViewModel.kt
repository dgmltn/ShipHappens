package com.dgmltn.shiphappens.ui.web

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dgmltn.shiphappens.data.settings.SettingsRepository
import com.dgmltn.shiphappens.data.source.SourceRegistry
import com.dgmltn.shiphappens.source.webview.LoginRecipe
import com.dgmltn.shiphappens.source.webview.PageEvent
import com.dgmltn.shiphappens.source.webview.WebCapableSource
import com.dgmltn.shiphappens.source.webview.WebCookieJar
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
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

    private val target: Pair<WebProviderSpec, LoginRecipe>? =
        registry.all().filterIsInstance<WebCapableSource>()
            .firstOrNull { it.webSpec.sourceId == sourceId }?.webSpec
            ?.let { spec -> spec.login?.let { spec to it } }

    private val done = MutableStateFlow(false)

    val state: StateFlow<WebLoginUiState> = done.map { d ->
        target?.let { (spec, login) ->
            WebLoginUiState(
                url = login.url, name = spec.carrier.displayName,
                accentHex = spec.carrier.accentColorHex ?: "#17150F", spec = spec, done = d,
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

package com.shiphappens.ui.web

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.shiphappens.source.webview.PageEvent
import com.shiphappens.source.webview.WebProviderSpec

/**
 * A carrier web page with the ShipHappens capture bridge installed. Android backs this with
 * android.webkit.WebView via WebSessions; iOS shows a placeholder until a WKWebView actual
 * lands (spec §9). Callbacks may fire on non-main threads.
 */
@Composable
expect fun PlatformWebView(
    url: String,
    spec: WebProviderSpec,
    onPayload: (String) -> Unit,
    onEvent: (PageEvent) -> Unit,
    modifier: Modifier = Modifier,
)

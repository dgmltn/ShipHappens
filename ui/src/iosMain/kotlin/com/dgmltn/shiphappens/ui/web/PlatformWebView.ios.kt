package com.dgmltn.shiphappens.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dgmltn.shiphappens.source.webview.PageEvent
import com.dgmltn.shiphappens.source.webview.WebProviderSpec

@Composable
actual fun PlatformWebView(
    url: String,
    spec: WebProviderSpec,
    onPayload: (String) -> Unit,
    onEvent: (PageEvent) -> Unit,
    modifier: Modifier,
) {
    // WKWebView actual is a future phase (spec §9).
    Box(modifier, contentAlignment = Alignment.Center) {
        Text("The in-app ${spec.carrier.displayName} page isn't available on iOS yet.", Modifier.padding(24.dp))
    }
}

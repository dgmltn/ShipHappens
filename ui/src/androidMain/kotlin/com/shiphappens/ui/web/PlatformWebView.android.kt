package com.shiphappens.ui.web

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.shiphappens.source.webview.PageEvent
import com.shiphappens.source.webview.WebProviderSpec
import com.shiphappens.source.webview.WebSessions

@Composable
actual fun PlatformWebView(
    url: String,
    spec: WebProviderSpec,
    onPayload: (String) -> Unit,
    onEvent: (PageEvent) -> Unit,
    modifier: Modifier,
) {
    // key(url): recreate rather than reload — redirects mutate WebView.url, so an update-block
    // "reload if changed" check would loop.
    key(url) {
        // remember'd holder (not a plain local): the factory runs once, but the composable can
        // recompose before disposal — a plain local would reset to null and leak the WebView.
        val holder = remember { arrayOfNulls<WebView>(1) }
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                WebView(ctx).apply {
                    WebSessions.configure(this, spec, onPayload, onEvent)
                    loadUrl(url)
                    holder[0] = this
                }
            },
        )
        DisposableEffect(Unit) {
            onDispose {
                holder[0]?.destroy()
                holder[0] = null
            }
        }
    }
}

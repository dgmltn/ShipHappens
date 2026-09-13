package com.dgmltn.shiphappens.ui.web

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.dgmltn.shiphappens.source.webview.PageEvent
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.WebSessions
import com.dgmltn.shiphappens.source.webview.debug.ScrapeTracer
import org.koin.compose.koinInject

@Composable
actual fun PlatformWebView(
    url: String,
    spec: WebProviderSpec,
    onPayload: (String) -> Unit,
    onEvent: (PageEvent) -> Unit,
    modifier: Modifier,
) {
    // Trace the visible scrape-on-view path too, using the same DI-provided tracer as the headless
    // scraper (gated by WebScrapeDebug.enabled).
    val tracer = koinInject<ScrapeTracer>()
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
                    // Until Chromium delivers its first frame, WebView's hardware draw clears its
                    // render target with the view background — unclipped, so during the nav
                    // transition (an offscreen layer) the default opaque white blanks the whole
                    // window, header and status bar included, for a second or more. A transparent
                    // background makes that clear a no-op; the page paints its own background.
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    WebSessions.configure(this, spec, onPayload, onEvent, tracer)
                    // Give Chromium a frame to present right away. On hardware, WebView composites
                    // through its own SurfaceControl overlay, attached the first time Chromium has a
                    // frame; attaching re-composites the whole window (a visible flash). A blank
                    // document commits instantly, so that attach lands during the screen's
                    // enter transition instead of seconds later at the carrier page's first paint.
                    // WebSessions ignores this navigation in its client callbacks.
                    loadUrl(WebSessions.BLANK_URL)
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

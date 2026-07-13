package com.shiphappens.source.webview

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

/**
 * Runs a real page load in an off-screen WebView and returns the bridge payloads it produced.
 *
 * - One scrape at a time (mutex) — spec §6.
 * - WebView must be created and driven on the main thread; it is never attached to a window.
 *   A fresh WebView per scrape keeps callback wiring simple and leak-free; the session itself
 *   (cookies) is app-global via CookieManager, so login state persists across scrapes.
 * - The extraction runner always posts exactly one 'dom' payload after the settle delay
 *   (see BridgeScripts), so its arrival is the end-of-scrape signal.
 * - Politeness: successful results are cached per URL in [throttle]; within the window the
 *   cached result is returned without loading the page at all — even on forced refresh.
 */
class HeadlessWebViewScraper(
    private val context: Context,
    private val throttle: ScrapeThrottle,
) : WebScraper {

    override val isAvailable = true
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun scrape(spec: WebProviderSpec, trackingNumber: String): ScrapeResult = mutex.withLock {
        val url = spec.trackingUrl(trackingNumber)
        throttle.cached(url)?.let { return it }
        val result = withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(SCRAPE_TIMEOUT_MS) { runScrape(spec, url) } ?: ScrapeResult.Timeout
        }
        // Only cache genuine tracking results (spec §7) — a login-wall, bot-challenge, or
        // not-found "dom" payload is still a ScrapeResult.Payloads but must NOT be cached,
        // or a stale failure would shadow a real scrape once the user resolves it.
        if (result is ScrapeResult.Payloads) {
            val router = PayloadRouter(spec)
            if (result.payloads.any { router.route(it) is RouteResult.Tracking }) {
                throttle.record(url, result)
            }
        }
        result
    }

    private suspend fun runScrape(spec: WebProviderSpec, url: String): ScrapeResult {
        val webView = WebView(context)
        return try {
            // Give the detached view a plausible viewport so the page lays out and runs scripts.
            webView.layout(0, 0, 1080, 2000)
            val payloads = mutableListOf<String>()
            val done = CompletableDeferred<ScrapeResult>()
            WebSessions.configure(
                webView, spec,
                onPayload = { payload ->
                    val kind = runCatching { json.decodeFromString<BridgePayload>(payload).kind }.getOrNull()
                    synchronized(payloads) { payloads += payload }
                    if (kind == "dom") done.complete(ScrapeResult.Payloads(synchronized(payloads) { payloads.toList() }))
                },
                onEvent = { event ->
                    if (event is PageEvent.LoadFailed) done.complete(ScrapeResult.LoadError(event.message))
                },
            )
            webView.loadUrl(url)
            done.await()
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private companion object {
        const val SCRAPE_TIMEOUT_MS = 25_000L
    }
}

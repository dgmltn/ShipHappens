package com.shiphappens.source.webview

import android.content.Context
import android.webkit.WebView
import com.shiphappens.source.webview.debug.ScrapeTracer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    private val tracer: ScrapeTracer,
) : WebScraper {

    override val isAvailable = true
    private val mutex = Mutex()

    override suspend fun scrape(spec: WebProviderSpec, trackingNumber: String): ScrapeResult = mutex.withLock {
        val url = spec.trackingUrl(trackingNumber)
        tracer.scrapeStarted(spec.sourceId, url)
        throttle.cached(url)?.let {
            tracer.cacheHit(spec.sourceId, url)
            return it
        }
        val result = withContext(Dispatchers.Main.immediate) {
            withTimeoutOrNull(SCRAPE_TIMEOUT_MS) { runScrape(spec, url) } ?: ScrapeResult.Timeout
        }
        // Only cache genuine tracking results (spec §7) — a login-wall, bot-challenge, or
        // not-found "dom" payload is still a ScrapeResult.Payloads but must NOT be cached,
        // or a stale failure would shadow a real scrape once the user resolves it.
        val summary = if (result is ScrapeResult.Payloads) {
            val router = PayloadRouter(spec)
            val routed = result.payloads.map { router.route(it) }
            if (routed.any { it is RouteResult.Tracking }) {
                throttle.record(url, result)
                "${result.payloads.size} payload(s), TRACKING found — cached"
            } else {
                "${result.payloads.size} payload(s), NO tracking — routed=${routed.map { it::class.simpleName }}"
            }
        } else {
            "${result::class.simpleName} (no payloads)"
        }
        tracer.scrapeFinished(spec.sourceId, summary)
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
                    synchronized(payloads) { payloads += payload }
                    // Complete as soon as a payload routes to a DEFINITIVE outcome — normally the
                    // captured API JSON (Tracking), which lands well before the heavy UPS SPA fires
                    // onPageFinished (and sometimes it never fires at all). A DOM "page:empty"
                    // result routes to Unparsed and must NOT complete the scrape: the DOM extractor
                    // frequently runs before the tracking XHR lands, so completing on empty would
                    // discard the API capture that arrives moments later.
                    when (PayloadRouter(spec).route(payload)) {
                        is RouteResult.Tracking, is RouteResult.LoginWall,
                        is RouteResult.Challenge, is RouteResult.NotFound ->
                            done.complete(ScrapeResult.Payloads(synchronized(payloads) { payloads.toList() }))
                        is RouteResult.Unparsed -> Unit // keep waiting (empty DOM before the API lands)
                    }
                },
                onEvent = { event ->
                    if (event is PageEvent.LoadFailed) done.complete(ScrapeResult.LoadError(event.message))
                },
                tracer = tracer,
            )
            webView.loadUrl(url)
            done.await()
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private companion object {
        // The UPS SPA can take ~15-24s to fire its tracking XHR; give margin past that so a slow
        // API capture still completes before the backstop timeout.
        const val SCRAPE_TIMEOUT_MS = 30_000L
    }
}

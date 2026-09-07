package com.dgmltn.shiphappens.source.webview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import com.dgmltn.shiphappens.source.webview.debug.ScrapeTracer
import java.util.concurrent.atomic.AtomicBoolean
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
    private val tracer: ScrapeTracer,
) : WebScraper {

    override val isAvailable = true
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    private fun isDomPayload(payload: String): Boolean =
        runCatching { json.decodeFromString<BridgePayload>(payload).kind }.getOrNull() == "dom"

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
            // A Goto with embedded coarse tracking is a genuine result (WebViewBasedSource will
            // surface it), so it gets the same politeness caching as a rich extraction.
            if (routed.any { it is RouteResult.Tracking || (it is RouteResult.Goto && it.coarse != null) }) {
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
            val router = PayloadRouter(spec)
            val hopped = AtomicBoolean(false)
            WebSessions.configure(
                webView, spec,
                onPayload = { payload ->
                    synchronized(payloads) { payloads += payload }
                    fun completeWithPayloads() = done.complete(ScrapeResult.Payloads(synchronized(payloads) { payloads.toList() }))
                    // Complete as soon as a payload routes to a DEFINITIVE outcome — normally the
                    // captured API JSON (Tracking), which lands well before the heavy UPS SPA fires
                    // onPageFinished (and sometimes it never fires at all). A DOM "page:empty"
                    // result routes to Unparsed and must NOT complete the scrape: the DOM extractor
                    // frequently runs before the tracking XHR lands, so completing on empty would
                    // discard the API capture that arrives moments later.
                    when (val routed = router.route(payload)) {
                        is RouteResult.Tracking, is RouteResult.LoginWall,
                        is RouteResult.Challenge, is RouteResult.NotFound -> completeWithPayloads()
                        is RouteResult.Goto ->
                            // Bounded to ONE hop per scrape (design spec §1): the first goto navigates to
                            // the shipment tracker within the same session/cookies; any later goto ends the
                            // scrape instead, so a page cycle can't loop the WebView until timeout.
                            if (hopped.compareAndSet(false, true)) {
                                tracer.hopStarted(spec.sourceId, routed.url)
                                // This callback runs on the WebView JavaBridge thread; WebView methods
                                // must be called on main. The view may already be destroyed — absorb.
                                mainHandler.post { runCatching { webView.loadUrl(routed.url) } }
                            } else {
                                completeWithPayloads()
                            }
                        is RouteResult.Unparsed ->
                            // Post-hop: the target page's dom payload is the only terminator a DOM-only
                            // provider will ever send, so even page:'empty' ends the scrape — the goto's
                            // embedded coarse tracking still yields a result downstream.
                            // Pre-hop: normally keep waiting, because an empty DOM often precedes the
                            // API capture. But a provider with no apiUrlPatterns has no second source to
                            // wait for, so waiting can only ever end in the backstop timeout — end the
                            // scrape now and report the failure in seconds instead of hanging 30s.
                            if (isDomPayload(payload) && (hopped.get() || spec.apiUrlPatterns.isEmpty())) {
                                completeWithPayloads()
                            }
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

package com.dgmltn.shiphappens.source.webview

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dgmltn.shiphappens.source.webview.debug.NoOpScrapeTracer
import com.dgmltn.shiphappens.source.webview.debug.ScrapeTracer
import com.dgmltn.shiphappens.source.webview.debug.WebScrapeDebug
import kotlinx.serialization.json.Json

/**
 * Shared WebView wiring for BOTH the visible More-details/login screens and the headless
 * scraper, so cookies, UA, and extraction behave identically everywhere.
 *
 * Threading: [onPayload] fires on the WebView's JavaBridge thread and [onEvent] on the main
 * thread — callers must hop to their own scope (ViewModels use viewModelScope.launch).
 */
object WebSessions {

    /** Delay after onPageFinished before running the DOM extraction runner (lets XHRs land first). */
    const val SETTLE_MS = 3_000L

    // The settle delay MUST be scheduled on a main-Looper Handler, not view.postDelayed: the
    // headless scraper's WebView is never attached to a window, and View.postDelayed on an
    // unattached view parks the runnable in a RunQueue that only drains on window-attach (which
    // never happens headless) — so the extraction runner would never fire and every headless
    // scrape would time out, discarding even a successfully captured API payload.
    private val mainHandler = Handler(Looper.getMainLooper())

    private val json = Json { ignoreUnknownKeys = true }

    /** Decodes a bridge payload just far enough to route it to the right tracer event. Gated so no
     *  parsing happens when tracing is off. */
    private fun trace(sourceId: String, tracer: ScrapeTracer, rawPayload: String) {
        if (!WebScrapeDebug.enabled) return
        val payload = runCatching { json.decodeFromString<BridgePayload>(rawPayload) }.getOrNull()
        if (payload == null) {
            tracer.payloadUnparseable(sourceId, rawPayload)
            return
        }
        when (payload.kind) {
            "api" -> tracer.apiCaptured(sourceId, payload.url, payload.body)
            "dom" -> tracer.domResult(sourceId, payload.body)
            else -> tracer.payloadUnparseable(sourceId, rawPayload)
        }
    }

    private class Bridge(
        private val sourceId: String,
        private val tracer: ScrapeTracer,
        private val onPayload: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun postMessage(message: String) {
            trace(sourceId, tracer, message)
            onPayload(message)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun configure(
        webView: WebView,
        spec: WebProviderSpec,
        onPayload: (String) -> Unit,
        onEvent: (PageEvent) -> Unit,
        tracer: ScrapeTracer = NoOpScrapeTracer,
    ) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Bridge object: same JS call shape (shipBridge.postMessage) on every WebView version.
        webView.addJavascriptInterface(Bridge(spec.sourceId, tracer, onPayload), BridgeScripts.BRIDGE_NAME)

        val captureJs = BridgeScripts.captureScript(spec.apiUrlPatterns)
        val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(webView, captureJs, spec.allowedOriginRules().toSet())
        }
        tracer.sessionConfigured(spec.sourceId, spec.apiUrlPatterns, documentStartSupported)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // Fallback when document-start injection isn't available: inject ASAP at page
                // start. Racy against very early page requests, but the DOM extractor still
                // provides coverage when the capture layer misses.
                if (!documentStartSupported) view.evaluateJavascript(captureJs, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                tracer.pageFinished(spec.sourceId, url)
                onEvent(PageEvent.Finished(url))
                view.evaluateJavascript(spec.isLoggedInJs) { value ->
                    val loggedIn = value == "true"
                    tracer.loginState(spec.sourceId, loggedIn)
                    onEvent(PageEvent.LoggedIn(loggedIn))
                }
                mainHandler.postDelayed({
                    // The view may be destroyed (headless teardown) before this fires; there is no
                    // public "is destroyed" check, so runCatching absorbs the post-destroy call.
                    runCatching { view.evaluateJavascript(BridgeScripts.extractionRunner(spec), null) }
                }, SETTLE_MS)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    tracer.pageLoadFailed(spec.sourceId, error.description?.toString())
                    onEvent(PageEvent.LoadFailed(error.description?.toString()))
                }
            }
        }
    }
}

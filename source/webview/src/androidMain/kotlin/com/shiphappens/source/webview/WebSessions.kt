package com.shiphappens.source.webview

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

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

    private class Bridge(private val onPayload: (String) -> Unit) {
        @JavascriptInterface
        fun postMessage(message: String) = onPayload(message)
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    fun configure(webView: WebView, spec: WebProviderSpec, onPayload: (String) -> Unit, onEvent: (PageEvent) -> Unit) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Bridge object: same JS call shape (shipBridge.postMessage) on every WebView version.
        webView.addJavascriptInterface(Bridge(onPayload), BridgeScripts.BRIDGE_NAME)

        val captureJs = BridgeScripts.captureScript(spec.apiUrlPatterns)
        val documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(webView, captureJs, spec.allowedOriginRules().toSet())
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // Fallback when document-start injection isn't available: inject ASAP at page
                // start. Racy against very early page requests, but the DOM extractor still
                // provides coverage when the capture layer misses.
                if (!documentStartSupported) view.evaluateJavascript(captureJs, null)
            }

            override fun onPageFinished(view: WebView, url: String) {
                onEvent(PageEvent.Finished(url))
                view.evaluateJavascript(spec.isLoggedInJs) { value ->
                    onEvent(PageEvent.LoggedIn(value == "true"))
                }
                view.postDelayed({
                    // The view may be destroyed (headless teardown) before this fires; there is no
                    // public "is destroyed" check, so runCatching absorbs the post-destroy call.
                    runCatching { view.evaluateJavascript(BridgeScripts.extractionRunner(spec), null) }
                }, SETTLE_MS)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) onEvent(PageEvent.LoadFailed(error.description?.toString()))
            }
        }
    }
}

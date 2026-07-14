package com.shiphappens.source.webview.debug

import kotlin.concurrent.Volatile

/**
 * Observability seam for the web-scraping framework. The scraper and session call these at each
 * lifecycle point; implementations decide what (if anything) to do. This keeps the core scraping
 * logic free of logging and makes tracing a first-class, provider-agnostic feature — any provider
 * added later is traced for free.
 *
 * All methods default to no-ops so implementations override only what they care about, and
 * [NoOpScrapeTracer] needs no body. Callers should still gate expensive argument construction on
 * [WebScrapeDebug.enabled] where it matters (e.g. large response bodies).
 */
interface ScrapeTracer {
    /** A headless scrape began for [url]. */
    fun scrapeStarted(sourceId: String, url: String) {}

    /** The politeness throttle short-circuited this scrape with a cached result — no page load. */
    fun cacheHit(sourceId: String, url: String) {}

    /** The WebView session was configured: which API URL patterns are being captured, and whether
     *  document-start script injection is supported on this device. */
    fun sessionConfigured(sourceId: String, apiUrlPatterns: List<String>, documentStartInjection: Boolean) {}

    /** The page finished loading. Not guaranteed to fire on heavy SPAs. */
    fun pageFinished(sourceId: String, url: String) {}

    /** The logged-in probe evaluated. */
    fun loginState(sourceId: String, loggedIn: Boolean) {}

    /** A page-internal API response matching the provider's patterns was captured. [body] is the
     *  raw JSON — the authoritative tracking data, and what you reverse-engineer a parser from. */
    fun apiCaptured(sourceId: String, url: String?, body: String) {}

    /** The DOM extraction runner posted its result (the canonical extraction JSON, or page:empty). */
    fun domResult(sourceId: String, resultJson: String) {}

    /** A bridge payload could not be decoded. */
    fun payloadUnparseable(sourceId: String, raw: String) {}

    /** The main frame failed to load. */
    fun pageLoadFailed(sourceId: String, message: String?) {}

    /** The scrape finished; [summary] is a human-readable outcome (payload count, routed result,
     *  timeout, whether it was cached). */
    fun scrapeFinished(sourceId: String, summary: String) {}
}

/** Default binding: does nothing, zero overhead. */
object NoOpScrapeTracer : ScrapeTracer

/**
 * Runtime switches for scrape tracing. On Android [enabled] defaults to the app's debuggable flag
 * (see the DI wiring), so tracing is automatic in debug builds and silent in release — and it can
 * be flipped at runtime from a dev menu or a test. [dumpBodiesToFile] additionally writes full
 * captured API bodies to disk (via the platform sink) so a large response can be `adb pull`ed
 * instead of reassembled from Logcat chunks.
 */
object WebScrapeDebug {
    // @Volatile: written once on the main thread during DI graph construction, read on the
    // WebView JavaBridge thread (WebSessions.trace) — the annotation gives the needed
    // happens-before so the bridge thread can't observe a stale value.
    @Volatile var enabled: Boolean = false
    @Volatile var dumpBodiesToFile: Boolean = false
}

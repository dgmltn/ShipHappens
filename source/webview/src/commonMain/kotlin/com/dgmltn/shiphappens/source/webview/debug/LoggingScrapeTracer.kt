package com.dgmltn.shiphappens.source.webview.debug

import co.touchlab.kermit.Logger

/**
 * Kermit-backed [ScrapeTracer]. Every call is gated on [WebScrapeDebug.enabled], so when tracing is
 * off this costs a single boolean check per event. Logs under the "ShipScrape" tag — filter with
 * `adb logcat -s ShipScrape`.
 *
 * Captured API bodies can be tens of KB; Logcat caps a line near 4k chars and does NOT auto-split,
 * so the body is emitted in numbered chunks that reassemble in order. If [WebScrapeDebug.dumpBodiesToFile]
 * is set and a [bodyDumper] was provided (Android wires one that writes to app storage), the full
 * body is also dumped to a file for `adb pull`.
 */
class LoggingScrapeTracer(
    private val bodyDumper: ((sourceId: String, url: String?, body: String) -> Unit)? = null,
) : ScrapeTracer {

    private val log = Logger.withTag(TAG)

    private inline fun trace(block: () -> Unit) {
        if (WebScrapeDebug.enabled) block()
    }

    override fun scrapeStarted(sourceId: String, url: String) = trace { log.i { "[$sourceId] scrape START url=$url" } }

    override fun cacheHit(sourceId: String, url: String) =
        trace { log.i { "[$sourceId] throttle HIT — cached result, no page load" } }

    override fun sessionConfigured(sourceId: String, apiUrlPatterns: List<String>, documentStartInjection: Boolean) =
        trace { log.i { "[$sourceId] configure: apiPatterns=$apiUrlPatterns docStartInjection=$documentStartInjection" } }

    override fun pageFinished(sourceId: String, url: String) = trace { log.i { "[$sourceId] pageFinished url=$url" } }

    override fun hopStarted(sourceId: String, url: String) = trace { log.i { "[$sourceId] goto HOP url=$url" } }

    override fun loginState(sourceId: String, loggedIn: Boolean) = trace { log.i { "[$sourceId] isLoggedIn=$loggedIn" } }

    override fun apiCaptured(sourceId: String, url: String?, body: String) = trace {
        val chunks = body.chunked(CHUNK_CHARS)
        log.i { "[$sourceId] API capture url=$url bodyLen=${body.length} chunks=${chunks.size}" }
        chunks.forEachIndexed { i, c -> log.i { "[$sourceId] API body[$i/${chunks.size}]: $c" } }
        if (WebScrapeDebug.dumpBodiesToFile) bodyDumper?.invoke(sourceId, url, body)
    }

    override fun domResult(sourceId: String, resultJson: String) =
        trace { log.i { "[$sourceId] DOM result: ${resultJson.take(DOM_PREVIEW_CHARS)}" } }

    override fun payloadUnparseable(sourceId: String, raw: String) =
        trace { log.w { "[$sourceId] payload UNPARSEABLE: ${raw.take(PREVIEW_CHARS)}" } }

    override fun pageLoadFailed(sourceId: String, message: String?) =
        trace { log.w { "[$sourceId] mainFrame load error: $message" } }

    override fun scrapeFinished(sourceId: String, summary: String) = trace { log.i { "[$sourceId] scrape DONE: $summary" } }

    private companion object {
        const val TAG = "ShipScrape"
        const val CHUNK_CHARS = 3000
        const val DOM_PREVIEW_CHARS = 1500
        const val PREVIEW_CHARS = 600
    }
}

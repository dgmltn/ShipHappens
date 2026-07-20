package com.dgmltn.shiphappens.source.webview

/** Raw result of one headless (or visible) page scrape: bridge payload JSON strings, in arrival order. */
sealed interface ScrapeResult {
    /** Payloads arrive chronologically; API captures land before the DOM extraction runner's output. */
    data class Payloads(val payloads: List<String>) : ScrapeResult
    data class LoadError(val message: String?) : ScrapeResult
    data object Timeout : ScrapeResult
    data object Unavailable : ScrapeResult
}

interface WebScraper {
    val isAvailable: Boolean
    suspend fun scrape(spec: WebProviderSpec, trackingNumber: String): ScrapeResult
}

/** Platforms without a WebView implementation (iOS/JVM for now; Android until Phase 2). */
object NoWebScraper : WebScraper {
    override val isAvailable = false
    override suspend fun scrape(spec: WebProviderSpec, trackingNumber: String) = ScrapeResult.Unavailable
}

/** Page lifecycle signals shared by the visible WebView composable and the headless scraper. */
sealed interface PageEvent {
    data class Finished(val url: String) : PageEvent
    data class LoggedIn(val loggedIn: Boolean) : PageEvent
    data class LoadFailed(val message: String?) : PageEvent
}

/** Cookie store operations the app needs (login persistence, sign-out). */
interface WebCookieJar {
    fun flush()
    fun clearForDomain(domain: String)
}

object NoOpCookieJar : WebCookieJar {
    override fun flush() {}
    override fun clearForDomain(domain: String) {}
}

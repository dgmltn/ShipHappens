package com.shiphappens.source.webview

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Politeness cache for headless scrapes (spec §6): at most one real page load per key per
 * [minInterval], even on forced refresh. Callers should record ONLY successful results so
 * failures (login wall before the user signs in, transient network) retry without waiting.
 * Not thread-safe by itself — the headless scraper serializes access behind its mutex.
 */
class ScrapeThrottle(
    private val minInterval: Duration = 15.minutes,
    private val now: () -> Instant,
) {
    private data class Entry(val at: Instant, val result: ScrapeResult)
    private val entries = mutableMapOf<String, Entry>()

    fun cached(key: String): ScrapeResult? =
        entries[key]?.takeIf { now() - it.at < minInterval }?.result

    fun record(key: String, result: ScrapeResult) {
        entries[key] = Entry(now(), result)
    }
}

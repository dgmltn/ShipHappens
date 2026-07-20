package com.dgmltn.shiphappens.source.webview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class ScrapeThrottleTest {
    private var nowMs = 0L
    private val throttle = ScrapeThrottle(minInterval = 15.minutes, now = { Instant.fromEpochMilliseconds(nowMs) })
    private val result = ScrapeResult.Payloads(listOf("p"))

    @Test fun empty_throttle_has_no_cached_result() {
        assertNull(throttle.cached("url1"))
    }

    @Test fun within_interval_returns_cached_result() {
        throttle.record("url1", result)
        nowMs = 14 * 60 * 1000
        assertEquals(result, throttle.cached("url1"))
        assertNull(throttle.cached("url2"))  // per-key
    }

    @Test fun after_interval_cache_expires() {
        throttle.record("url1", result)
        nowMs = 15 * 60 * 1000
        assertNull(throttle.cached("url1"))
    }

    @Test fun rerecording_resets_the_window() {
        throttle.record("url1", result)
        nowMs = 10 * 60 * 1000
        throttle.record("url1", result)
        nowMs = 20 * 60 * 1000
        assertEquals(result, throttle.cached("url1"))
    }
}

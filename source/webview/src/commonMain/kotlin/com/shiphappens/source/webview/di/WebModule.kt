package com.shiphappens.source.webview.di

import org.koin.core.module.Module

/**
 * Binds WebScraper and WebCookieJar for the current platform. Android provides real
 * implementations (headless scraper in Phase 2); iOS/JVM bind no-ops so web sources
 * report implemented = false and the registry skips them.
 */
expect fun platformWebModule(): Module

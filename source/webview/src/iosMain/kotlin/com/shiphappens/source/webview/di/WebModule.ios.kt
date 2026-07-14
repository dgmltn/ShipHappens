package com.shiphappens.source.webview.di

import com.shiphappens.source.webview.NoOpCookieJar
import com.shiphappens.source.webview.NoWebScraper
import com.shiphappens.source.webview.WebCookieJar
import com.shiphappens.source.webview.WebScraper
import com.shiphappens.source.webview.debug.NoOpScrapeTracer
import com.shiphappens.source.webview.debug.ScrapeTracer
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformWebModule(): Module = module {
    single<WebScraper> { NoWebScraper }
    single<WebCookieJar> { NoOpCookieJar }
    single<ScrapeTracer> { NoOpScrapeTracer }
}

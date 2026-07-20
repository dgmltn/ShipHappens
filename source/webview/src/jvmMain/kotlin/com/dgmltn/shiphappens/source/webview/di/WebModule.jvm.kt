package com.dgmltn.shiphappens.source.webview.di

import com.dgmltn.shiphappens.source.webview.NoOpCookieJar
import com.dgmltn.shiphappens.source.webview.NoWebScraper
import com.dgmltn.shiphappens.source.webview.WebCookieJar
import com.dgmltn.shiphappens.source.webview.WebScraper
import com.dgmltn.shiphappens.source.webview.debug.NoOpScrapeTracer
import com.dgmltn.shiphappens.source.webview.debug.ScrapeTracer
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformWebModule(): Module = module {
    single<WebScraper> { NoWebScraper }
    single<WebCookieJar> { NoOpCookieJar }
    single<ScrapeTracer> { NoOpScrapeTracer }
}

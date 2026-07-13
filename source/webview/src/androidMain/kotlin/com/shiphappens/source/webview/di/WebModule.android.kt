package com.shiphappens.source.webview.di

import com.shiphappens.source.webview.AndroidWebCookieJar
import com.shiphappens.source.webview.HeadlessWebViewScraper
import com.shiphappens.source.webview.ScrapeThrottle
import com.shiphappens.source.webview.WebCookieJar
import com.shiphappens.source.webview.WebScraper
import kotlin.time.Clock
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformWebModule(): Module = module {
    single { ScrapeThrottle(now = { Clock.System.now() }) }
    single<WebScraper> { HeadlessWebViewScraper(androidContext(), get()) }
    single<WebCookieJar> { AndroidWebCookieJar() }
}

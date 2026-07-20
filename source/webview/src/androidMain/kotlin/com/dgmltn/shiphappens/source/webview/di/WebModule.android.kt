package com.dgmltn.shiphappens.source.webview.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.dgmltn.shiphappens.source.webview.AndroidWebCookieJar
import com.dgmltn.shiphappens.source.webview.HeadlessWebViewScraper
import com.dgmltn.shiphappens.source.webview.ScrapeThrottle
import com.dgmltn.shiphappens.source.webview.WebCookieJar
import com.dgmltn.shiphappens.source.webview.WebScraper
import com.dgmltn.shiphappens.source.webview.debug.LoggingScrapeTracer
import com.dgmltn.shiphappens.source.webview.debug.ScrapeTracer
import com.dgmltn.shiphappens.source.webview.debug.WebScrapeDebug
import java.io.File
import kotlin.time.Clock
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformWebModule(): Module = module {
    single { ScrapeThrottle(now = { Clock.System.now() }) }
    single<ScrapeTracer> {
        val ctx: Context = androidContext()
        // Default scrape tracing to the app's debuggable flag: on in debug builds, silent in
        // release. Still flippable at runtime via WebScrapeDebug.enabled (e.g. a dev-menu toggle).
        WebScrapeDebug.enabled = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        LoggingScrapeTracer(bodyDumper = { sourceId, _, body -> dumpBody(ctx, sourceId, body) })
    }
    single<WebScraper> { HeadlessWebViewScraper(androidContext(), get(), get()) }
    single<WebCookieJar> { AndroidWebCookieJar() }
}

/** Writes a full captured API body to app-scoped external storage for `adb pull`. Best-effort;
 *  only reached when WebScrapeDebug.dumpBodiesToFile is set. */
private fun dumpBody(context: Context, sourceId: String, body: String) {
    runCatching {
        val dir = File(context.getExternalFilesDir(null), "scrape-debug").apply { mkdirs() }
        // No timestamp source in common code paths; System.currentTimeMillis is fine here (Android-only).
        File(dir, "$sourceId-${System.currentTimeMillis()}.json").writeText(body)
    }
}

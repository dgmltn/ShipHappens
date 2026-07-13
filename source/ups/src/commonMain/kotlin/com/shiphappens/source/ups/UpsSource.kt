package com.shiphappens.source.ups

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.domain.normalizeTracking
import com.shiphappens.source.api.TrackingSource
import com.shiphappens.source.webview.WebScraper
import com.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** UPS via ups.com in a WebView — see docs/superpowers/specs/2026-07-12-webview-tracking-source-design.md. */
class UpsWebSource(scraper: WebScraper) : WebViewBasedSource(UpsWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? =
        WellKnownCarriers.UPS.takeIf { Regex("^1Z[0-9A-Z]{10,}$").matches(normalizeTracking(trackingNumber)) }
}

val upsSourceModule: Module = module { single { UpsWebSource(get()) } bind TrackingSource::class }

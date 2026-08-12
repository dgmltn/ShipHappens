package com.dgmltn.shiphappens.source.amzl

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.api.TrackingSource
import com.dgmltn.shiphappens.source.webview.WebScraper
import com.dgmltn.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Amazon Logistics via track.amazon.com — see docs/superpowers/specs/2026-08-11-amzl-source-design.md. */
class AmzlWebSource(scraper: WebScraper) : WebViewBasedSource(AmzlWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? =
        WellKnownCarriers.AMAZON_LOGISTICS.takeIf {
            Regex("^TBA\\d{9,15}$").matches(normalizeTracking(trackingNumber))
        }
}

val amzlSourceModule: Module = module { single { AmzlWebSource(get()) } bind TrackingSource::class }

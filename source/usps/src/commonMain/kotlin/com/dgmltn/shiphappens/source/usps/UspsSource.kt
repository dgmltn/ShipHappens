package com.dgmltn.shiphappens.source.usps

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.api.TrackingSource
import com.dgmltn.shiphappens.source.webview.WebScraper
import com.dgmltn.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** USPS via tools.usps.com in a WebView — see docs/superpowers/specs/2026-07-14-usps-webview-source-design.md. */
class UspsWebSource(scraper: WebScraper) : WebViewBasedSource(UspsWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? {
        val normalized = normalizeTracking(trackingNumber)
        return WellKnownCarriers.USPS.takeIf {
            Regex("^(94|93|92|95|82)\\d{14,24}$").matches(normalized) ||
                Regex("^[A-Z]{2}\\d{9}US$").matches(normalized)
        }
    }
}

val uspsSourceModule: Module = module { single { UspsWebSource(get()) } bind TrackingSource::class }

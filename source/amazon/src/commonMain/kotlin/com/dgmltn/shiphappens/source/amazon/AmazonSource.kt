package com.dgmltn.shiphappens.source.amazon

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.api.TrackingSource
import com.dgmltn.shiphappens.source.webview.WebScraper
import com.dgmltn.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

/** Amazon orders via the logged-in amazon.com order pages — see docs/superpowers/specs/2026-07-15-amazon-webview-source-design.md. */
class AmazonWebSource(scraper: WebScraper) : WebViewBasedSource(AmazonWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? =
        // Order ids display as 3-7-7 digits (113-1234567-1234567) but normalizeTracking strips
        // the hyphens, so match the normalized 17-digit form. US order ids start with 1 or 7,
        // which keeps 17-digit numbers of other carriers from false-matching.
        WellKnownCarriers.AMAZON.takeIf { Regex("^[17]\\d{16}$").matches(normalizeTracking(trackingNumber)) }
}

val amazonSourceModule: Module = module { single { AmazonWebSource(get()) } bind TrackingSource::class }

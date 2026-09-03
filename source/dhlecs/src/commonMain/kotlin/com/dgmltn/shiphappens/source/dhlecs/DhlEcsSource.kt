package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.api.TrackingSource
import com.dgmltn.shiphappens.source.webview.WebScraper
import com.dgmltn.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

// Only the 420+ZIP-prefixed IMpb form — the bare 22-digit body stays USPS's (last mile;
// tools.usps.com tracks it too). Mirrors BuiltInCarrierDetection.
private val DHLECS_NUMBER = Regex("^420\\d{5}9\\d{21,25}$")

/** DHL eCommerce via webtrack.dhlecs.com in a WebView (anonymous tracker API, AMZL-style). */
class DhlEcsWebSource(scraper: WebScraper) : WebViewBasedSource(DhlEcsWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? =
        WellKnownCarriers.DHL_ECOMMERCE.takeIf { DHLECS_NUMBER.matches(normalizeTracking(trackingNumber)) }
}

val dhlEcsSourceModule: Module = module { single { DhlEcsWebSource(get()) } bind TrackingSource::class }

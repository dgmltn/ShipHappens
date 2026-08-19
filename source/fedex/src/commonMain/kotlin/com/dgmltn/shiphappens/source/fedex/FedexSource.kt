package com.dgmltn.shiphappens.source.fedex

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.api.TrackingSource
import com.dgmltn.shiphappens.source.webview.WebScraper
import com.dgmltn.shiphappens.source.webview.WebViewBasedSource
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.module

private val FEDEX_FORMATS = Regex("^\\d{12}$|^\\d{15}$|^\\d{20,22}$")

// 20-22 digit numbers with a USPS service prefix are USPS labels (FedEx Ground Economy hands
// those to USPS); claiming them here would make resolution depend on source registration order.
private val USPS_PREFIXED = Regex("^(94|93|92|95|82)\\d{14,24}$")

/** FedEx via fedex.com/fedextrack in a WebView — parallel to UpsWebSource/UspsWebSource. */
class FedexWebSource(scraper: WebScraper) : WebViewBasedSource(FedexWebSpec, scraper) {
    override fun detectCarrier(trackingNumber: String): Carrier? {
        val normalized = normalizeTracking(trackingNumber)
        return WellKnownCarriers.FEDEX.takeIf {
            FEDEX_FORMATS.matches(normalized) && !USPS_PREFIXED.matches(normalized)
        }
    }
}

val fedexSourceModule: Module = module { single { FedexWebSource(get()) } bind TrackingSource::class }

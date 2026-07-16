package com.shiphappens.source.webview

import com.shiphappens.domain.TrackingSnapshot
import com.shiphappens.domain.Carrier
import com.shiphappens.source.api.FailureReason
import com.shiphappens.source.api.SourceDescriptor
import com.shiphappens.source.api.SourceKind
import com.shiphappens.source.api.SourceResult
import com.shiphappens.source.api.TrackingSource

/** Marker capability: lets UI find the web recipe for a carrier (More-details screen, login). */
interface WebCapableSource {
    val webSpec: WebProviderSpec
}

/**
 * A TrackingSource whose data comes from driving the carrier's own website. Subclasses supply
 * only [detectCarrier]; everything else derives from the [webSpec] recipe. No credential fields —
 * auth is an optional cookie session established in the login WebView.
 */
abstract class WebViewBasedSource(
    final override val webSpec: WebProviderSpec,
    private val scraper: WebScraper,
) : TrackingSource, WebCapableSource {

    final override val descriptor = SourceDescriptor(
        id = webSpec.sourceId,
        displayName = webSpec.carrier.displayName,
        kind = SourceKind.CARRIER,
        accentColorHex = webSpec.carrier.accentColorHex,
        implemented = scraper.isAvailable,
    )

    abstract override fun detectCarrier(trackingNumber: String): Carrier?

    final override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val name = webSpec.carrier.displayName
        if (!scraper.isAvailable) {
            return SourceResult.Failure(FailureReason.UNKNOWN, "$name web tracking isn't available on this platform yet")
        }
        return when (val result = scraper.scrape(webSpec, trackingNumber)) {
            is ScrapeResult.Payloads -> {
                val router = PayloadRouter(webSpec)
                val routed = result.payloads.map(router::route)
                routed.firstNotNullOfOrNull { (it as? RouteResult.Tracking)?.tracking }
                    ?.let { return SourceResult.Success(it.toSnapshot()) }
                // A goto hop's embedded coarse tracking is the designed fallback when the hop's target
                // page never produced a rich extraction (design spec §1) — and it outranks the error
                // ladder because the page that emitted it was already past login and order lookup.
                routed.firstNotNullOfOrNull { (it as? RouteResult.Goto)?.tracking }
                    ?.let { return SourceResult.Success(it.toSnapshot()) }
                when {
                    routed.any { it is RouteResult.LoginWall } ->
                        SourceResult.Failure(FailureReason.AUTH, "Sign in to $name in Settings, then refresh")
                    routed.any { it is RouteResult.Challenge } ->
                        SourceResult.Failure(FailureReason.RATE_LIMITED, "$name wants a human check — open More details to continue")
                    routed.any { it is RouteResult.NotFound } ->
                        SourceResult.Failure(FailureReason.NOT_FOUND, "$name doesn't recognize this number")
                    else -> SourceResult.Failure(FailureReason.UNKNOWN, "Couldn't read tracking data from $name")
                }
            }
            is ScrapeResult.LoadError -> SourceResult.Failure(FailureReason.NETWORK, result.message)
            ScrapeResult.Timeout -> SourceResult.Failure(FailureReason.NETWORK, "Timed out loading $name")
            ScrapeResult.Unavailable -> SourceResult.Failure(FailureReason.UNKNOWN, "$name web tracking unavailable")
        }
    }
}

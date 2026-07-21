package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.source.api.FailureReason
import com.dgmltn.shiphappens.source.api.SourceDescriptor
import com.dgmltn.shiphappens.source.api.SourceResult
import com.dgmltn.shiphappens.source.api.TrackingSource

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
                routed.firstRichTracking()?.let { return SourceResult.Success(it.toSnapshot()) }
                // A goto hop's embedded coarse tracking is the designed fallback when the hop's target
                // page never produced a rich extraction (design spec §1) — and it outranks the error
                // ladder because the page that emitted it was already past login and order lookup.
                routed.firstCoarseTracking()?.let { return SourceResult.Success(it.toSnapshot()) }
                when {
                    routed.has<RouteResult.LoginWall>() ->
                        SourceResult.Failure(FailureReason.AUTH, "Sign in to $name in Settings, then refresh")
                    routed.has<RouteResult.Challenge>() ->
                        SourceResult.Failure(FailureReason.RATE_LIMITED, "$name wants a human check — open More details to continue")
                    routed.has<RouteResult.NotFound>() ->
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

/*
 * These three read like `firstNotNullOfOrNull { (it as? T)?.tracking }` and `any { it is T }` written
 * the long way, and that is deliberate. Kotlin/Native 2.4.0 miscompiles those inline-lambda forms when
 * they scan this list inside `track`: the "found nothing" path yields an uninitialized reference rather
 * than null, the null check passes, and `toSnapshot()` then segfaults on a garbage pointer
 * (failure_taxonomy_mapping, iosSimulatorArm64 only — the JVM target is fine). Plain iterator loops
 * compile correctly. Revisit when the Kotlin version is bumped; see WebViewBasedSourceTest.
 */

private fun List<RouteResult>.firstRichTracking(): ScrapedTracking? {
    for (r in this) if (r is RouteResult.Tracking) return r.tracking
    return null
}

private fun List<RouteResult>.firstCoarseTracking(): ScrapedTracking? {
    for (r in this) {
        if (r is RouteResult.Goto) {
            val t = r.tracking
            if (t != null) return t
        }
    }
    return null
}

private inline fun <reified T : RouteResult> List<RouteResult>.has(): Boolean {
    for (r in this) if (r is T) return true
    return false
}

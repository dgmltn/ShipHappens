package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot
import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.normalizeTracking
import com.dgmltn.shiphappens.source.api.FailureReason
import com.dgmltn.shiphappens.source.api.SourceDescriptor
import com.dgmltn.shiphappens.source.api.SourceResult
import com.dgmltn.shiphappens.source.api.TrackingSource
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/** Marker capability: lets UI find the web recipe for a carrier (More-details screen, login). */
interface WebCapableSource {
    val webSpec: WebProviderSpec
}

/**
 * A TrackingSource whose data comes from driving the carrier's own website. Everything derives
 * from the [webSpec] recipe: identity from the carrier, detection from the carrier's number
 * pattern, scraping from the spec. No credential fields — auth is an optional cookie session
 * established in the login WebView.
 */
class WebSource(
    override val webSpec: WebProviderSpec,
    private val scraper: WebScraper,
) : TrackingSource, WebCapableSource {

    override val descriptor = SourceDescriptor(
        id = webSpec.sourceId,
        displayName = webSpec.carrier.displayName,
        accentColorHex = webSpec.carrier.accentColorHex,
        implemented = scraper.isAvailable,
    )

    override fun detectCarrier(trackingNumber: String): Carrier? =
        webSpec.carrier.takeIf { it.claims(normalizeTracking(trackingNumber)) }

    override suspend fun track(trackingNumber: String, carrier: Carrier?): SourceResult<TrackingSnapshot> {
        val name = webSpec.carrier.displayName
        if (!scraper.isAvailable) {
            return SourceResult.Failure(FailureReason.UNKNOWN, "$name web tracking isn't available on this platform yet")
        }
        return when (val result = scraper.scrape(webSpec, trackingNumber)) {
            is ScrapeResult.Payloads -> {
                val router = PayloadRouter(webSpec)
                val routed = result.payloads.map(router::route)
                // A goto hop's embedded coarse tracking is the designed fallback when the hop's target
                // page never produced a rich extraction (design spec §1) — and it outranks the error
                // ladder because the page that emitted it was already past login and order lookup. It
                // also backfills a rich result that landed on an impoverished tracker page (UNKNOWN,
                // no ETA), so that page can't blank an ETA the order page already knew.
                val coarse = routed.firstCoarseTracking()
                routed.firstRichTracking()?.let { return SourceResult.Success(it.backfilledFrom(coarse)) }
                coarse?.let { return SourceResult.Success(it) }
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

/**
 * Koin registration for one carrier. Qualified by source id so six WebSource singles coexist;
 * `bind` keeps them all visible to `getAll<TrackingSource>()`, which SourceRegistry is built from.
 */
fun webSourceModule(spec: WebProviderSpec): Module = module {
    single(named(spec.sourceId)) { WebSource(spec, get()) } bind TrackingSource::class
}

/*
 * These three read like `firstNotNullOfOrNull { (it as? T)?.tracking }` and `any { it is T }` written
 * the long way, and that is deliberate. Kotlin/Native 2.4.0 miscompiles those inline-lambda forms when
 * they scan this list inside `track`: the "found nothing" path yields an uninitialized reference rather
 * than null, the null check passes, and dereferencing it then segfaults on a garbage pointer
 * (failure_taxonomy_mapping, iosSimulatorArm64 only — the JVM target is fine). Plain iterator loops
 * compile correctly. Revisit when the Kotlin version is bumped; see WebSourceTest.
 */

private fun List<RouteResult>.firstRichTracking(): TrackingSnapshot? {
    for (r in this) if (r is RouteResult.Tracking) return r.snapshot
    return null
}

private fun List<RouteResult>.firstCoarseTracking(): TrackingSnapshot? {
    for (r in this) {
        if (r is RouteResult.Goto) {
            val t = r.coarse
            if (t != null) return t
        }
    }
    return null
}

private inline fun <reified T : RouteResult> List<RouteResult>.has(): Boolean {
    for (r in this) if (r is T) return true
    return false
}

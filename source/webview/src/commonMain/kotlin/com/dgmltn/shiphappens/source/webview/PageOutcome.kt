package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingSnapshot

/**
 * What a carrier's Kotlin decided about a page. Produced by [WebProviderSpec.parseRaw] from a
 * `page:'raw'` extraction and never serialized — the JS bridge only ever carries [DomRaw].
 * [PayloadRouter] turns it into a [RouteResult], which is where the hop-URL rule is enforced;
 * a provider cannot bypass that by constructing a [Goto].
 */
sealed interface PageOutcome {
    data class Tracking(val snapshot: TrackingSnapshot) : PageOutcome
    /** One-hop navigation, optionally carrying a coarse snapshot read from the page that asked for it. */
    data class Goto(val url: String, val coarse: TrackingSnapshot?) : PageOutcome
    data object NotFound : PageOutcome
    data object LoginWall : PageOutcome
    data object Challenge : PageOutcome
    data object Empty : PageOutcome
}

fun PageOutcome?.snapshotOrNull(): TrackingSnapshot? = (this as? PageOutcome.Tracking)?.snapshot

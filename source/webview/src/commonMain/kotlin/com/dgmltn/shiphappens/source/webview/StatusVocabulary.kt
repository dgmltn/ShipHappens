package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingStatus

/**
 * The carrier-neutral status vocabulary, shared by every provider whose input is a human
 * sentence (carrier tracker banners, scan-event descriptions). Providers merge their own
 * wordings in via [StatusKeywords] and one precedence chain evaluates the union — never
 * compose as `local() ?: shared()`, which lets a late-stage local keyword ("processed")
 * shadow an earlier shared match ("out for delivery") in the same text.
 *
 * Deliberately NOT used by Amazon's order pages: an order page mixes shipment cards with
 * RMA/returns copy, so bare substrings like "return" and "attempt" — safe on carrier tracker
 * pages, and matching pre-consolidation behavior there — would misclassify (the 2026-07-19
 * QA bug). AmzlApiParser classifies API tokens, not sentences, and keeps its own table too.
 */

/** Per-status keyword extensions a provider merges into the shared vocabulary. Lowercase. */
data class StatusKeywords(
    val outForDelivery: List<String> = emptyList(),
    val delivered: List<String> = emptyList(),
    val exception: List<String> = emptyList(),
    val labelCreated: List<String> = emptyList(),
    val shipped: List<String> = emptyList(),
    val inTransit: List<String> = emptyList(),
    /** Delay wordings. Orthogonal to the stage keywords above — see [isDelayedWording]. */
    val delayed: List<String> = emptyList(),
)

private fun normalizeWording(raw: String?): String =
    raw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

private val OUT_FOR_DELIVERY = listOf("out for delivery")
private val DELIVERED = listOf("delivered")
private val EXCEPTION = listOf(
    "exception", "attempt", "action required", "notice left", "held",
    "unable to deliver", "return",
)
private val LABEL_CREATED = listOf("label created")
private val SHIPPED = listOf("picked up")
private val IN_TRANSIT = listOf("in transit", "on the way", "on its way", "departed", "arrived")
private val DELAYED = listOf("delay")

/**
 * Maps a status wording to a status, or null when the text isn't recognizable — null is the
 * caller's cue to fall back (newest classifiable event, coarse page state), never a guess.
 * Chain order is load-bearing: exception wordings ("delivery exception", "delivery attempted")
 * contain delivery-ish substrings, so OFD/DELIVERED match on their exact phrases first.
 */
fun classifyStatusWording(raw: String?, extras: StatusKeywords = StatusKeywords()): TrackingStatus? {
    val t = normalizeWording(raw)
    if (t.isEmpty()) return null
    fun hit(shared: List<String>, extra: List<String>) = shared.any { it in t } || extra.any { it in t }
    return when {
        hit(OUT_FOR_DELIVERY, extras.outForDelivery) -> TrackingStatus.OUT_FOR_DELIVERY
        hit(DELIVERED, extras.delivered) -> TrackingStatus.DELIVERED
        hit(EXCEPTION, extras.exception) -> TrackingStatus.EXCEPTION
        hit(LABEL_CREATED, extras.labelCreated) -> TrackingStatus.LABEL_CREATED
        hit(SHIPPED, extras.shipped) -> TrackingStatus.SHIPPED
        hit(IN_TRANSIT, extras.inTransit) -> TrackingStatus.IN_TRANSIT
        // Note there is deliberately no delay branch: a delay is a modifier, not a stage (see
        // [isDelayedWording]). "On the Way: Delayed" classifies IN_TRANSIT on its stage wording
        // above, while a bare "Delivery updated - delay" names no stage and so answers null —
        // a package can be late before it ships, late in transit, or late out for delivery, and
        // picking one of those would be a guess. Null routes the caller to the event rows or the
        // stored status; the delay still reports separately.
        else -> null
    }
}

/**
 * True when a wording names two or more DIFFERENT stages — the signature of a scraped progress
 * rail, whose step labels are all in the DOM regardless of the package's actual state (the dhlecs
 * details route concatenates "Notified En Route Delivered" after the real status; live QA
 * 2026-09-03 saw it classify a label-only package DELIVERED). No genuine single-status sentence
 * names two stages, so a DOM fallback should refuse such text instead of letting the precedence
 * chain pick whichever stage matches first. Delay wordings don't count: a delay is a modifier,
 * not a stage.
 */
fun isMultiStageWording(raw: String?, extras: StatusKeywords = StatusKeywords()): Boolean {
    val t = normalizeWording(raw)
    if (t.isEmpty()) return false
    fun hit(shared: List<String>, extra: List<String>) = shared.any { it in t } || extra.any { it in t }
    val stages = listOf(
        hit(OUT_FOR_DELIVERY, extras.outForDelivery),
        hit(DELIVERED, extras.delivered),
        hit(EXCEPTION, extras.exception),
        hit(LABEL_CREATED, extras.labelCreated),
        hit(SHIPPED, extras.shipped),
        hit(IN_TRANSIT, extras.inTransit),
    )
    return stages.count { it } >= 2
}

/**
 * Whether a wording reports a delay — asked independently of [classifyStatusWording], because a
 * delay is orthogonal to the stage: a package can be in transit and late, out for delivery and
 * late, or an exception and late. Callers persist the answer as a note alongside the status
 * rather than collapsing the two into one field.
 */
fun isDelayedWording(raw: String?, extras: StatusKeywords = StatusKeywords()): Boolean {
    val t = normalizeWording(raw)
    if (t.isEmpty()) return false
    return DELAYED.any { it in t } || extras.delayed.any { it in t }
}

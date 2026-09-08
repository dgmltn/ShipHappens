package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingStatus

// The carrier-neutral status vocabulary, shared by every provider whose input is a human
// sentence (carrier tracker banners, scan-event descriptions) or an API token (AMZL, via
// classifyToken). Providers merge their own wordings in via [StatusKeywords] and one
// precedence chain evaluates the union — never compose as `local() ?: shared()`, which lets a
// late-stage local keyword ("processed") shadow an earlier shared match ("out for delivery")
// in the same text.
//
// [BaseKeywords.None] is for pages that mix shipment copy with unrelated copy: Amazon's order
// pages carry RMA/returns cards, where the shared base's bare "return" and "attempt" would
// misclassify a completed replacement as an exception (QA 2026-07-19). Amazon builds its
// vocabulary as `StatusVocabulary(..., base = BaseKeywords.None)` and merges only its own
// wordings in. [classifyToken] reads CamelCase / SCREAMING_SNAKE API codes ("InTransitDelayed",
// "OUT_FOR_DELIVERY") as words before running them through the same chain — how AMZL classifies
// its API tokens.

/** Per-status keyword extensions a provider merges into the shared vocabulary. Lowercase. */
data class StatusKeywords(
    val outForDelivery: List<String> = emptyList(),
    val delivered: List<String> = emptyList(),
    val exception: List<String> = emptyList(),
    val labelCreated: List<String> = emptyList(),
    val shipped: List<String> = emptyList(),
    val inTransit: List<String> = emptyList(),
    /** Delay wordings. Orthogonal to the stage keywords above — see [StatusVocabulary.isDelayed]. */
    val delayed: List<String> = emptyList(),
)

/** Which carrier-neutral phrases a vocabulary starts from. */
enum class BaseKeywords {
    /** Tracker-page English every carrier prints ("out for delivery", "picked up", "in transit"…). */
    Carrier,
    /**
     * Nothing shared: only the carrier's own phrases. For pages that mix shipment copy with
     * unrelated copy — Amazon's order pages carry RMA/returns cards, where the base's bare
     * "return" and "attempt" misclassified a completed replacement as an exception (QA 2026-07-19).
     */
    None,
}

private fun normalizeWording(raw: String?): String =
    raw?.lowercase()?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

private val CARRIER_BASE = StatusKeywords(
    outForDelivery = listOf("out for delivery"),
    delivered = listOf("delivered"),
    exception = listOf(
        "exception", "attempt", "action required", "notice left", "held",
        "unable to deliver", "return",
    ),
    labelCreated = listOf("label created"),
    shipped = listOf("picked up"),
    inTransit = listOf("in transit", "on the way", "on its way", "departed", "arrived"),
    delayed = listOf("delay"),
)

/**
 * Wordings that contain "delivered" and negate it. Evaluated on every vocabulary, whatever
 * its base: they suppress the DELIVERED lane and classify EXCEPTION — see [NOT_SHIPPED] for the
 * equivalent guard on SHIPPED. Before this lane existed, DHL eCommerce guarded "Undelivered -
 * Processes for Local Disposal" from outside the chain and AMZL reordered its own copy of the
 * chain for the same reason.
 */
private val NOT_DELIVERED = listOf("undelivered", "not delivered")

/** Wordings that contain "shipped" and negate it. Like [NOT_DELIVERED], evaluated on every
 *  vocabulary: they suppress the SHIPPED lane and count as LABEL_CREATED. */
private val NOT_SHIPPED = listOf("not yet shipped", "not shipped", "hasn't shipped", "has not shipped")

private operator fun StatusKeywords.plus(o: StatusKeywords) = StatusKeywords(
    outForDelivery = outForDelivery + o.outForDelivery,
    delivered = delivered + o.delivered,
    exception = exception + o.exception,
    labelCreated = labelCreated + o.labelCreated,
    shipped = shipped + o.shipped,
    inTransit = inTransit + o.inTransit,
    delayed = delayed + o.delayed,
)

/**
 * A carrier's status vocabulary: the shared carrier-neutral phrases (or none) plus the
 * carrier's own, evaluated by one precedence chain. Never compose vocabularies as
 * `local() ?: shared()` — a late-stage local keyword ("processed") would shadow an earlier
 * shared match ("out for delivery") in the same text; merging the keyword lists and running
 * one chain is what keeps precedence right.
 */
class StatusVocabulary(
    extras: StatusKeywords = StatusKeywords(),
    base: BaseKeywords = BaseKeywords.Carrier,
) {
    private val words: StatusKeywords = when (base) {
        BaseKeywords.Carrier -> CARRIER_BASE + extras
        BaseKeywords.None -> extras
    }

    /**
     * Every stage the text names, in precedence order. Chain order is load-bearing: exception
     * wordings ("delivery exception", "delivery attempted") contain delivery-ish substrings, so
     * OUT_FOR_DELIVERY and DELIVERED match on their exact phrases first. There is deliberately
     * no delay lane — a delay is a modifier, not a stage (see [isDelayed]).
     */
    private fun stages(t: String): List<TrackingStatus> {
        fun hit(phrases: List<String>) = phrases.any { it in t }
        val negated = hit(NOT_DELIVERED)
        val negatedShipped = hit(NOT_SHIPPED)
        return buildList {
            if (hit(words.outForDelivery)) add(TrackingStatus.OUT_FOR_DELIVERY)
            if (!negated && hit(words.delivered)) add(TrackingStatus.DELIVERED)
            if (negated || hit(words.exception)) add(TrackingStatus.EXCEPTION)
            if (negatedShipped || hit(words.labelCreated)) add(TrackingStatus.LABEL_CREATED)
            if (!negatedShipped && hit(words.shipped)) add(TrackingStatus.SHIPPED)
            if (hit(words.inTransit)) add(TrackingStatus.IN_TRANSIT)
        }
    }

    /** The stage a wording names, or null when it isn't recognizable — the caller's cue to fall
     *  back (newest classifiable event, coarse page state), never a guess. */
    fun classify(text: String?): TrackingStatus? {
        val t = normalizeWording(text)
        if (t.isEmpty()) return null
        return stages(t).firstOrNull()
    }

    /** Whether a wording reports a delay, asked independently of [classify] because a delay is
     *  orthogonal to the stage: in transit and late, out for delivery and late, an exception and late. */
    fun isDelayed(text: String?): Boolean {
        val t = normalizeWording(text)
        return t.isNotEmpty() && words.delayed.any { it in t }
    }

    /**
     * True when a wording names two or more DIFFERENT stages — the signature of a scraped
     * progress rail, whose step labels are all in the DOM regardless of the package's state
     * (dhlecs live QA 2026-09-03 classified a label-only package DELIVERED off "Notified En Route
     * Delivered"). No genuine single-status sentence names two stages, so a DOM reader should
     * refuse such text instead of letting the chain pick whichever stage matches first.
     */
    fun isMultiStage(text: String?): Boolean {
        val t = normalizeWording(text)
        return t.isNotEmpty() && stages(t).size >= 2
    }

    /** An API code ("OutForDelivery", "READY_FOR_RECEIVE") classified by the same chain after [humanizeToken]. */
    fun classifyToken(token: String?): TrackingStatus? = classify(humanizeToken(token))

    fun isDelayedToken(token: String?): Boolean = isDelayed(humanizeToken(token))
}

/** "OutForDelivery" → "out for delivery"; "READY_FOR_RECEIVE" → "ready for receive". */
fun humanizeToken(token: String?): String =
    (token ?: "")
        .replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ")
        .replace('_', ' ')
        .lowercase()
        .trim()

package com.dgmltn.shiphappens.source.webview

import com.dgmltn.shiphappens.domain.TrackingEvent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Not-found wordings any carrier's tracker page might print, per the boundary rule: generic
 * English, each first validated live on some carrier. Carrier-specific copy ("problem finding
 * this order") stays in that carrier's [TrackerPageRules.notFound].
 */
private val SHARED_NOT_FOUND = listOf(
    """can.t find (that|this) tracking number""",
    """couldn.t find""",
    """unable to find""",
    """invalid tracking""",
    """no record of this tracking""",
    """could not locate the tracking""",
)

/**
 * Everything one carrier declares about reading its tracker page. Everything else — the
 * not-found check, the empty check, the multi-stage refusal, event conversion, the fallback
 * ladder — is [resolveTrackerPage]'s, so a fix there reaches every carrier.
 */
class TrackerPageRules(
    val vocabulary: StatusVocabulary,
    /** Regex sources (case-insensitive) matched against [DomRaw.pageText], merged with the shared list. */
    val notFound: List<String> = emptyList(),
    /** Reads the delivery promise out of [DomRaw.etaText]. Override only when the page needs a gate first. */
    val etaDate: (text: String?, today: LocalDate?) -> LocalDate? = ::parsePromiseDate,
    /** The current-location banner; override to strip page phrasing ("Currently in"). */
    val location: (DomRaw) -> String? = { it.locationText },
    val delayNote: (headline: String?, events: List<TrackingEvent>) -> String? =
        { headline, events -> headlineThenNewestEvent(vocabulary, headline, events) },
) {
    internal val notFoundPatterns: List<Regex> = (SHARED_NOT_FOUND + notFound).map { Regex(it, RegexOption.IGNORE_CASE) }
}

/**
 * The delay note for a page: the headline when it reports the delay, else the newest event(s)
 * when one of those does. Only the newest, because delay events stay in the history for the
 * life of the shipment and non-null delayNote IS the delay flag — scanning older rows would keep
 * a delivered package flagged forever. Several rows can share the newest timestamp (a scrape can
 * capture more than one row per instant); any of them being delayed counts.
 */
fun headlineThenNewestEvent(vocabulary: StatusVocabulary, headline: String?, events: List<TrackingEvent>): String? {
    headline?.takeIf { vocabulary.isDelayed(it) }?.let { return it }
    val newest = events.maxOfOrNull { it.timestamp } ?: return null
    return events.filter { it.timestamp == newest }
        .firstOrNull { vocabulary.isDelayed(it.description) }?.description
}

/**
 * Reads a tracker page's [DomRaw] under [rules]. Null for a foreign [DomRaw.kind] or a
 * multi-stage headline (routes to Unparsed, so nothing is persisted and the API capture on the
 * same page stays the only writer); [PageOutcome.NotFound] when any not-found pattern matches;
 * [PageOutcome.Empty] when there is no headline, no event, and no promise; otherwise a
 * [PageOutcome.Tracking] assembled by the shared ladder.
 */
fun resolveTrackerPage(
    raw: DomRaw,
    rules: TrackerPageRules,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): PageOutcome? {
    if (raw.kind != "tracker") return null
    val pageText = raw.pageText
    if (pageText != null && rules.notFoundPatterns.any { it.containsMatchIn(pageText) }) return PageOutcome.NotFound
    val headline = raw.statusText?.takeIf { it.isNotBlank() }
    if (headline != null && rules.vocabulary.isMultiStage(headline)) return null
    val today = raw.today()
    val events = raw.events.mapNotNull { it.toTrackingEvent(rules.vocabulary, zone, today) }
    val etaDate = rules.etaDate(raw.etaText, today) ?: raw.etaDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val etaWindowText = raw.etaWindowText ?: findEtaWindowText(raw.etaText)
    if (headline == null && events.isEmpty() && etaDate == null && etaWindowText == null) return PageOutcome.Empty
    return PageOutcome.Tracking(
        assembleSnapshot(
            vocabulary = rules.vocabulary,
            headline = headline,
            events = events,
            etaDate = etaDate,
            etaWindowText = etaWindowText,
            location = rules.location(raw),
            delayNote = rules.delayNote(headline, events),
        ),
    )
}

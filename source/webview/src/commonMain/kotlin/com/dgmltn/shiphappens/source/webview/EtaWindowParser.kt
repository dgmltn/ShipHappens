package com.dgmltn.shiphappens.source.webview

import kotlinx.datetime.LocalTime

/** A delivery window. A null [start] means open-ended ("by [end]"). */
data class EtaWindow(val start: LocalTime?, val end: LocalTime?)

// "3:00 PM - 5:00 PM", "3 - 5 PM" (start inherits the end's meridiem), "11:30 AM – 1:30 PM",
// "8 AM to 12 PM", "between 9:45 AM and 1:45 PM" (USPS's "Out for Delivery" phrasing). The end
// meridiem is mandatory: a bare "3 - 5" is ambiguous, and guessing it would show the user a
// window the carrier never quoted.
private val RANGE = Regex(
    """(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*(?:-|–|—|to|and)\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)""",
    RegexOption.IGNORE_CASE,
)

// "by 10 PM" — a lone cutoff, which is an open-ended window.
private val CUTOFF = Regex("""\bby\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)""", RegexOption.IGNORE_CASE)

// The two window shapes [parseEtaWindow] understands, for extraction from a noisy banner: a
// range ("10:35 AM - 2:35 PM", "between 9:45am and 1:45pm") or a lone cutoff ("by 8:00 PM").
// "by end of day" carries no time and is no window.
private val WINDOW_RANGE = Regex(
    """(?:between\s+)?\d{1,2}(?::\d{2})?\s*(?:am|pm)?\s*(?:-|–|—|to|and)\s*\d{1,2}(?::\d{2})?\s*(?:am|pm)""",
    RegexOption.IGNORE_CASE,
)
private val WINDOW_CUTOFF = Regex("""\bby\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)""", RegexOption.IGNORE_CASE)

/**
 * The delivery-window phrase as quoted on the page (fed to [parseEtaWindow] downstream), or
 * null when the text carries none — extracted verbatim so surrounding tooltip/date copy never
 * reaches the parser.
 */
fun findEtaWindowText(text: String?): String? {
    if (text.isNullOrBlank()) return null
    return WINDOW_RANGE.find(text)?.value ?: WINDOW_CUTOFF.find(text)?.value
}

/**
 * Parses a free-text delivery window as scraped from a carrier page.
 *
 * Returns null when nothing parses — deliberately all-or-nothing, never a half-filled window:
 * `ParcelRepository.applySnapshot` merges the two bounds atomically, so a lone start salvaged from
 * a malformed range could attach to a newer end and render a window nobody quoted.
 */
fun parseEtaWindow(text: String?): EtaWindow? {
    if (text.isNullOrBlank()) return null
    RANGE.find(text)?.let { m ->
        val (h1, m1, mer1, h2, m2, mer2) = m.destructured
        val end = time(h2, m2, mer2) ?: return null
        val start = time(h1, m1, mer1.ifEmpty { mer2 }) ?: return null
        return EtaWindow(start, end)
    }
    CUTOFF.find(text)?.let { m ->
        val (h, min, mer) = m.destructured
        return time(h, min, mer)?.let { EtaWindow(null, it) }
    }
    return null
}

/** 12-hour components -> [LocalTime]; null if any component is out of range. */
private fun time(hour: String, minute: String, meridiem: String): LocalTime? {
    val h = hour.toIntOrNull() ?: return null
    val min = if (minute.isEmpty()) 0 else minute.toIntOrNull() ?: return null
    if (h !in 1..12 || min !in 0..59) return null
    val h24 = when {
        meridiem.equals("pm", ignoreCase = true) && h != 12 -> h + 12
        meridiem.equals("am", ignoreCase = true) && h == 12 -> 0
        else -> h
    }
    return LocalTime(h24, min)
}

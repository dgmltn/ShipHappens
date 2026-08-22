package com.dgmltn.shiphappens.source.webview

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * Date-phrase parsing shared by the provider page logics. Carrier pages render delivery days
 * four ways — month-name with a year, numeric M/D/YYYY, relative words, and a year-less
 * month+day — and each helper owns exactly one of them so providers compose the subset their
 * pages actually use. Yearful parsers should be tried before [parseDayWithoutYear], which
 * ignores any year in the text and infers its own.
 */

private const val MONTH_NAMES =
    "January|February|March|April|May|June|July|August|September|October|November|December|" +
        "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec"
private val MONTH_DAY_YEAR = Regex("""\b($MONTH_NAMES)\.?\s+(\d{1,2}),?\s+(\d{4})""", RegexOption.IGNORE_CASE)
private val DAY_MONTH_YEAR = Regex("""\b(\d{1,2})\s+($MONTH_NAMES)\.?\s+(\d{4})""", RegexOption.IGNORE_CASE)
private val MONTH_DAY = Regex("""\b($MONTH_NAMES)\.?\s+(\d{1,2})\b""", RegexOption.IGNORE_CASE)
private val DAY_MONTH = Regex("""\b(\d{1,2})\s+($MONTH_NAMES)\b""", RegexOption.IGNORE_CASE)

// Lookbehind, not \b: fedex.com's hero flattens to "Thursday8/20/2026" (QA 2026-08-19), and
// there is no word boundary between "y" and "8". The year is 4 digits or 2 ("8/18/26" in
// fedex.com's travel-history date cells, live capture 2026-08-21) — two-digit years read as 2000s.
private val NUMERIC_MDY = Regex("""(?<![\d/])(\d{1,2})/(\d{1,2})/(\d{4}|\d{2})\b""")

private fun monthOf(name: String): Month? =
    Month.entries.firstOrNull { it.name.startsWith(name.trimEnd('.').uppercase()) }

private fun dateOf(year: String, monthName: String, day: String): LocalDate? {
    val month = monthOf(monthName) ?: return null
    return runCatching { LocalDate(year.toInt(), month, day.toInt()) }.getOrNull()
}

/** "Monday, July 28, 2026", "28 July 2026", "Aug 13, 2026" — a month-name date WITH a year. */
fun parseMonthNameDate(text: String?): LocalDate? {
    if (text.isNullOrBlank()) return null
    DAY_MONTH_YEAR.find(text)?.destructured?.let { (d, m, y) -> return dateOf(y, m, d) }
    MONTH_DAY_YEAR.find(text)?.destructured?.let { (m, d, y) -> return dateOf(y, m, d) }
    return null
}

/** "8/19/2026", "07/16/2026", "Thursday8/20/2026", "8/18/26" — numeric month/day/year. */
fun parseNumericMdyDate(text: String?): LocalDate? {
    val m = NUMERIC_MDY.find(text ?: return null) ?: return null
    val (mm, dd, yy) = m.destructured
    val year = yy.toInt().let { if (it < 100) it + 2000 else it }
    return runCatching { LocalDate(year, mm.toInt(), dd.toInt()) }.getOrNull()
}

/**
 * "today", "tomorrow", "yesterday", "overnight" (delivery during the coming night, i.e.
 * tomorrow morning) — resolved against [today], the date the page was read on
 * ([DomRaw.todayIso]). Null [today] means the page never reported its date; a guessed
 * resolution is worse than no ETA.
 */
fun parseRelativeDay(text: String?, today: LocalDate?): LocalDate? {
    if (text.isNullOrBlank() || today == null) return null
    val t = text.lowercase()
    return when {
        "today" in t -> today
        "tomorrow" in t || "overnight" in t -> today.plus(1, DateTimeUnit.DAY)
        "yesterday" in t -> today.minus(1, DateTimeUnit.DAY)
        else -> null
    }
}

// Full names first so alternation prefers them; "Thurs|Thur|Thu" longest-first likewise.
private const val WEEKDAY_NAMES =
    "Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|" +
        "Mon|Tues|Tue|Wed|Thurs|Thur|Thu|Fri|Sat|Sun"
private val WEEKDAY = Regex("""\b($WEEKDAY_NAMES)\b""", RegexOption.IGNORE_CASE)

/**
 * "Thursday Between 10:10 AM - 2:10 PM", "Arriving Monday" — a weekday-only promise (live
 * fedex.com wording near delivery, QA 2026-08-19), resolved to that weekday's next occurrence
 * counting [today] itself. Only safe on text known to be a promise: on arbitrary text a past
 * weekday ("Delivered Thursday") would resolve forward.
 */
private val TIME_12H = Regex("""(\d{1,2}):(\d{2})\s*([ap])\.?m\.?""", RegexOption.IGNORE_CASE)
private val TIME_24H = Regex("""\b(\d{1,2}):(\d{2})(?::\d{2})?\b""")

/** "3:06 AM", "1:48 pm", "12:07 A.M.", or 24-hour "14:33[:00]" — the first clock phrase found.
 *  A bare date ("08/19/2026") has no colon pair and parses to nothing. */
fun parseTimeOfDay(text: String?): LocalTime? {
    val s = text ?: return null
    TIME_12H.find(s)?.let { m ->
        val (h, min, ap) = m.destructured
        val hour24 = (h.toInt() % 12) + if (ap.lowercase() == "p") 12 else 0
        return runCatching { LocalTime(hour24, min.toInt()) }.getOrNull()
    }
    val m = TIME_24H.find(s) ?: return null
    val (h, min) = m.destructured
    return runCatching { LocalTime(h.toInt(), min.toInt()) }.getOrNull()
}

fun parseWeekdayName(text: String?, today: LocalDate?): LocalDate? {
    if (text.isNullOrBlank() || today == null) return null
    val name = WEEKDAY.find(text)?.groupValues?.get(1) ?: return null
    val target = DayOfWeek.entries.firstOrNull { it.name.startsWith(name.trimEnd('.').uppercase()) }
        ?: return null
    val ahead = (target.isoDayNumber - today.dayOfWeek.isoDayNumber + 7) % 7
    return today.plus(ahead, DateTimeUnit.DAY)
}

/**
 * "Saturday, August 22", "22 August" — a month+day with no year, so the year is inferred from
 * [today]: anything implausibly far ahead (>45 days) is read as last year's date, keeping a
 * December promise seen in January from landing eleven months out.
 */
fun parseDayWithoutYear(text: String?, today: LocalDate?): LocalDate? {
    if (text.isNullOrBlank() || today == null) return null
    val (monthName, day) = MONTH_DAY.find(text)?.destructured?.let { (m, d) -> m to d }
        ?: DAY_MONTH.find(text)?.destructured?.let { (d, m) -> m to d }
        ?: return null
    val candidate = dateOf(today.year.toString(), monthName, day) ?: return null
    return if (candidate > today.plus(45, DateTimeUnit.DAY)) {
        runCatching { LocalDate(today.year - 1, candidate.month, candidate.day) }.getOrNull()
    } else {
        candidate
    }
}

package com.dgmltn.shiphappens.domain

/**
 * The movement a scan's wording reports, as one display word — "Arrived", "Departed",
 * "Processed" — or null when it names none (a stage line, a delivery). Carrier-neutral: it
 * reads the few English stems every carrier uses, so a list of scans can say "Arrived San
 * Diego" whatever the carrier wrote after the verb.
 */
fun scanVerb(description: String): String? {
    val t = description.lowercase().trim()
    return when {
        // A delivery is the end of the trip, whatever it says about where the package was left.
        "deliver" in t -> null
        // "arrived"/"arrival" only: "arriving on time" is a promise, not a scan.
        "arrived" in t || "arrival" in t || t.startsWith("at ") -> "Arrived"
        "depart" in t || LEFT.containsMatchIn(t) -> "Departed"
        "process" in t -> "Processed"
        else -> null
    }
}

private val LEFT = Regex("\\bleft\\b")

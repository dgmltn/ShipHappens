package com.dgmltn.shiphappens.geo

/**
 * Carriers spell the same place many ways ("SOUTH SAN FRANCISCO CA 94080 US",
 * "South San Francisco, CA"). The bundled table and the geocache are both keyed by the one
 * canonical form this produces: "CITY, ST" for US places, "CITY, CC" otherwise.
 */
object PlaceKey {
    private val ZIP = Regex("""\b\d{5}(-\d{4})?\b""")
    private val WHITESPACE = Regex("""\s+""")
    private val US_SUFFIX = Regex("""(^|,\s*|\s+)(US|USA|UNITED STATES|UNITED STATES OF AMERICA)$""")
    private val TRAILING_STATE = Regex("""^(.*\S)\s+([A-Z]{2})$""")

    fun normalize(raw: String?): String? {
        if (raw == null) return null
        var s = raw.uppercase().replace(ZIP, " ")
        s = s.split(',').map { it.trim().replace(WHITESPACE, " ") }.filter { it.isNotEmpty() }.joinToString(", ")
        s = s.replace(US_SUFFIX, "").trim().trimEnd(',').trim()
        if (s.isEmpty()) return null
        val parts = s.split(", ").toMutableList()
        if (parts.size == 1) {
            val m = TRAILING_STATE.matchEntire(parts[0])
            if (m != null && m.groupValues[2] in US_STATES.values) {
                parts[0] = m.groupValues[1]
                parts.add(m.groupValues[2])
            }
        }
        if (parts.size >= 2) {
            val last = parts.last()
            US_STATES[last]?.let { parts[parts.lastIndex] = it }
        }
        return parts.joinToString(", ").takeIf { it.any(Char::isLetter) }
    }

    private val US_STATES: Map<String, String> = mapOf(
        "ALABAMA" to "AL", "ALASKA" to "AK", "ARIZONA" to "AZ", "ARKANSAS" to "AR", "CALIFORNIA" to "CA",
        "COLORADO" to "CO", "CONNECTICUT" to "CT", "DELAWARE" to "DE", "DISTRICT OF COLUMBIA" to "DC",
        "FLORIDA" to "FL", "GEORGIA" to "GA", "HAWAII" to "HI", "IDAHO" to "ID", "ILLINOIS" to "IL",
        "INDIANA" to "IN", "IOWA" to "IA", "KANSAS" to "KS", "KENTUCKY" to "KY", "LOUISIANA" to "LA",
        "MAINE" to "ME", "MARYLAND" to "MD", "MASSACHUSETTS" to "MA", "MICHIGAN" to "MI",
        "MINNESOTA" to "MN", "MISSISSIPPI" to "MS", "MISSOURI" to "MO", "MONTANA" to "MT",
        "NEBRASKA" to "NE", "NEVADA" to "NV", "NEW HAMPSHIRE" to "NH", "NEW JERSEY" to "NJ",
        "NEW MEXICO" to "NM", "NEW YORK" to "NY", "NORTH CAROLINA" to "NC", "NORTH DAKOTA" to "ND",
        "OHIO" to "OH", "OKLAHOMA" to "OK", "OREGON" to "OR", "PENNSYLVANIA" to "PA",
        "PUERTO RICO" to "PR", "RHODE ISLAND" to "RI", "SOUTH CAROLINA" to "SC", "SOUTH DAKOTA" to "SD",
        "TENNESSEE" to "TN", "TEXAS" to "TX", "UTAH" to "UT", "VERMONT" to "VT", "VIRGINIA" to "VA",
        "WASHINGTON" to "WA", "WEST VIRGINIA" to "WV", "WISCONSIN" to "WI", "WYOMING" to "WY",
    )
}

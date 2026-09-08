package com.dgmltn.shiphappens.domain

data class Carrier(
    val code: String,
    val displayName: String,
    val accentColorHex: String? = null,
    /**
     * Matched against a normalized number (see [normalizeTracking]); null for carriers we only
     * display. Regex compares by identity, so code that needs a known carrier must take it from
     * [WellKnownCarriers.byCode] rather than constructing an equal-looking one.
     */
    val numberPattern: Regex? = null,
) {
    fun claims(normalized: String): Boolean = numberPattern?.matches(normalized) == true
}

object WellKnownCarriers {
    val UPS = Carrier("ups", "UPS", "#5A3A22", Regex("^1Z[0-9A-Z]{10,}$"))
    val USPS = Carrier("usps", "USPS", "#1E3A8F", Regex("^(94|93|92|95|82)\\d{14,24}$|^[A-Z]{2}\\d{9}US$"))
    // 20-22 digit numbers with a USPS service prefix are USPS labels (FedEx Ground Economy hands
    // those to USPS); the lookahead keeps FedEx from claiming them, so resolution never depends
    // on registration order.
    val FEDEX = Carrier("fedex", "FedEx", "#5A1B9A", Regex("^\\d{12}$|^\\d{15}$|^(?!(94|93|92|95|82))\\d{20,22}$"))
    // Amazon orange (#FF9900) darkened to sit with the muted brand accents above. Order ids are
    // 3-7-7 digits normalized to 17 (hyphens stripped); US ids start 1 or 7.
    val AMAZON = Carrier("amazon", "Amazon", "#146EB4", Regex("^[17]\\d{16}$"))
    // Amazon's squid-ink navy; distinct from the orders source so carrier→webSpec lookups stay 1:1.
    val AMAZON_LOGISTICS = Carrier("amzl", "Amazon Logistics", "#37475A", Regex("^TBA\\d{9,15}$"))
    // DHL red (#D40511) darkened likewise; "dhlecs" not "dhl", leaving room for a DHL Express
    // carrier. Only the 420+ZIP-prefixed IMpb form — the bare 22-digit body stays USPS's, whose
    // pattern claims it (USPS does the last mile and tracks it too). 2026-09-03.
    val DHL_ECOMMERCE = Carrier("dhlecs", "DHL eCommerce", "#B3040D", Regex("^420\\d{5}9\\d{21,25}$"))
    val all = listOf(UPS, USPS, FEDEX, AMAZON, AMAZON_LOGISTICS, DHL_ECOMMERCE)
    fun byCode(code: String): Carrier? = all.firstOrNull { it.code == code.trim().lowercase() }

    /** The first well-known carrier whose pattern claims [raw] once normalized; null below ten characters. */
    fun detect(raw: String): Carrier? {
        val norm = normalizeTracking(raw.trim())
        if (norm.length < 10) return null
        return all.firstOrNull { it.claims(norm) }
    }
}

private val FALLBACK_PALETTE = listOf(
    "#0F766E", "#B45309", "#4338CA", "#9D174D", "#166534", "#7C2D12", "#1D4ED8", "#6B21A8",
)

/** Deterministic accent for carriers we don't have brand colors for. */
fun fallbackAccentColor(code: String): String {
    val h = code.lowercase().fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return FALLBACK_PALETTE[h % FALLBACK_PALETTE.size]
}

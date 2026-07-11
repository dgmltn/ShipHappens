package com.shiphappens.domain

data class Carrier(
    val code: String,
    val displayName: String,
    val accentColorHex: String? = null,
)

object WellKnownCarriers {
    val UPS = Carrier("ups", "UPS", "#5A3A22")
    val USPS = Carrier("usps", "USPS", "#1E3A8F")
    val FEDEX = Carrier("fedex", "FedEx", "#5A1B9A")
    val all = listOf(UPS, USPS, FEDEX)
    fun byCode(code: String): Carrier? = all.firstOrNull { it.code == code.trim().lowercase() }
}

private val FALLBACK_PALETTE = listOf(
    "#0F766E", "#B45309", "#4338CA", "#9D174D", "#166534", "#7C2D12", "#1D4ED8", "#6B21A8",
)

/** Deterministic accent for carriers we don't have brand colors for. */
fun fallbackAccentColor(code: String): String {
    val h = code.lowercase().fold(0) { acc, c -> (acc * 31 + c.code) and 0x7FFFFFFF }
    return FALLBACK_PALETTE[h % FALLBACK_PALETTE.size]
}

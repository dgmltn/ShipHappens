package com.dgmltn.shiphappens.data.source

import com.dgmltn.shiphappens.domain.Carrier
import com.dgmltn.shiphappens.domain.WellKnownCarriers
import com.dgmltn.shiphappens.domain.normalizeTracking

object BuiltInCarrierDetection {
    private val UPS = Regex("^1Z[0-9A-Z]{10,}$")
    private val USPS_NUM = Regex("^(94|93|92|95|82)\\d{14,24}$")
    private val USPS_INTL = Regex("^[A-Z]{2}\\d{9}US$")
    private val FEDEX = Regex("^\\d{12}$|^\\d{15}$|^\\d{20,22}$")
    // Amazon order ids: 3-7-7 digits normalized to 17 (hyphens stripped); US ids start 1 or 7.
    private val AMAZON = Regex("^[17]\\d{16}$")

    fun detect(raw: String): Carrier? {
        val norm = normalizeTracking(raw.trim())
        if (norm.length < 10) return null
        return when {
            UPS.matches(norm) -> WellKnownCarriers.UPS
            USPS_NUM.matches(norm) || USPS_INTL.matches(norm) -> WellKnownCarriers.USPS
            FEDEX.matches(norm) -> WellKnownCarriers.FEDEX
            AMAZON.matches(norm) -> WellKnownCarriers.AMAZON
            else -> null
        }
    }
}

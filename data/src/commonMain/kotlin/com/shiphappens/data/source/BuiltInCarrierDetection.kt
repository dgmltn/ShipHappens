package com.shiphappens.data.source

import com.shiphappens.domain.Carrier
import com.shiphappens.domain.WellKnownCarriers
import com.shiphappens.domain.normalizeTracking

object BuiltInCarrierDetection {
    private val UPS = Regex("^1Z[0-9A-Z]{10,}$")
    private val USPS_NUM = Regex("^(94|93|92|95|82)\\d{14,24}$")
    private val USPS_INTL = Regex("^[A-Z]{2}\\d{9}US$")
    private val FEDEX = Regex("^\\d{12}$|^\\d{15}$|^\\d{20,22}$")

    fun detect(raw: String): Carrier? {
        val norm = normalizeTracking(raw.trim())
        if (norm.length < 10) return null
        return when {
            UPS.matches(norm) -> WellKnownCarriers.UPS
            USPS_NUM.matches(norm) || USPS_INTL.matches(norm) -> WellKnownCarriers.USPS
            FEDEX.matches(norm) -> WellKnownCarriers.FEDEX
            else -> null
        }
    }
}

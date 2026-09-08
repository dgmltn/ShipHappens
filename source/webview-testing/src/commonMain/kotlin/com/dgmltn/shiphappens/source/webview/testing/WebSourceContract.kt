package com.dgmltn.shiphappens.source.webview.testing

import com.dgmltn.shiphappens.source.api.SourceResult
import com.dgmltn.shiphappens.source.webview.NoWebScraper
import com.dgmltn.shiphappens.source.webview.WebProviderSpec
import com.dgmltn.shiphappens.source.webview.WebSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** What every WebSource built from a spec must satisfy: identity from the carrier, detection from its pattern, a clean failure without a scraper. */
object WebSourceContract {
    fun verify(spec: WebProviderSpec, claims: List<String>, rejects: List<String>) {
        val source = WebSource(spec, NoWebScraper)
        assertEquals(spec.sourceId, source.descriptor.id)
        assertEquals(spec.carrier.displayName, source.descriptor.displayName)
        assertEquals(spec.carrier.accentColorHex, source.descriptor.accentColorHex)
        assertFalse(source.descriptor.implemented, "NoWebScraper => not implemented")
        claims.forEach { assertEquals(spec.carrier, source.detectCarrier(it), "claims $it") }
        rejects.forEach { assertNull(source.detectCarrier(it), "rejects $it") }
    }

    suspend fun verifyTrackFailsWithoutScraper(spec: WebProviderSpec, trackingNumber: String) {
        assertIs<SourceResult.Failure>(WebSource(spec, NoWebScraper).track(trackingNumber, null))
    }
}

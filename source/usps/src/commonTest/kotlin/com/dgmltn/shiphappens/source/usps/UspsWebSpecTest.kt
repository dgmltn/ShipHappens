package com.dgmltn.shiphappens.source.usps

import com.dgmltn.shiphappens.domain.TrackingStatus
import com.dgmltn.shiphappens.source.webview.DomRaw
import com.dgmltn.shiphappens.source.webview.snapshotOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UspsWebSpecTest {

    @Test fun tracking_url_uses_tools_usps_path() {
        assertEquals(
            "https://tools.usps.com/tracking/9434636106092288655003",
            UspsWebSpec.trackingUrl("9434636106092288655003"),
        )
    }

    @Test fun api_pattern_matches_candidate_tracking_endpoints_only() {
        val re = Regex(UspsWebSpec.apiUrlPatterns.single())
        // Broad by design (design spec §Decisions): both the legacy endpoint and any
        // plausible new one must match; non-tracking usps.com XHRs must not.
        // Runtime matches with JS RegExp.test (substring find), so assert with
        // containsMatchIn rather than Kotlin's full-match semantics.
        assertTrue(re.containsMatchIn("https://tools.usps.com/go/TrackConfirmAction?tLabels=9434636106092288655003"))
        assertTrue(re.containsMatchIn("https://tools.usps.com/api/tracking/v1/9434636106092288655003"))
        assertFalse(re.containsMatchIn("https://tools.usps.com/go/POLocatorAction"))
        assertFalse(re.containsMatchIn("https://webapis.ups.com/track/api/Track/GetStatus"))
    }

    @Test fun spec_identity_and_origins() {
        assertEquals("usps", UspsWebSpec.sourceId)
        assertEquals("usps.com", UspsWebSpec.cookieDomain)
        assertEquals(listOf("https://*.usps.com", "https://usps.com"), UspsWebSpec.allowedOriginRules())
        assertTrue(UspsWebSpec.challengeMarkers.isNotEmpty())
    }

    @Test fun parse_api_delegates_to_usps_parser() {
        val t = UspsWebSpec.parseApi(null, """{"statusCategory":"Delivered"}""")
        assertEquals(TrackingStatus.DELIVERED, t?.status)
        assertEquals(null, UspsWebSpec.parseApi(null, """{"unrelated":true}"""))
    }

    @Test fun parse_raw_delegates_to_usps_page_logic() {
        val result = UspsWebSpec.parseRaw(DomRaw(kind = "tracker", statusText = "On the Way"))
        assertEquals(TrackingStatus.IN_TRANSIT, result.snapshotOrNull()?.status)
    }
}

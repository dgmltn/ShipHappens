package com.dgmltn.shiphappens.source.dhlecs

import com.dgmltn.shiphappens.source.webview.NEVER_LOGGED_IN_JS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DhlEcsWebSpecTest {

    @Test fun tracking_url_normalizes_into_the_orders_page_shape() {
        assertEquals(
            "https://webtrack.dhlecs.com/orders?trackingNumber=420300019261234500000000000042",
            DhlEcsWebSpec.trackingUrl("420 30001 9261-2345 0000 0000 0000 42"),
        )
    }

    @Test fun origin_rules_are_confined_to_the_webtrack_subdomain() {
        assertEquals("webtrack.dhlecs.com", DhlEcsWebSpec.cookieDomain)
    }

    @Test fun login_is_never_reported() {
        // Anonymous tracker, AMZL-style: no login recipe, so the probe is the shared constant false.
        assertNull(DhlEcsWebSpec.login)
        assertEquals(NEVER_LOGGED_IN_JS, DhlEcsWebSpec.isLoggedInJs)
    }
}

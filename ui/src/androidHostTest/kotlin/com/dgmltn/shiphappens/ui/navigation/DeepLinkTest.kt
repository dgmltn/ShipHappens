package com.dgmltn.shiphappens.ui.navigation

import kotlin.test.*

class DeepLinkTest {
    @Test fun parcel_uri_parses() {
        assertEquals(DeepLink.Parcel("abc-123"), parseDeepLink("shiphappens://parcel/abc-123"))
    }

    @Test fun signin_uri_parses() {
        assertEquals(DeepLink.SignIn("ups"), parseDeepLink("shiphappens://signin/ups"))
    }

    @Test fun unknown_host_null_and_blank_are_ignored() {
        assertNull(parseDeepLink("shiphappens://nonsense/x"))
        assertNull(parseDeepLink("shiphappens://parcel/"))
        assertNull(parseDeepLink(null))
        assertNull(parseDeepLink("https://example.com/parcel/1"))
    }
}

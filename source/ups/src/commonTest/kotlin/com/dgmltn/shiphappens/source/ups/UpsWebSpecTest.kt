package com.dgmltn.shiphappens.source.ups

import kotlin.test.Test
import kotlin.test.assertEquals

class UpsWebSpecTest {

    @Test fun tracking_url_normalizes_into_the_tracknum_query_param() {
        assertEquals(
            "https://www.ups.com/track?loc=en_US&tracknum=1Z999AA10123456784",
            UpsWebSpec.trackingUrl("1z 999 aa1 01 2345 6784"),
        )
    }
}

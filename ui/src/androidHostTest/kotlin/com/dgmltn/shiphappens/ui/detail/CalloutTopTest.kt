package com.dgmltn.shiphappens.ui.detail

import kotlin.test.Test
import kotlin.test.assertEquals

class CalloutTopTest {
    @Test fun sits_above_the_stop_when_it_fits() = assertEquals(40, calloutTop(anchorY = 100, height = 48, gap = 12, edge = 6, maxHeight = 152))
    @Test fun flips_below_when_above_would_clip() = assertEquals(32, calloutTop(anchorY = 20, height = 48, gap = 12, edge = 6, maxHeight = 152))
    @Test fun clamps_when_neither_side_fits() = assertEquals(6, calloutTop(anchorY = 40, height = 140, gap = 12, edge = 6, maxHeight = 152))
}

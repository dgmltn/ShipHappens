package com.dgmltn.shiphappens.ui.list

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The entire release rule of [SwipeActionRow]: where the row settles is a function of release
 * position alone. Velocity is deliberately not an input.
 */
class SwipeReleaseTargetTest {

    private val threshold = 100f
    private val width = 400f

    @Test
    fun `released at or past threshold exits toward the swiped edge`() {
        assertEquals(width, swipeReleaseTarget(offset = 100f, thresholdPx = threshold, width = width))
        assertEquals(width, swipeReleaseTarget(offset = 250f, thresholdPx = threshold, width = width))
        assertEquals(-width, swipeReleaseTarget(offset = -100f, thresholdPx = threshold, width = width))
        assertEquals(-width, swipeReleaseTarget(offset = -250f, thresholdPx = threshold, width = width))
    }

    @Test
    fun `released under threshold springs back to rest`() {
        assertEquals(0f, swipeReleaseTarget(offset = 99f, thresholdPx = threshold, width = width))
        assertEquals(0f, swipeReleaseTarget(offset = -99f, thresholdPx = threshold, width = width))
        assertEquals(0f, swipeReleaseTarget(offset = 0f, thresholdPx = threshold, width = width))
    }

    @Test
    fun `unmeasured row can never exit`() {
        assertEquals(0f, swipeReleaseTarget(offset = 150f, thresholdPx = threshold, width = 0f))
    }
}

package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayColorTest {

    private fun a(c: Int) = c ushr 24
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    @Test
    fun nothingIsZero() {
        assertEquals(0, OverlayColor.argb(0f, 1f, 1f, 0f, 0f))
    }

    @Test
    fun colourOnlyKeepsItsColourAndAlpha() {
        val c = OverlayColor.argb(0f, 1f, 0.5f, 0f, 0.4f)
        assertEquals(102, a(c))
        assertEquals(255, r(c))
        assertEquals(128, g(c))
        assertEquals(0, b(c))
    }

    @Test
    fun darkenOnlyIsBlackWithThatAlpha() {
        val c = OverlayColor.argb(0.5f, 0f, 0f, 0f, 0f)
        assertEquals(128, a(c))
        assertEquals(0, r(c) + g(c) + b(c))
    }

    @Test
    fun darkenUnderColourCompositesAsTwoLayers() {
        // black 50% then white 50% on top: alpha = 0.5 + 0.5 * 0.5 = 0.75, colour = 1 * 0.5 / 0.75 = 2/3
        val c = OverlayColor.argb(0.5f, 1f, 1f, 1f, 0.5f)
        assertEquals(191, a(c))
        assertEquals(170, r(c))
        assertEquals(170, g(c))
        assertEquals(170, b(c))
    }

    @Test
    fun outOfRangeInputsAreClamped() {
        val c = OverlayColor.argb(2f, 2f, -1f, 0f, 3f)
        assertEquals(255, a(c))
        assertEquals(255, r(c))
        assertEquals(0, g(c))
    }
}

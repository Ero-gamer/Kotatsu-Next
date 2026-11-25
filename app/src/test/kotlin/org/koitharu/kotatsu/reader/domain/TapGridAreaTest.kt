package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TapGridAreaTest {

    @Test
    fun noMirrorKeepsArea() {
        TapGridArea.entries.forEach { assertEquals(it, it.mirrored(horizontal = false, vertical = false)) }
    }

    @Test
    fun horizontalSwapsLeftAndRight() {
        assertEquals(TapGridArea.TOP_RIGHT, TapGridArea.TOP_LEFT.mirrored(horizontal = true, vertical = false))
        assertEquals(TapGridArea.CENTER_LEFT, TapGridArea.CENTER_RIGHT.mirrored(horizontal = true, vertical = false))
        assertEquals(TapGridArea.BOTTOM_CENTER, TapGridArea.BOTTOM_CENTER.mirrored(horizontal = true, vertical = false))
    }

    @Test
    fun verticalSwapsTopAndBottom() {
        assertEquals(TapGridArea.BOTTOM_LEFT, TapGridArea.TOP_LEFT.mirrored(horizontal = false, vertical = true))
        assertEquals(TapGridArea.CENTER, TapGridArea.CENTER.mirrored(horizontal = false, vertical = true))
    }

    @Test
    fun bothRotatesHalfTurnAndIsInvolution() {
        assertEquals(TapGridArea.BOTTOM_RIGHT, TapGridArea.TOP_LEFT.mirrored(horizontal = true, vertical = true))
        TapGridArea.entries.forEach {
            assertEquals(it, it.mirrored(true, true).mirrored(true, true))
        }
    }
}

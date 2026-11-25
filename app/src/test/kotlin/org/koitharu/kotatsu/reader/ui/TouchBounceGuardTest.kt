package org.koitharu.kotatsu.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.reader.ui.tapgrid.TouchBounceGuard

class TouchBounceGuardTest {

    @Test
    fun noDragNoBounce() {
        assertFalse(TouchBounceGuard().isBounce(1_000))
    }

    @Test
    fun touchRightAfterDragIsBounce() {
        val guard = TouchBounceGuard()
        guard.onDragEnd(1_000)
        assertTrue(guard.isBounce(1_000))
        assertTrue(guard.isBounce(1_149))
    }

    @Test
    fun touchAfterWindowIsRealTap() {
        val guard = TouchBounceGuard()
        guard.onDragEnd(1_000)
        assertFalse(guard.isBounce(1_150))
        assertFalse(guard.isBounce(5_000))
    }

    @Test
    fun clockGoingBackwardsIsNotBounce() {
        val guard = TouchBounceGuard()
        guard.onDragEnd(1_000)
        assertFalse(guard.isBounce(900))
    }

    @Test
    fun resetForgetsTheDrag() {
        val guard = TouchBounceGuard()
        guard.onDragEnd(1_000)
        guard.reset()
        assertFalse(guard.isBounce(1_010))
    }
}

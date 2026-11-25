package org.koitharu.kotatsu.reader.ui.pager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderMotionTest {

    @Test
    fun quinticEndpoints() {
        assertEquals(0f, ReaderMotion.quintic(0f), 1e-6f)
        assertEquals(1f, ReaderMotion.quintic(1f), 1e-6f)
    }

    @Test
    fun quinticIsFrontLoadedAndMonotonic() {
        assertTrue(ReaderMotion.quintic(0.5f) > 0.9f)
        var prev = -1f
        for (i in 0..100) {
            val v = ReaderMotion.quintic(i / 100f)
            assertTrue(v >= prev)
            prev = v
        }
    }
}

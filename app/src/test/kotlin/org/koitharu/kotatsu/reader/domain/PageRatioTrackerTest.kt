package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageRatioTrackerTest {

    @Test
    fun emptyHasNoPlaceholder() {
        val t = PageRatioTracker()
        assertNull(t.median())
        assertEquals(0, t.placeholderHeight(1000))
    }

    @Test
    fun medianOfOddAndEvenCounts() {
        val t = PageRatioTracker()
        t.record(100, 200)
        t.record(100, 400)
        t.record(100, 300)
        assertEquals(3f, t.median()!!, 0.0001f)
        t.record(100, 500)
        assertEquals(3.5f, t.median()!!, 0.0001f)
    }

    @Test
    fun oneOddPageDoesNotSkewIt() {
        val t = PageRatioTracker()
        repeat(7) { t.record(800, 2400) }
        t.record(800, 80)
        assertEquals(3f, t.median()!!, 0.0001f)
    }

    @Test
    fun keepsOnlyTheLastSixteen() {
        val t = PageRatioTracker()
        repeat(16) { t.record(100, 100) }
        repeat(16) { t.record(100, 200) }
        assertEquals(2f, t.median()!!, 0.0001f)
    }

    @Test
    fun invalidSizesAreIgnored() {
        val t = PageRatioTracker()
        t.record(0, 100)
        t.record(100, 0)
        t.record(-1, 5)
        assertNull(t.median())
    }

    @Test
    fun placeholderScalesWithWidthAndIsClamped() {
        val t = PageRatioTracker()
        t.record(100, 300)
        assertEquals(900, t.placeholderHeight(300))
        val tiny = PageRatioTracker().also { it.record(1000, 10) }
        assertEquals(300, tiny.placeholderHeight(1000))
        val huge = PageRatioTracker().also { it.record(10, 10_000) }
        assertEquals(6000, huge.placeholderHeight(1000))
    }
}

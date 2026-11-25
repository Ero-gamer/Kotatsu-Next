package org.koitharu.kotatsu.reader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreloadPlannerTest {

    @Test
    fun noSamplesUntilTwoTransitions() {
        val t = ReadingSpeedTracker()
        t.onPage(1, 0)
        assertNull(t.averageGapMs())
        t.onPage(2, 1_000)
        assertNull(t.averageGapMs())
        t.onPage(3, 2_000)
        assertEquals(1_000L, t.averageGapMs())
    }

    @Test
    fun samePageIsNotATransition() {
        val t = ReadingSpeedTracker()
        t.onPage(1, 0)
        t.onPage(1, 500)
        t.onPage(2, 1_000)
        t.onPage(2, 1_100)
        t.onPage(3, 2_000)
        assertEquals(1_000L, t.averageGapMs())
    }

    @Test
    fun keepsOnlyTheLastFive() {
        val t = ReadingSpeedTracker()
        var now = 0L
        t.onPage(0, now)
        repeat(3) { i -> now += 10_000; t.onPage(i + 1L, now) } // three slow gaps
        repeat(5) { i -> now += 1_000; t.onPage(10L + i, now) } // five fast ones push them out
        assertEquals(1_000L, t.averageGapMs())
    }

    @Test
    fun longBreakIsIgnored() {
        val t = ReadingSpeedTracker()
        t.onPage(1, 0)
        t.onPage(2, 2_000)
        t.onPage(3, 4_000)
        t.onPage(4, 4_000 + 130_000) // phone put down
        t.onPage(5, 4_000 + 130_000 + 2_000)
        assertEquals(2_000L, t.averageGapMs())
    }

    @Test
    fun fastReaderGetsMoreAhead() {
        assertEquals(PreloadPlan(8, 2), PreloadPlanner.plan(800, metered = false, constrained = false))
        assertEquals(PreloadPlan(6, 2), PreloadPlanner.plan(2_500, metered = false, constrained = false))
        assertEquals(PreloadPlan(4, 2), PreloadPlanner.plan(6_000, metered = false, constrained = false))
        assertEquals(PreloadPlan(3, 2), PreloadPlanner.plan(30_000, metered = false, constrained = false))
    }

    @Test
    fun unknownSpeedUsesTheDefault() {
        assertEquals(PreloadPlan(6, 2), PreloadPlanner.plan(null, metered = false, constrained = false))
    }

    @Test
    fun meteredNetworkIsCapped() {
        assertEquals(PreloadPlan(3, 1), PreloadPlanner.plan(800, metered = true, constrained = false))
        assertEquals(PreloadPlan(3, 1), PreloadPlanner.plan(null, metered = true, constrained = false))
        assertEquals(PreloadPlan(3, 1), PreloadPlanner.plan(6_000, metered = true, constrained = false))
    }

    @Test
    fun smallDevicesStaySmall() {
        assertEquals(PreloadPlan(3, 1), PreloadPlanner.plan(800, metered = false, constrained = true))
        assertEquals(PreloadPlan(2, 1), PreloadPlanner.plan(null, metered = false, constrained = true))
        assertEquals(PreloadPlan(2, 1), PreloadPlanner.plan(20_000, metered = false, constrained = true))
        assertEquals(PreloadPlan(3, 1), PreloadPlanner.plan(800, metered = true, constrained = true))
    }
}

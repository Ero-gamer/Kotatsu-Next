package org.koitharu.kotatsu.download

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.download.ui.worker.AdaptiveGate

class AdaptiveGateTest {

    @Test
    fun startsAtMax() {
        assertEquals(4, AdaptiveGate(4).limit)
    }

    @Test
    fun rateLimitShrinksButNotBelowOne() {
        val gate = AdaptiveGate(4, cooldownMs = 10)
        var now = 0L
        repeat(10) {
            gate.onRateLimited(now)
            now += 100
        }
        assertEquals(1, gate.limit)
    }

    @Test
    fun burstWithinCooldownCountsOnce() {
        val gate = AdaptiveGate(4, cooldownMs = 2_000)
        gate.onRateLimited(0)
        gate.onRateLimited(500)
        gate.onRateLimited(1_999)
        assertEquals(3, gate.limit)
        gate.onRateLimited(2_000)
        assertEquals(2, gate.limit)
    }

    @Test
    fun successesGiveBackOnePermitEach() {
        val gate = AdaptiveGate(4, recoverAfter = 3, cooldownMs = 0)
        gate.onRateLimited(0)
        gate.onRateLimited(1)
        assertEquals(2, gate.limit)
        repeat(3) { gate.onSuccess() }
        assertEquals(3, gate.limit)
        repeat(3) { gate.onSuccess() }
        assertEquals(4, gate.limit)
        repeat(10) { gate.onSuccess() }
        assertEquals(4, gate.limit)
    }

    @Test
    fun rateLimitResetsSuccessRun() {
        val gate = AdaptiveGate(4, recoverAfter = 3, cooldownMs = 0)
        gate.onRateLimited(0)
        repeat(2) { gate.onSuccess() }
        gate.onRateLimited(1)
        repeat(2) { gate.onSuccess() }
        assertEquals(2, gate.limit)
        gate.onSuccess()
        assertEquals(3, gate.limit)
    }
}

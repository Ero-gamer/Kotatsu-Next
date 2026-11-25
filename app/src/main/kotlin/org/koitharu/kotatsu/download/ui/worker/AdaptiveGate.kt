package org.koitharu.kotatsu.download.ui.worker

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Concurrency limiter that shrinks when the server rate-limits us and grows back after a run of successes.
 * Starts at [max] permits, never goes below 1. Each rate-limit hit parks one permit (at most once per
 * [cooldownMs], so a burst of 429s from requests already in flight counts once); every [recoverAfter]
 * successes without a hit give one parked permit back.
 */
class AdaptiveGate(
    private val max: Int,
    private val recoverAfter: Int = 20,
    private val cooldownMs: Long = 2_000L,
) {

    private val semaphore = Semaphore(max)
    private val lock = Any()
    private var parked = 0
    private var successes = 0
    private var lastHit = Long.MIN_VALUE

    init {
        require(max >= 1) { "max must be >= 1" }
    }

    /** Permits currently usable (max minus parked). */
    val limit: Int
        get() = synchronized(lock) { max - parked }

    suspend fun <T> withPermit(block: suspend () -> T): T = semaphore.withPermit { block() }

    fun onRateLimited(nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        successes = 0
        if (lastHit != Long.MIN_VALUE && nowMs - lastHit < cooldownMs) return@synchronized
        if (parked < max - 1 && semaphore.tryAcquire()) {
            parked++
            lastHit = nowMs
        }
    }

    fun onSuccess() = synchronized(lock) {
        if (parked > 0 && ++successes >= recoverAfter) {
            successes = 0
            parked--
            semaphore.release()
        }
    }
}

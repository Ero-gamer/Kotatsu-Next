package org.koitharu.kotatsu.download

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.download.ui.worker.retryDelayFor

class RetryDelayTest {

    @Test
    fun serverDelayWins() {
        assertEquals(7_000L, retryDelayFor(7_000L, 0))
        assertEquals(0L, retryDelayFor(0L, 2))
    }

    @Test
    fun unknownServerDelayBacksOffExponentially() {
        assertEquals(2_000L, retryDelayFor(null, 0))
        assertEquals(4_000L, retryDelayFor(-1L, 1))
        assertEquals(8_000L, retryDelayFor(null, 2))
    }

    @Test
    fun backoffIsCapped() {
        assertEquals(16_000L, retryDelayFor(null, 3))
        assertEquals(16_000L, retryDelayFor(null, 50))
        assertEquals(2_000L, retryDelayFor(null, -4))
    }
}

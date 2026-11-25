package org.koitharu.kotatsu.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryAfterTest {

    private val now = 1_700_000_000_000L // Tue, 14 Nov 2023 22:13:20 GMT

    @Test
    fun secondsBecomeMillis() {
        assertEquals(120_000L, "120".parseRetryAfter(now))
        assertEquals(5_000L, " 5 ".parseRetryAfter(now))
    }

    @Test
    fun negativeSecondsClampToZero() {
        assertEquals(0L, "-3".parseRetryAfter(now))
    }

    @Test
    fun httpDateBecomesDelayFromNow() {
        assertEquals(40_000L, "Tue, 14 Nov 2023 22:14:00 GMT".parseRetryAfter(now))
    }

    @Test
    fun pastHttpDateIsZero() {
        assertEquals(0L, "Tue, 14 Nov 2023 22:00:00 GMT".parseRetryAfter(now))
    }

    @Test
    fun garbageIsZeroNotException() {
        assertEquals(0L, "soon".parseRetryAfter(now))
        assertEquals(0L, "".parseRetryAfter(now))
    }
}

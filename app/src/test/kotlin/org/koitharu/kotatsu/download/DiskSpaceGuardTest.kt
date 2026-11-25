package org.koitharu.kotatsu.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.koitharu.kotatsu.core.exceptions.NotEnoughSpaceException
import org.koitharu.kotatsu.download.domain.DiskSpaceGuard

class DiskSpaceGuardTest {

    @Test
    fun enoughRoom() {
        assertTrue(DiskSpaceGuard.hasRoom(DiskSpaceGuard.MIN_FREE_BYTES))
        assertTrue(DiskSpaceGuard.hasRoom(10L * 1024 * 1024 * 1024))
    }

    @Test
    fun belowMinimumIsRefused() {
        assertFalse(DiskSpaceGuard.hasRoom(DiskSpaceGuard.MIN_FREE_BYTES - 1))
        assertFalse(DiskSpaceGuard.hasRoom(0))
    }

    @Test
    fun unknownFreeSpaceDoesNotBlock() {
        assertTrue(DiskSpaceGuard.hasRoom(-1))
    }

    @Test
    fun checkThrowsWithNumbers() {
        try {
            DiskSpaceGuard.check(5, 100)
            fail("expected NotEnoughSpaceException")
        } catch (e: NotEnoughSpaceException) {
            assertEquals(5L, e.freeBytes)
            assertEquals(100L, e.neededBytes)
        }
        DiskSpaceGuard.check(100, 100)
    }
}

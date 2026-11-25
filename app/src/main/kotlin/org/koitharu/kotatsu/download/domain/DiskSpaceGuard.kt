package org.koitharu.kotatsu.download.domain

import org.koitharu.kotatsu.core.exceptions.NotEnoughSpaceException

/** Decides whether a chapter may be downloaded given the free bytes of the target volume. Plain Kotlin, JVM-testable. */
object DiskSpaceGuard {

    /** Room kept free for a chapter in progress (pages, temp files, the archive being written). */
    const val MIN_FREE_BYTES = 200L * 1024 * 1024

    fun hasRoom(freeBytes: Long, minBytes: Long = MIN_FREE_BYTES): Boolean = freeBytes < 0 || freeBytes >= minBytes

    /** @throws NotEnoughSpaceException when [freeBytes] is known (>= 0) and below [minBytes]. */
    fun check(freeBytes: Long, minBytes: Long = MIN_FREE_BYTES) {
        if (!hasRoom(freeBytes, minBytes)) throw NotEnoughSpaceException(freeBytes, minBytes)
    }
}

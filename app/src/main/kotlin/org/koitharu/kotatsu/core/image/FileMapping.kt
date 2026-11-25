package org.koitharu.kotatsu.core.image

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Read-only memory map of the whole file: the kernel pages it in on demand and can drop the pages under
 * memory pressure, so a big image never needs a second heap copy. Null when the file is empty, too large
 * for one mapping or cannot be mapped; callers then fall back to reading it. The mapping stays valid after
 * this returns (it is released when the buffer is garbage-collected), so keep a reference to the buffer
 * for as long as native code uses it. Only for immutable files (page cache, archives are replaced, never edited).
 */
internal fun File.mapReadOnlyOrNull(maxSize: Long = Int.MAX_VALUE.toLong()): MappedByteBuffer? = try {
    val size = length()
    if (size !in 1..maxSize) {
        null
    } else {
        FileInputStream(this).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, size) }
    }
} catch (_: IOException) {
    null
} catch (_: OutOfMemoryError) {
    null
}

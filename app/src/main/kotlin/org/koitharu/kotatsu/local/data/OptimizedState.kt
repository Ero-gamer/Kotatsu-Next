package org.koitharu.kotatsu.local.data

import android.util.LruCache
import java.io.File

/**
 * Memory-only record of which local archives were rewritten by the image optimizer, filled when a
 * manga index is parsed (which already happens in the background) and read, without any I/O, when the
 * chapter list is built. The key includes length and modification time, so a rewritten or replaced
 * archive is never matched against a stale answer; unknown archives simply show no badge.
 */
object OptimizedState {

    private val cache = LruCache<String, Boolean>(128)

    fun put(file: File, optimized: Boolean) {
        cache.put(key(file), optimized)
    }

    fun isOptimized(file: File?): Boolean = file != null && cache.get(key(file)) == true

    private fun key(file: File) = "${file.path}|${file.length()}|${file.lastModified()}"
}

package org.koitharu.kotatsu.core.image

import android.util.LruCache

/**
 * Remembers the REAL format (from the file header) of recently fetched pages, so the page grids can
 * show format badges without re-reading anything. One packed Int per page; thread safe.
 */
object PageFormatRegistry {

    private const val CAPACITY = 4096
    private const val ANIMATED_FLAG = 0x100
    private const val FORMAT_MASK = 0xFF

    class Badge(val format: ImageFormat, val animated: Boolean)

    private val cache = LruCache<Long, Int>(CAPACITY)
    private val formats = ImageFormat.entries

    fun record(pageId: Long, info: ImageInfo) {
        if (!info.isKnown) return
        cache.put(pageId, info.format.ordinal or (if (info.animated) ANIMATED_FLAG else 0))
    }

    fun lookup(pageId: Long): Badge? {
        val packed = cache.get(pageId) ?: return null
        val format = formats.getOrNull(packed and FORMAT_MASK) ?: return null
        return Badge(format, packed and ANIMATED_FLAG != 0)
    }
}

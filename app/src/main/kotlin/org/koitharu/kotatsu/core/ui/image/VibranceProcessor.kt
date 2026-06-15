package org.koitharu.kotatsu.core.ui.image

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.core.net.toFile
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVibranceFilter
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.util.zip.ZipFile

/**
 * Singleton that applies GLSL vibrance (GPUImageVibranceFilter) to page bitmaps.
 *
 * Resource optimisation:
 * - [Semaphore](1): only ONE page processes at a time — primary crash guard for low-end devices.
 *   Off-screen pages that request processing queue up and are cancelled before they reach
 *   the semaphore (BasePageHolder cancels the job on onPause).
 * - LRU in-memory cache ([MAX_CACHED_BITMAPS] entries): re-scrolling to a visited page
 *   reuses the cached vibrance bitmap — zero GPU work.
 * - Shared [GPUImage] instance: EGL context created once, reused across all pages.
 * - Input bitmap is decoded from the page file URI (same path as sharpening), never
 *   holding a reference to SSIV internals.
 * - ARGB_8888 copy made only if needed; recycled immediately after the GPU pass.
 */
object VibranceProcessor {

    private const val MAX_CACHED_BITMAPS = 4

    private val semaphore = Semaphore(1)

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHED_BITMAPS) {
        override fun sizeOf(key: String, value: Bitmap) = 1
    }

    @Volatile private var gpuImage: GPUImage? = null

    fun cacheKey(uri: String, vibrance: Float) = "$uri|v$vibrance"

    fun getCached(key: String): Bitmap? = synchronized(cache) { cache.get(key) }

    /**
     * Decodes the page bitmap from [pageUri], applies GLSL vibrance, caches and returns result.
     * Suspends until the [Semaphore] is available — at most one page processes at a time.
     * Returns null on decode failure, GPU error, or unsupported URI scheme.
     */
    suspend fun process(context: Context, pageUri: Uri, vibrance: Float, key: String): Bitmap? {
        if (!pageUri.isFileUri() && !pageUri.isZipUri()) return null

        return semaphore.withPermit {
            runCatching {
                val raw = decodeBitmap(pageUri) ?: return@runCatching null
                val needsCopy = raw.config != Bitmap.Config.ARGB_8888
                val src = if (needsCopy) {
                    val copy = raw.copy(Bitmap.Config.ARGB_8888, false)
                    raw.recycle()
                    copy
                } else raw
                try {
                    val gpu = gpuImage ?: GPUImage(context.applicationContext).also { gpuImage = it }
                    gpu.setFilter(GPUImageVibranceFilter(vibrance))
                    val result = gpu.getBitmapWithFilterApplied(src) ?: return@runCatching null
                    synchronized(cache) { cache.put(key, result) }
                    result
                } finally {
                    src.recycle()
                }
            }.onFailure {
                gpuImage = null // invalidate on EGL/GPU error
            }.getOrNull()
        }
    }

    /**
     * Applies GLSL vibrance directly to an in-memory [Bitmap] (e.g. color-correction preview).
     * Uses the same [Semaphore] and GPU instance as [process].
     */
    suspend fun processBitmap(context: Context, input: Bitmap, vibrance: Float): Bitmap? {
        return semaphore.withPermit {
            runCatching {
                val needsCopy = input.config != Bitmap.Config.ARGB_8888
                val src = if (needsCopy) input.copy(Bitmap.Config.ARGB_8888, false) else input
                try {
                    val gpu = gpuImage ?: GPUImage(context.applicationContext).also { gpuImage = it }
                    gpu.setFilter(GPUImageVibranceFilter(vibrance))
                    gpu.getBitmapWithFilterApplied(src)
                } finally {
                    if (needsCopy) src.recycle()
                }
            }.onFailure { gpuImage = null }.getOrNull()
        }
    }

    fun releaseEntry(key: String) {
        synchronized(cache) {
            cache.remove(key)?.let { bmp ->
                if (!bmp.isRecycled) bmp.recycle()
            }
        }
    }

    fun trimMemory() {
        synchronized(cache) {
            // Recycle all cached bitmaps before evicting
            val snapshot = cache.snapshot()
            cache.evictAll()
            snapshot.values.forEach { if (!it.isRecycled) it.recycle() }
        }
        gpuImage = null
    }

    private fun decodeBitmap(uri: Uri): Bitmap? = runCatching {
        if (uri.isZipUri()) {
            ZipFile(uri.schemeSpecificPart).use { zip ->
                val entry = zip.getEntry(uri.fragment) ?: return null
                zip.getInputStream(entry).use { stream ->
                    BitmapDecoderCompat.decode(stream, MimeTypes.getMimeTypeFromExtension(entry.name))
                }
            }
        } else {
            BitmapDecoderCompat.decode(uri.toFile())
        }
    }.getOrNull()
}

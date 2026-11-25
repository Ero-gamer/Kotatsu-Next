package org.koitharu.kotatsu.reader.domain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.LruCache
import androidx.annotation.ColorInt
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Background color for a page, taken from the color its border has: 8 points (corners and edge middles)
 * of a ~64px thumbnail. Returns a color only when most points agree, so pages with art running to the
 * edge keep the default background. Formats the platform decoder cannot read give null.
 */
object PageEdgeColor {

    private const val TARGET_SIZE = 64
    private const val MIN_AGREEING = 6
    private const val MAX_DISTANCE = 40

    private const val NO_COLOR = Long.MIN_VALUE
    private const val CACHE_SIZE = 32

    /** Results (incl. "no color") keyed by uri + backing file stamp, so a rewritten file is re-sampled. */
    private val cache = LruCache<String, Long>(CACHE_SIZE)

    @ColorInt
    fun sample(uri: Uri): Int? {
        val key = cacheKey(uri)
        if (key != null) {
            cache.get(key)?.let { return if (it == NO_COLOR) null else it.toInt() }
        }
        return try {
            val result = decodeSample(uri)
            if (key != null) cache.put(key, result?.toLong() ?: NO_COLOR)
            result
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun cacheKey(uri: Uri): String? {
        val file = when {
            uri.isFileUri() -> uri.path?.let(::File)
            uri.isZipUri() -> File(uri.schemeSpecificPart)
            else -> null
        } ?: return null
        val length = file.length()
        return if (length > 0L) "$uri|$length|${file.lastModified()}" else null
    }

    private fun decodeSample(uri: Uri): Int? {
        val bitmap = decodeThumbnail(uri) ?: return null
        return try {
            edgeColor(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * A roughly 64 px (longest side) ARGB_8888 thumbnail of [uri], or null when the platform decoder cannot
     * read it. The caller recycles it. Shared with [PageStatsEngine]. Blocking: call off the main thread.
     */
    internal fun decodeThumbnail(uri: Uri): Bitmap? {
        return withStreams(uri) { open ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(open(), null, bounds)
            val longest = max(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return@withStreams null
            val options = BitmapFactory.Options().apply {
                inSampleSize = Integer.highestOneBit((longest / TARGET_SIZE).coerceAtLeast(1))
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(open(), null, options)
        }
    }

    /**
     * A decode of [uri] sized by pixel count rather than by its longest side: about [budget] pixels (up to
     * 4x with the power-of-two sample size), so tall webtoon strips get as many samples as a normal page.
     * The caller recycles it. Blocking: call off the main thread.
     */
    internal fun decodePixelBudget(uri: Uri, budget: Int): Bitmap? {
        return withStreams(uri) { open ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(open(), null, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withStreams null
            val ratio = sqrt(bounds.outWidth.toDouble() * bounds.outHeight / budget)
            val options = BitmapFactory.Options().apply {
                inSampleSize = Integer.highestOneBit(ratio.toInt().coerceAtLeast(1))
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(open(), null, options)
        }
    }

    /** Same key rule as the color cache: the uri plus the backing file's length and modification time. */
    internal fun stampedKey(uri: Uri): String? = cacheKey(uri)

    private fun edgeColor(bitmap: Bitmap): Int? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 3 || h < 3) return null
        val xs = intArrayOf(0, w / 2, w - 1)
        val ys = intArrayOf(0, h / 2, h - 1)
        val points = ArrayList<Int>(8)
        for (yi in 0..2) {
            for (xi in 0..2) {
                if (xi == 1 && yi == 1) continue // centre is content, not border
                val c = bitmap.getPixel(xs[xi], ys[yi])
                if (Color.alpha(c) >= 128) points.add(c)
            }
        }
        if (points.size < MIN_AGREEING) return null
        val r = median(points.map { Color.red(it) })
        val g = median(points.map { Color.green(it) })
        val b = median(points.map { Color.blue(it) })
        val agreeing = points.count {
            abs(Color.red(it) - r) <= MAX_DISTANCE &&
                abs(Color.green(it) - g) <= MAX_DISTANCE &&
                abs(Color.blue(it) - b) <= MAX_DISTANCE
        }
        return if (agreeing >= MIN_AGREEING) Color.rgb(r, g, b) else null
    }

    private fun median(values: List<Int>): Int = values.sorted()[values.size / 2]

    /**
     * Runs [block] with an `open` function that returns a fresh stream of [uri] on each call; every stream is
     * closed afterwards. A zip archive is opened once for all of them.
     */
    private fun <T> withStreams(uri: Uri, block: (open: () -> InputStream) -> T?): T? {
        val opened = ArrayList<InputStream>(2)
        try {
            return when {
                uri.isFileUri() -> {
                    val file = File(requireNotNull(uri.path))
                    block { file.inputStream().also { opened.add(it) } }
                }

                uri.isZipUri() -> ZipFile(uri.schemeSpecificPart).use { zip ->
                    val entry = zip.getEntry(uri.fragment) ?: return null
                    block { zip.getInputStream(entry).also { opened.add(it) } }
                }

                else -> null
            }
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }
}

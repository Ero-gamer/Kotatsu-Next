package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Splits a double-page spread (a landscape image, width over [RATIO] x height) into its two halves as
 * WebP files, so each half fills the screen on its own. The reading direction decides which half comes
 * first: left half first for left-to-right, right half first for right-to-left. Halves do not overlap.
 */
object WideImageSplitter {

    private const val RATIO = 1.2
    private const val MAX_HALF_BYTES = 48L * 1024 * 1024
    private const val MIN_WIDTH = 64

    /** True for an image that can be region-decoded and re-encoded without losing data we care about. */
    fun isSplittable(info: ImageInfo): Boolean = (info.format == ImageFormat.JPEG || info.format == ImageFormat.PNG || info.format == ImageFormat.WEBP) &&
        !info.animated &&
        info.orientation <= 1 &&
        info.bitDepth <= 8 &&
        info.width >= MIN_WIDTH &&
        info.height in 1..WebpEncoding.MAX_DIMENSION &&
        (info.width + 1) / 2 in 1..WebpEncoding.MAX_DIMENSION

    /** True when [info] describes a spread [split] would cut in two. */
    fun needsSplit(info: ImageInfo): Boolean = isSplittable(info) &&
        info.width.toDouble() > info.height.toDouble() * RATIO &&
        ((info.width + 1) / 2).toLong() * info.height * 4 <= MAX_HALF_BYTES

    /**
     * Cuts [source] into two WebP files created with [newFile] (the caller owns and deletes them), in reading
     * order for [rightToLeft]. Null when the image must stay as it is (not a spread, animated, rotated,
     * undecodable or any failure; nothing is left behind in that case).
     */
    fun split(source: File, target: WebpTarget, rightToLeft: Boolean, newFile: () -> File): List<File>? {
        val info = try {
            ImageFormatProbe.probe(source)
        } catch (_: Exception) {
            return null
        }
        if (!needsSplit(info)) return null
        val hasAlpha = info.hasAlpha == true
        val mid = info.width / 2
        val halves = listOf(Rect(0, 0, mid, info.height), Rect(mid, 0, info.width, info.height))
            .let { if (rightToLeft) it.reversed() else it }
        val parts = ArrayList<File>(2)
        var decoder: BitmapRegionDecoder? = null
        try {
            decoder = BitmapDecoderCompat.createRegionDecoder(source.absolutePath) ?: return null
            if (decoder.width != info.width || decoder.height != info.height) return null
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
            }
            for (region in halves) {
                val bitmap = decoder.decodeRegion(region, options) ?: return discard(parts)
                val bytes = try {
                    WebpEncoding.encode(bitmap, hasAlpha, target)
                } finally {
                    bitmap.recycle()
                }
                if (bytes == null || bytes.isEmpty()) return discard(parts)
                val file = newFile()
                parts += file
                FileOutputStream(file).use { it.write(bytes) }
            }
            return if (parts.size == 2) parts else discard(parts)
        } catch (_: Exception) {
            return discard(parts)
        } catch (_: OutOfMemoryError) {
            return discard(parts)
        } finally {
            decoder?.recycle()
        }
    }

    private fun discard(files: List<File>): List<File>? {
        files.forEach { it.delete() }
        return null
    }
}

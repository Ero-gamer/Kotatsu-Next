package org.koitharu.kotatsu.core.image

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.Build
import java.io.File
import java.io.FileOutputStream

/**
 * Splits very tall images (webtoon strips) into several WebP files, so the page list stays stable while
 * reading. Same thresholds as TachiyomiSY: split only when height > width * 3 and more than one part
 * results. Parts have equal height (the last one takes the remainder) and do not overlap.
 */
object TallImageSplitter {

    private const val MAX_PARTS = 99
    private const val MAX_PART_BYTES = 48L * 1024 * 1024
    private const val MIN_PART_HEIGHT = 512
    private const val RATIO = 3

    /** How an image is cut: [partCount] parts of [partHeight] pixels (the last may be shorter). */
    data class Plan(val partHeight: Int, val partCount: Int)

    /** True for an image that can be region-decoded and re-encoded without losing data we care about. */
    fun isSplittable(info: ImageInfo): Boolean = (info.format == ImageFormat.JPEG || info.format == ImageFormat.PNG || info.format == ImageFormat.WEBP) &&
        !info.animated &&
        info.orientation <= 1 &&
        info.bitDepth <= 8 &&
        info.width in 1..WebpEncoding.MAX_DIMENSION

    /** The cut for an image of [width] x [height], or null when it is not tall enough to be split. */
    fun plan(width: Int, height: Int): Plan? {
        if (width <= 0 || height <= width.toLong() * RATIO) return null
        val byMemory = (MAX_PART_BYTES / (width.toLong() * 4)).toInt()
        val optimal = minOf(
            Resources.getSystem().displayMetrics.heightPixels * 2,
            byMemory,
            WebpEncoding.MAX_DIMENSION,
        ).coerceAtLeast(MIN_PART_HEIGHT)
        val partCount = (height - 1) / optimal + 1
        if (partCount <= 1 || partCount > MAX_PARTS) return null
        return Plan(partHeight = (height + partCount - 1) / partCount, partCount = partCount)
    }

    /** True when [info] describes an image [split] would cut. */
    fun needsSplit(info: ImageInfo): Boolean = isSplittable(info) && plan(info.width, info.height) != null

    /**
     * Output encoding for [source] per [WebpPolicy]: with [auto] lossless sources become lossy, lossy
     * ones keep the quality the file was saved with (header estimate), [fallbackQuality] is the fixed-mode fallback.
     */
    fun targetFor(source: File, info: ImageInfo, fallbackQuality: Int, auto: Boolean = false): WebpTarget =
        WebpPolicy.target(auto, info.format, PageConverter.sourceQuality(source, info), fallbackQuality)

    /**
     * Cuts [source] into WebP part files created with [newFile] (the caller owns and deletes them).
     * Returns the parts in order, or null when the image must stay as it is (not tall enough, animated,
     * rotated, undecodable or any failure; nothing is left behind in that case).
     */
    fun split(source: File, target: WebpTarget, newFile: () -> File): List<File>? {
        val info = try {
            ImageFormatProbe.probe(source)
        } catch (_: Exception) {
            return null
        }
        if (!isSplittable(info)) return null
        val plan = plan(info.width, info.height) ?: return null
        val hasAlpha = info.hasAlpha == true
        val parts = ArrayList<File>(plan.partCount)
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
            var top = 0
            while (top < info.height) {
                val bottom = minOf(top + plan.partHeight, info.height)
                val bitmap = decoder.decodeRegion(Rect(0, top, info.width, bottom), options)
                    ?: return discard(parts)
                val bytes = try {
                    WebpEncoding.encode(bitmap, hasAlpha, target)
                } finally {
                    bitmap.recycle()
                }
                if (bytes == null || bytes.isEmpty()) return discard(parts)
                val file = newFile()
                parts += file
                FileOutputStream(file).use { it.write(bytes) }
                top = bottom
            }
            return if (parts.size > 1) parts else discard(parts)
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

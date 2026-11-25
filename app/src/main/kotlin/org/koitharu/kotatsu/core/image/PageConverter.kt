package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.Build
import org.koitharu.kotatsu.core.image.codecs.NativeImage
import java.io.File
import java.io.IOException

/**
 * Re-encodes one still page (JPEG, PNG, BMP, GIF, AVIF, JXL) as WebP without changing its resolution.
 * Shared by the download-time conversion and the optimizer so both pick formats and targets the same way.
 */
object PageConverter {

    private const val MAX_BITMAP_BYTES = 64L * 1024 * 1024
    private const val TINY_LOSSLESS_BPP = 0.12
    private const val TINY_LOSSY_BPP = 0.2

    /** Header-level check: a still image of a supported format that fits the encoder and the memory budget. */
    fun isConvertible(info: ImageInfo): Boolean = isConvertibleFormat(info) &&
        (info.format == ImageFormat.AVIF || info.format == ImageFormat.JXL || info.bitDepth <= 8) &&
        info.orientation <= 1 &&
        info.width in 1..WebpEncoding.MAX_DIMENSION &&
        info.height in 1..WebpEncoding.MAX_DIMENSION &&
        info.width.toLong() * info.height * 4 <= MAX_BITMAP_BYTES

    fun isConvertibleFormat(info: ImageInfo): Boolean = !info.animated && when (info.format) {
        ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.BMP, ImageFormat.GIF, ImageFormat.AVIF, ImageFormat.JXL -> true
        else -> false
    }

    /** Source quality as far as the header tells; PNG / JXL / BMP / GIF give [SourceQuality.Unknown]. */
    fun sourceQuality(file: File, info: ImageInfo): SourceQuality = WebpEncoding.estimateQuality(file, info.format)

    /** WebP bytes for [source], or null when it cannot be converted (the caller then keeps the original). */
    fun convert(source: File, info: ImageInfo, target: WebpTarget): ByteArray? {
        if (isAlreadyTiny(source, info, target)) return null
        var bitmap: Bitmap? = null
        return try {
            bitmap = decode(source, info) ?: return null
            if (bitmap.width != info.width || bitmap.height != info.height) return null
            val hasAlpha = info.hasAlpha == true && bitmap.hasAlpha()
            WebpEncoding.encode(bitmap, hasAlpha, target)?.takeIf { it.isNotEmpty() }
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * The caller discards a result that is not smaller than the source, but only after a full (slow, method 6)
     * encode. A page that is already this small per pixel (1-bit / indexed line art, heavily compressed JPEG)
     * practically never shrinks, so it is skipped before decoding and encoding it for minutes.
     */
    private fun isAlreadyTiny(source: File, info: ImageInfo, target: WebpTarget): Boolean {
        val pixels = info.width.toLong() * info.height
        if (pixels <= 0L) return false
        val bytesPerPixel = source.length().toDouble() / pixels
        return bytesPerPixel < if (target is WebpTarget.Lossless) TINY_LOSSLESS_BPP else TINY_LOSSY_BPP
    }

    private fun decode(source: File, info: ImageInfo): Bitmap? = when (info.format) {
        ImageFormat.AVIF -> BitmapDecoderCompat.decode(source)

        ImageFormat.JXL -> NativeImage.open(source)?.use {
            it.decode(0, Rect(0, 0, it.width, it.height), 1, Bitmap.Config.ARGB_8888)
        }

        else -> {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                }
            }
            BitmapFactory.decodeFile(source.absolutePath, options)
        }
    }
}

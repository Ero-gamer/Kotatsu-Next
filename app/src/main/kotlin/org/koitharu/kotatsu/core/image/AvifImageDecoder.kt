package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.allowRgb565
import coil3.request.maxBitmapSize
import coil3.util.component1
import coil3.util.component2
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import kotlinx.coroutines.runInterruptible
import org.aomedia.avif.android.AvifDecoder
import org.koitharu.kotatsu.core.util.ext.ramAvailable
import org.koitharu.kotatsu.core.util.ext.readByteArrayAtMost
import java.nio.ByteBuffer

class AvifImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult = runInterruptible {
        // A file-backed source is memory-mapped (no heap copy); anything else is read into a direct buffer.
        val mapped = source.fileOrNull()?.toFile()?.mapReadOnlyOrNull()
        val head = if (mapped != null) {
            ByteArray(minOf(mapped.remaining(), HEAD_BYTES)).also { mapped.duplicate().get(it) }
        } else {
            null
        }
        val byteArray = if (mapped == null) source.source().readByteArrayAtMost() else null
        // The native libavif binding is not guaranteed to fail cleanly on a truncated/corrupt
        // buffer; reject anything whose header doesn't check out before it reaches native code.
        val info = if (head != null) {
            ImageFormatProbe.probe(head, head.size, truncated = true)
        } else {
            ImageFormatProbe.probe(checkNotNull(byteArray))
        }
        if (info.format != ImageFormat.AVIF) {
            throw ImageDecodeException(
                uri = source.fileOrNull()?.toString(),
                format = "avif",
                message = "Not a valid AVIF file",
            )
        }
        val bytes = mapped ?: ByteBuffer.allocateDirect(checkNotNull(byteArray).size).put(byteArray).rewind() as ByteBuffer
        val decoder = AvifDecoder.create(bytes) ?: throw ImageDecodeException(
            uri = source.fileOrNull()?.toString(),
            format = "avif",
            message = "Requested to decode byte buffer which cannot be handled by AvifDecoder",
        )
        try {
            // libavif needs a full-size bitmap: use 8888 (no banding for >8-bit), fall back to 565 for opaque
            // images only when 8888 does not fit the free RAM, and refuse when even that does not fit.
            val pixels = decoder.width.toLong() * decoder.height
            val free = options.context.ramAvailable
            val config = when {
                pixels * 4 <= free -> Bitmap.Config.ARGB_8888

                !decoder.alphaPresent && pixels * 2 <= free -> Bitmap.Config.RGB_565

                else -> throw ImageDecodeException(
                    uri = source.fileOrNull()?.toString(),
                    format = "avif",
                    message = "Image too large: ${decoder.width}x${decoder.height}",
                )
            }
            val bitmap = createBitmap(decoder.width, decoder.height, config)
            val result = decoder.nextFrame(bitmap)
            if (result != 0) {
                bitmap.recycle()
                throw ImageDecodeException(
                    uri = source.fileOrNull()?.toString(),
                    format = "avif",
                    message = AvifDecoder.resultToString(result),
                )
            }
            // downscaling
            val (dstWidth, dstHeight) = DecodeUtils.computeDstSize(
                srcWidth = bitmap.width,
                srcHeight = bitmap.height,
                targetSize = options.size,
                scale = options.scale,
                maxSize = options.maxBitmapSize,
            )
            if (dstWidth < bitmap.width || dstHeight < bitmap.height) {
                // Covers and thumbnails: honour Coil's allowRgb565 (opaque images only), half the cache memory.
                val scaled = if (options.allowRgb565 && !decoder.alphaPresent) {
                    scaleToRgb565(bitmap, dstWidth, dstHeight)
                } else {
                    bitmap.scale(dstWidth, dstHeight)
                }
                bitmap.recycle()
                DecodeResult(
                    image = scaled.asImage(),
                    isSampled = true,
                )
            } else {
                DecodeResult(
                    image = bitmap.asImage(),
                    isSampled = false,
                )
            }
        } finally {
            decoder.release()
            bytes.capacity() // keeps the mapping reachable until native code is done
        }
    }

    private fun scaleToRgb565(src: Bitmap, dstWidth: Int, dstHeight: Int): Bitmap {
        val out = createBitmap(dstWidth, dstHeight, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(
            src,
            null,
            Rect(0, 0, dstWidth, dstHeight),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG),
        )
        return out
    }

    private companion object {
        const val HEAD_BYTES = 64 * 1024
    }

    class Factory : Decoder.Factory {

        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? = if (isApplicable(result)) {
            AvifImageDecoder(result.source, options)
        } else {
            null
        }

        override fun equals(other: Any?) = other is Factory

        override fun hashCode() = javaClass.hashCode()

        private fun isApplicable(result: SourceFetchResult): Boolean = result.mimeType == "image/avif" ||
            // Wrong or missing type (e.g. an .avif saved as .jpg): trust the content when it is a plain file.
            result.source.fileOrNull()?.toFile()?.let { ImageFormatProbe.probe(it).format == ImageFormat.AVIF } == true
    }
}

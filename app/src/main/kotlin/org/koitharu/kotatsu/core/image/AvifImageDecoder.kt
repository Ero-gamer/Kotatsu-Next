package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.util.component1
import coil3.util.component2
import com.davemorrissey.labs.subscaleview.decoder.ImageDecodeException
import com.radzivon.vicvane.android.avif.HeifCoder // Updated Import
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.util.ext.readByteBuffer

class AvifImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    private val heifCoder = HeifCoder()

    override suspend fun decode(): DecodeResult = runInterruptible {
        val bytes = source.source().readByteBuffer()
        
        // Decoding using dav1d through HeifCoder
        val bitmap = try {
            heifCoder.decode(bytes) 
        } catch (e: Exception) {
            throw ImageDecodeException(
                uri = source.fileOrNull()?.toString(),
                format = "avif",
                message = "HeifCoder failed to decode buffer: ${e.message}",
            )
        } ?: throw ImageDecodeException(
            uri = source.fileOrNull()?.toString(),
            format = "avif",
            message = "HeifCoder returned null bitmap",
        )

        try {
            // Downscaling logic (Kept same as original for quality/performance balance)
            val (dstWidth, dstHeight) = DecodeUtils.computeDstSize(
                srcWidth = bitmap.width,
                srcHeight = bitmap.height,
                targetSize = options.size,
                scale = options.scale,
                maxSize = options.maxBitmapSize,
            )

            if (dstWidth < bitmap.width || dstHeight < bitmap.height) {
                val scaled = bitmap.scale(dstWidth, dstHeight)
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
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader
        ): Decoder? = if (isApplicable(result)) {
            AvifImageDecoder(result.source, options)
        } else {
            null
        }

        override fun equals(other: Any?) = other is Factory
        override fun hashCode() = javaClass.hashCode()

        private fun isApplicable(result: SourceFetchResult): Boolean {
            return result.mimeType == "image/avif"
        }
    }
}

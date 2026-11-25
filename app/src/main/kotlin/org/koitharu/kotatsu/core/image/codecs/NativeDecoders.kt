package org.koitharu.kotatsu.core.image.codecs

import android.graphics.Bitmap
import android.graphics.Rect
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.allowRgb565
import coil3.request.bitmapConfig
import coil3.size.Scale
import coil3.size.pxOrElse
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.image.ImageFormat
import org.koitharu.kotatsu.core.image.ImageFormatProbe

/** Largest animation frame buffers (per bitmap, in pixels); animations are scaled down to fit. */
private const val ANIMATION_MAX_PIXELS = 2_500_000
private const val ANIMATION_MAX_PIXELS_SAVER = 1_000_000

/** Animations from larger files are not played (static first frame instead). */
private const val ANIMATION_MAX_FILE = 48L * 1024 * 1024

/** Largest still JPEG XL decoded in one piece (64 MB of ARGB_8888); anything bigger is downsampled. */
private const val STILL_MAX_PIXELS = 16_000_000

/** Smallest power-of-two sample so that `width * height / sample^2 <= maxPixels`. */
internal fun sampleFor(width: Int, height: Int, maxPixels: Int): Int {
    var sample = 1
    while ((width.toLong() / sample) * (height.toLong() / sample) > maxPixels) sample *= 2
    return sample
}

/** Plays animated JPEG XL / APNG / GIF / WebP / AVIF when the request allows animation (wrap in the gating factory). */
class NativeAnimatedDecoder private constructor(
    private val image: FrameSource,
    private val config: Bitmap.Config,
    private val maxPixels: Int,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val sample = sampleFor(image.width, image.height, maxPixels)
        val drawable = NativeAnimatedDrawable(image, sample, config)
        // Not shareable: the drawable is stateful, so it must never come out of the memory cache twice.
        return DecodeResult(image = drawable.asImage(shareable = false), isSampled = sample > 1)
    }

    class Factory(private val isMemorySaver: () -> Boolean) : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val file = result.source.fileOrNull()?.toFile() ?: return null
            if (file.length() > ANIMATION_MAX_FILE) return null
            val info = ImageFormatProbe.probe(file)
            if (!info.animated) return null
            val image: FrameSource = when (info.format) {
                ImageFormat.JXL, ImageFormat.PNG, ImageFormat.GIF ->
                    if (NativeCodecs.isAvailable) NativeImage.open(file) else null

                ImageFormat.WEBP -> WebpFrameSource.open(file)

                ImageFormat.AVIF -> AvifFrameSource.open(file)

                else -> null
            } ?: return null
            if (!image.isAnimated) {
                image.close()
                return null
            }
            val saver = isMemorySaver()
            val config = if (saver && !image.hasAlpha) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
            return NativeAnimatedDecoder(
                image = image,
                config = config,
                maxPixels = if (saver) ANIMATION_MAX_PIXELS_SAVER else ANIMATION_MAX_PIXELS,
            )
        }
    }
}

/** Decodes (the first frame of) JPEG XL images for thumbnails, covers and previews. */
class NativeStillDecoder private constructor(
    private val image: NativeImage,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult = runInterruptible {
        try {
            val width = options.size.width.pxOrElse { image.width }
            val height = options.size.height.pxOrElse { image.height }
            var sample = DecodeUtils.calculateInSampleSize(image.width, image.height, width, height, Scale.FIT)
            sample = Integer.highestOneBit(sample.coerceAtLeast(1))
            sample = maxOf(sample, sampleFor(image.width, image.height, STILL_MAX_PIXELS))
            // Coil keeps `allowRgb565` (the app-wide low-RAM switch) apart from an explicit bitmapConfig.
            val config = if ((options.bitmapConfig == Bitmap.Config.RGB_565 || options.allowRgb565) && !image.hasAlpha) {
                Bitmap.Config.RGB_565
            } else {
                Bitmap.Config.ARGB_8888
            }
            val bitmap = image.decode(0, Rect(0, 0, image.width, image.height), sample, config)
                ?: throw java.io.IOException("JPEG XL decode failed")
            DecodeResult(image = bitmap.asImage(), isSampled = sample > 1)
        } finally {
            image.close()
        }
    }

    class Factory : Decoder.Factory {

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (!NativeCodecs.isAvailable) return null
            val file = result.source.fileOrNull()?.toFile() ?: return null
            if (ImageFormatProbe.probe(file).format != ImageFormat.JXL) return null
            val image = NativeImage.open(file) ?: return null
            return NativeStillDecoder(image, options)
        }
    }
}

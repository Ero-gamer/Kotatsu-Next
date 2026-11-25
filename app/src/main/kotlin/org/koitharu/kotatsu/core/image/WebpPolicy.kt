package org.koitharu.kotatsu.core.image

/**
 * Which WebP encoding a page gets when it is re-encoded (split, converted, optimized).
 *
 * Auto mode (match the source):
 *  - PNG: always lossy at [PNG_QUALITY];
 *  - any other truly lossless source (BMP, GIF, lossless WebP / AVIF / JXL): lossy at [FALLBACK_QUALITY];
 *  - lossy JPEG / WebP / AVIF / JXL: keeps the quality the source was saved with, [FALLBACK_QUALITY] when
 *    that is unknown or unreasonable (below [MIN_REASONABLE_QUALITY]).
 * Fixed mode keeps the older behaviour: PNG / BMP / GIF lossless, others the source quality or [fixedFallback].
 * The encoder itself always runs libwebp method 6 with SharpYUV (see [WebpEncoding]).
 */
object WebpPolicy {

    const val FALLBACK_QUALITY = 95
    const val PNG_QUALITY = 98
    const val MIN_REASONABLE_QUALITY = 40

    fun target(auto: Boolean, format: ImageFormat, source: SourceQuality, fixedFallback: Int): WebpTarget = if (auto) autoTarget(format, source) else fixedTarget(format, source, fixedFallback)

    fun autoTarget(format: ImageFormat, source: SourceQuality): WebpTarget = when (format) {
        ImageFormat.PNG -> WebpTarget.Lossy(PNG_QUALITY)

        ImageFormat.BMP, ImageFormat.GIF -> WebpTarget.Lossy(FALLBACK_QUALITY)

        else -> when (source) {
            SourceQuality.Lossless -> WebpTarget.Lossy(FALLBACK_QUALITY)
            is SourceQuality.Lossy -> WebpTarget.Lossy(reasonable(source.quality))
            SourceQuality.Unknown -> WebpTarget.Lossy(FALLBACK_QUALITY)
        }
    }

    fun fixedTarget(format: ImageFormat, source: SourceQuality, fallback: Int): WebpTarget = when (format) {
        ImageFormat.PNG, ImageFormat.BMP, ImageFormat.GIF -> WebpTarget.Lossless
        else -> WebpEncoding.targetFor(source, fallback)
    }

    private fun reasonable(quality: Int): Int = if (quality < MIN_REASONABLE_QUALITY) FALLBACK_QUALITY else quality.coerceAtMost(100)
}

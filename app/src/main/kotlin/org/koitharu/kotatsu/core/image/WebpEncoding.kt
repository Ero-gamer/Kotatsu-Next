package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.os.Build
import org.koitharu.kotatsu.core.image.codecs.NativeWebp
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/** What an image file was saved with, as far as its header says. */
sealed interface SourceQuality {
    /** Lossy source, estimated quality 1..100. */
    data class Lossy(val quality: Int) : SourceQuality

    data object Lossless : SourceQuality

    data object Unknown : SourceQuality
}

/** How a new WebP is encoded. */
sealed interface WebpTarget {
    data object Lossless : WebpTarget

    data class Lossy(val quality: Int) : WebpTarget
}

/**
 * WebP encoding for downloads and the optimizer. The native libwebp encoder (method 6, SharpYUV, lossless
 * alpha, near-lossless off) is used when available; otherwise the platform encoder is a fallback for
 * callers that can live without the extra control (see [isNativeAvailable]).
 */
object WebpEncoding {

    const val MAX_DIMENSION = 16383
    private const val AVIF_HEAD_BYTES = 64 * 1024

    val isNativeAvailable: Boolean
        get() = NativeWebp.isAvailable

    /** Header-only estimate of the quality [file] was encoded with. */
    fun estimateQuality(file: File): SourceQuality {
        if (!isNativeAvailable) return SourceQuality.Unknown
        val code = try {
            NativeWebp.nativeEstimateQuality(file.absolutePath)
        } catch (_: Throwable) {
            return SourceQuality.Unknown
        }
        return when (code) {
            in 1..100 -> SourceQuality.Lossy(code)
            101 -> SourceQuality.Lossless
            else -> SourceQuality.Unknown
        }
    }

    /**
     * Quality of [file] from its header: JPEG / WebP through the native estimator, AVIF through its AV1 header.
     * Other formats (PNG, JXL, ...) are [SourceQuality.Unknown] here; callers apply [WebpPolicy] to them.
     */
    fun estimateQuality(file: File, format: ImageFormat): SourceQuality = when (format) {
        ImageFormat.JPEG, ImageFormat.WEBP -> estimateQuality(file)

        ImageFormat.AVIF -> try {
            val head = ByteArray(AVIF_HEAD_BYTES)
            val n = file.inputStream().use { it.read(head).coerceAtLeast(0) }
            estimateQuality(head, n, format)
        } catch (_: java.io.IOException) {
            SourceQuality.Unknown
        }

        else -> SourceQuality.Unknown
    }

    /** Header-only estimate from an already read [head] (AVIF only; see the file overload). */
    fun estimateQuality(head: ByteArray, length: Int, format: ImageFormat): SourceQuality {
        if (format != ImageFormat.AVIF) return SourceQuality.Unknown
        val result = LossyQuality.analyze(head, length, format) ?: return SourceQuality.Unknown
        return if (result.kind == LossyQuality.Kind.LOSSLESS) {
            SourceQuality.Lossless
        } else {
            SourceQuality.Lossy(result.quality.coerceIn(1, 100))
        }
    }

    /**
     * Output encoding for an image whose source quality is [source]: lossless sources stay lossless,
     * lossy ones are encoded at (at most) the same quality, [fallback] when it is unknown.
     */
    fun targetFor(source: SourceQuality, fallback: Int): WebpTarget = when (source) {
        SourceQuality.Lossless -> WebpTarget.Lossless
        is SourceQuality.Lossy -> WebpTarget.Lossy(source.quality.coerceIn(1, 100))
        SourceQuality.Unknown -> WebpTarget.Lossy(fallback.coerceIn(1, 100))
    }

    /**
     * Encodes [bitmap] (ARGB_8888, straight alpha when [hasAlpha]) to WebP bytes, or null on failure.
     * Never recycles [bitmap].
     */
    fun encode(bitmap: Bitmap, hasAlpha: Boolean, target: WebpTarget): ByteArray? {
        if (bitmap.isRecycled || bitmap.width !in 1..MAX_DIMENSION || bitmap.height !in 1..MAX_DIMENSION) return null
        return if (isNativeAvailable && bitmap.config == Bitmap.Config.ARGB_8888 && !(hasAlpha && bitmap.isPremultiplied)) {
            encodeNative(bitmap, hasAlpha, target) ?: encodePlatform(bitmap, target)
        } else {
            encodePlatform(bitmap, target)
        }
    }

    /**
     * The last staging buffer, held weakly: a page is ~15 MB of native memory that only the GC would free, so
     * consecutive pages reuse it while it lives, and it is never kept alive when memory is short.
     */
    private val spareBuffer = AtomicReference<WeakReference<ByteBuffer>?>(null)

    private fun acquireBuffer(size: Int): ByteBuffer {
        val spare = spareBuffer.getAndSet(null)?.get()
        if (spare != null && spare.capacity() >= size) {
            return spare.clear() as ByteBuffer
        }
        return ByteBuffer.allocateDirect(size)
    }

    private fun encodeNative(bitmap: Bitmap, hasAlpha: Boolean, target: WebpTarget): ByteArray? = try {
        val buffer = acquireBuffer(bitmap.rowBytes * bitmap.height)
        bitmap.copyPixelsToBuffer(buffer)
        NativeWebp.nativeEncode(
            buffer = buffer,
            width = bitmap.width,
            height = bitmap.height,
            stride = bitmap.rowBytes,
            hasAlpha = hasAlpha,
            lossless = target is WebpTarget.Lossless,
            quality = (target as? WebpTarget.Lossy)?.quality?.toFloat() ?: 100f,
            nearLossless = 100,
            exact = false,
        ).also { spareBuffer.set(WeakReference(buffer)) }
    } catch (_: Throwable) {
        null // OutOfMemoryError / UnsatisfiedLinkError: let the caller fall back or skip
    }

    @Suppress("DEPRECATION")
    private fun encodePlatform(bitmap: Bitmap, target: WebpTarget): ByteArray? = try {
        // Before API 30 the platform has no lossless WebP: CompressFormat.WEBP at 100 is still LOSSY. A lossless
        // source is better left as it is than silently turned lossy, so the caller gets null (keeps the file).
        if (target is WebpTarget.Lossless && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val format = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP
            target is WebpTarget.Lossless -> Bitmap.CompressFormat.WEBP_LOSSLESS
            else -> Bitmap.CompressFormat.WEBP_LOSSY
        }
        val quality = if (target is WebpTarget.Lossy) target.quality else 100
        ByteArrayOutputStream().use { out ->
            if (bitmap.compress(format, quality, out)) out.toByteArray() else null
        }
    } catch (_: Throwable) {
        null
    }
}

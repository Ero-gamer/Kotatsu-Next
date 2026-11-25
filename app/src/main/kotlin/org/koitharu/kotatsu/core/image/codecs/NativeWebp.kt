package org.koitharu.kotatsu.core.image.codecs

import java.nio.ByteBuffer

/**
 * JNI surface of the libwebp encoder inside `libssivcodecs.so` (vendored libwebp 1.6.0, SharpYUV,
 * NEON / SSE / AVX2 builds selected the same way as the other native codecs). Optional: check
 * [isAvailable] before every call.
 */
object NativeWebp {

    /** Same library as [NativeCodecs]; false when the Rust build was skipped or cannot be loaded. */
    val isAvailable: Boolean
        get() = NativeCodecs.isAvailable

    /**
     * Encodes straight (non-premultiplied) RGBA pixels from a **direct** [buffer].
     * [lossless] ignores [quality]; [nearLossless] (0..100, 100 = off) and [exact] only apply to lossless.
     * Returns the WebP file bytes, or null on failure.
     */
    @JvmStatic
    external fun nativeEncode(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        stride: Int,
        hasAlpha: Boolean,
        lossless: Boolean,
        quality: Float,
        nearLossless: Int,
        exact: Boolean,
    ): ByteArray?

    /** Source quality of the image file at [path]: 1..100 lossy, 101 lossless, -1 unknown. */
    @JvmStatic
    external fun nativeEstimateQuality(path: String): Int
}

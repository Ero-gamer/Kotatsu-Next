package org.koitharu.kotatsu.core.image.codecs

import android.graphics.Bitmap
import org.koitharu.kotatsu.core.image.CpuFeatures

/**
 * JNI surface of `libssivcodecs.so` (Rust: JPEG XL, animated PNG and GIF). The library is optional: when
 * it is missing (build without a Rust toolchain, unsupported ABI) [isAvailable] is false and every
 * caller falls back to the system decoders.
 */
object NativeCodecs {

    const val FORMAT_JXL = 1
    const val FORMAT_APNG = 2
    const val FORMAT_GIF = 3
    const val FORMAT_PNG = 4

    val isAvailable: Boolean by lazy {
        // armeabi-v7a ships a NEON build next to the plain one; use it only when the CPU reports NEON
        // (CpuFeatures.hasNeon is null/false -> plain build). Other ABIs have only the plain library.
        (CpuFeatures.abi.startsWith("armeabi") && CpuFeatures.hasNeon == true && load("ssivcodecs_neon")) ||
            load("ssivcodecs")
    }

    private fun load(name: String): Boolean = try {
        System.loadLibrary(name)
        true
    } catch (_: Throwable) {
        false
    }

    /** Opens `[offset, offset + length)` of [fd] (duplicated natively). 0 on failure. */
    @JvmStatic
    external fun nativeOpen(fd: Int, offset: Long, length: Long): Long

    /** `{format, width, height, frameCount, hasAlpha, loopCount}` or null. */
    @JvmStatic
    external fun nativeInfo(handle: Long): IntArray?

    @JvmStatic
    external fun nativeFrameDuration(handle: Long, frame: Int): Int

    /** Decodes a region at 1/[sample] into [bitmap], which must have exactly the sampled size. */
    @JvmStatic
    external fun nativeDecode(
        handle: Long,
        frame: Int,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        sample: Int,
        bitmap: Bitmap,
    ): Boolean

    @JvmStatic
    external fun nativeClose(handle: Long)
}

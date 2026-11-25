package org.koitharu.kotatsu.core.image.codecs

import android.os.SystemClock
import android.util.Log
import org.koitharu.kotatsu.BuildConfig

/**
 * Which decoder serves PNG pages: the system one (Skia) or the native one (Rust `png` crate, streaming
 * region decode that continues where the previous tile stopped). The system decoder is always the fallback.
 */
object NativePngMode {

    const val AUTO = 0
    const val SKIA = 1
    const val RUST = 2

    /** Set by the reader from the "PNG decoder" preference. */
    @Volatile
    var mode: Int = AUTO

    /** Auto: whole-image decodes go native only from this many pixels on (the system decoder is fine below). */
    const val AUTO_IMAGE_MIN_PIXELS = 4_000_000L

    /** Auto: archive entries smaller than this are not extracted for the native decoder. */
    const val AUTO_ARCHIVE_MIN_BYTES = 256L * 1024

    // Not private: [timed] is a public inline function and may only touch public API.
    const val TAG = "NativePng"

    /** Runs [block] and logs how long it took in debug builds. */
    inline fun <T> timed(what: () -> String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            Log.d(TAG, "${what()}: ${SystemClock.elapsedRealtime() - start} ms")
        }
    }
}

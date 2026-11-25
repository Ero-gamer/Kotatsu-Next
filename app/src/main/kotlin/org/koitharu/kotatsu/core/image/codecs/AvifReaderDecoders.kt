package org.koitharu.kotatsu.core.image.codecs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.image.ImageFormatProbe
import org.koitharu.kotatsu.core.util.ext.ensureRamAtLeast
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Largest decoded AVIF (width * height * 4) kept whole in memory; bigger pages use the tiled PNG copy instead. */
private const val MAX_DIRECT_BYTES = 48L * 1024 * 1024

/** Android 12+ decodes AVIF in the platform; below that libavif does it here. */
private val needsLibavif: Boolean
    get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S

/**
 * The whole image of [file] through libavif, or null when it is too big for memory (the caller then falls back)
 * or cannot be decoded. Nothing is written to disk.
 */
private fun decodeAvifWhole(context: Context, file: File): Bitmap? = try {
    val info = ImageFormatProbe.probe(file)
    val bytes = info.width.toLong() * info.height * 4
    if (info.width <= 0 || info.height <= 0 || bytes > MAX_DIRECT_BYTES) {
        null
    } else {
        context.ensureRamAtLeast(bytes * 2)
        BitmapDecoderCompat.decode(file)?.also {
            // libavif hands back ARGB_8888 whose hasAlpha() stays true even for opaque images, which kept the
            // RGB_565 tile path (memory saver) from ever being taken. The header says whether there is alpha.
            if (info.hasAlpha == false && it.hasAlpha()) it.setHasAlpha(false)
        }
    }
} catch (_: IOException) {
    null
} catch (_: RuntimeException) {
    null
} catch (_: OutOfMemoryError) {
    null
}

/**
 * Serves still AVIF pages straight from one in-memory libavif decode (tiles are cropped / scaled from it), so the
 * page is never re-encoded to PNG. Everything else, and AVIFs too big to hold, go to [fallback].
 */
class AvifAwareRegionDecoder(
    private val fallback: ImageRegionDecoder,
    private val config: Bitmap.Config,
) : ImageRegionDecoder {

    private val lock = ReentrantReadWriteLock()
    private var master: Bitmap? = null

    @Volatile
    private var recycled = false

    override fun init(context: Context, uri: Uri): Point {
        if (needsLibavif) {
            val resolved = NativeSources.resolveAvif(context, uri)
            if (resolved != null) {
                val bitmap = try {
                    decodeAvifWhole(context, resolved.file)
                } finally {
                    resolved.cleanup()
                }
                if (bitmap != null) {
                    lock.write {
                        if (recycled) {
                            bitmap.recycle()
                            error("Decoder recycled")
                        }
                        master = bitmap
                    }
                    return Point(bitmap.width, bitmap.height)
                }
            }
        }
        return fallback.init(context, uri)
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        lock.read {
            val src = master ?: return fallback.decodeRegion(sRect, sampleSize)
            val s = sampleSize.coerceAtLeast(1)
            val left = sRect.left.coerceIn(0, src.width)
            val top = sRect.top.coerceIn(0, src.height)
            val right = sRect.right.coerceIn(left, src.width)
            val bottom = sRect.bottom.coerceIn(top, src.height)
            val w = (right - left + s - 1) / s
            val h = (bottom - top + s - 1) / s
            if (w <= 0 || h <= 0) throw IOException("Empty region $sRect")
            // always a copy: the caller recycles tiles, the master must survive
            val out = Bitmap.createBitmap(w, h, if (config == Bitmap.Config.RGB_565 && !src.hasAlpha()) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(src, Rect(left, top, right, bottom), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }

    override val isReady: Boolean
        get() = !recycled && (master != null || fallback.isReady)

    override val isRegionDecodeCheap: Boolean
        get() = master != null || fallback.isRegionDecodeCheap

    override fun recycle() {
        lock.write {
            recycled = true
            master?.recycle()
            master = null
        }
        fallback.recycle()
    }

    class Factory(
        private val base: DecoderFactory<out ImageRegionDecoder>,
    ) : DecoderFactory<ImageRegionDecoder> {

        override val bitmapConfig: Bitmap.Config?
            get() = base.bitmapConfig

        override fun make(): ImageRegionDecoder {
            val inner = base.make()
            return if (needsLibavif) AvifAwareRegionDecoder(inner, base.bitmapConfig ?: Bitmap.Config.ARGB_8888) else inner
        }
    }
}

/** Whole-image counterpart of [AvifAwareRegionDecoder] (small pages that need no tiling). */
class AvifAwareImageDecoder(
    private val fallback: ImageDecoder,
) : ImageDecoder {

    override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap {
        val resolved = NativeSources.resolveAvif(context, uri) ?: return fallback.decode(context, uri, sampleSize)
        try {
            val full = decodeAvifWhole(context, resolved.file) ?: return fallback.decode(context, uri, sampleSize)
            val s = sampleSize.coerceAtLeast(1)
            if (s == 1) return full
            val scaled = Bitmap.createScaledBitmap(full, (full.width + s - 1) / s, (full.height + s - 1) / s, true)
            if (scaled !== full) full.recycle()
            return scaled
        } finally {
            resolved.cleanup()
        }
    }

    class Factory(
        private val base: DecoderFactory<out ImageDecoder>,
    ) : DecoderFactory<ImageDecoder> {

        override val bitmapConfig: Bitmap.Config?
            get() = base.bitmapConfig

        override fun make(): ImageDecoder {
            val inner = base.make()
            return if (needsLibavif) AvifAwareImageDecoder(inner) else inner
        }
    }
}

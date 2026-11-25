package org.koitharu.kotatsu.core.image.codecs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import com.davemorrissey.labs.subscaleview.decoder.DecoderFactory
import com.davemorrissey.labs.subscaleview.decoder.ImageDecoder
import com.davemorrissey.labs.subscaleview.decoder.ImageRegionDecoder
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** RGB_565 has no alpha channel, so images with transparency always decode as ARGB_8888. */
private fun NativeImage.configFor(config: Bitmap.Config): Bitmap.Config = if (hasAlpha && config == Bitmap.Config.RGB_565) Bitmap.Config.ARGB_8888 else config

/**
 * Region decoder that serves JPEG XL images (and, per the "PNG decoder" setting, still PNGs) through the
 * native codecs and hands everything else to [fallback] (the regular JPEG / Skia decoder). The format check is
 * a short header read for plain files and a file-name check for archive entries, so ordinary pages pay nothing
 * noticeable. A PNG the native decoder cannot handle (interlaced, damaged) is served by [fallback] instead.
 */
class NativeAwareRegionDecoder(
    private val fallback: ImageRegionDecoder,
    private val config: Bitmap.Config,
) : ImageRegionDecoder {

    private val lock = ReentrantReadWriteLock()
    private val fallbackLock = Any()
    private var image: NativeImage? = null
    private var source: NativeSources.Resolved? = null
    private var isPng = false
    private var appContext: Context? = null
    private var sourceUri: Uri? = null

    @Volatile
    private var fallbackReady = false

    @Volatile
    private var pngBroken = false

    @Volatile
    private var recycled = false

    override fun init(context: Context, uri: Uri): Point {
        val resolved = NativeSources.resolve(context, uri)
        val opened = resolved?.let { NativeImage.open(it.file) }
        if (resolved != null && opened != null) {
            return install(resolved, opened, png = false, context, uri)
        }
        resolved?.cleanup()
        val mode = NativePngMode.mode
        if (mode != NativePngMode.SKIA) {
            val minBytes = if (mode == NativePngMode.RUST) 0L else NativePngMode.AUTO_ARCHIVE_MIN_BYTES
            val png = NativeSources.resolvePng(context, uri, minBytes)
            val pngImage = png?.let { NativeImage.open(it.file) }
            if (png != null && pngImage != null && pngImage.format == NativeCodecs.FORMAT_PNG) {
                return install(png, pngImage, png = true, context, uri)
            }
            pngImage?.close()
            png?.cleanup()
        }
        fallbackReady = true
        val size = fallback.init(context, uri)
        if (recycled) {
            // recycle() ran while the fallback was opening: release what it just acquired.
            fallback.recycle()
            error("Decoder recycled")
        }
        return size
    }

    private fun install(
        resolved: NativeSources.Resolved,
        opened: NativeImage,
        png: Boolean,
        context: Context,
        uri: Uri,
    ): Point {
        lock.write {
            if (recycled) {
                opened.close()
                resolved.cleanup()
                error("Decoder recycled")
            }
            image = opened
            source = resolved
            isPng = png
            appContext = context.applicationContext
            sourceUri = uri
        }
        return Point(opened.width, opened.height)
    }

    /** The system decoder, initialised on first use (a native PNG decode that failed starts it lazily). */
    private fun readyFallback(): ImageRegionDecoder {
        synchronized(fallbackLock) {
            if (!fallbackReady) {
                fallback.init(
                    requireNotNull(appContext) { "No source" },
                    requireNotNull(sourceUri) { "No source" },
                )
                fallbackReady = true
            }
        }
        return fallback
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        lock.read {
            val native = image ?: return readyFallback().decodeRegion(sRect, sampleSize)
            if (!isPng) {
                return native.decode(0, sRect, sampleSize, native.configFor(config))
                    ?: throw java.io.IOException("JPEG XL region decode failed")
            }
            if (!pngBroken) {
                val bitmap = NativePngMode.timed({ "region $sRect s$sampleSize" }) {
                    native.decode(0, sRect, sampleSize, native.configFor(config))
                }
                if (bitmap != null) return bitmap
                pngBroken = true
            }
            return readyFallback().decodeRegion(sRect, sampleSize)
        }
    }

    override val isReady: Boolean
        get() = !recycled && (image != null || fallback.isReady)

    // Rows are streamed top to bottom, so a random tile still costs the rows above it (like the system decoder).
    override val isRegionDecodeCheap: Boolean
        get() = if (isPng) false else image != null || fallback.isRegionDecodeCheap

    override fun recycle() {
        lock.write {
            recycled = true
            image?.close()
            image = null
            source?.cleanup()
            source = null
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
            return if (NativeCodecs.isAvailable) {
                NativeAwareRegionDecoder(inner, base.bitmapConfig ?: Bitmap.Config.ARGB_8888)
            } else {
                inner
            }
        }
    }
}

/** Whole-image counterpart of [NativeAwareRegionDecoder] (small images that need no tiling). */
class NativeAwareImageDecoder(
    private val fallback: ImageDecoder,
    private val config: Bitmap.Config,
) : ImageDecoder {

    override fun decode(context: Context, uri: Uri, sampleSize: Int): Bitmap {
        val resolved = NativeSources.resolve(context, uri)
        if (resolved == null) return decodePng(context, uri, sampleSize) ?: fallback.decode(context, uri, sampleSize)
        try {
            val native = NativeImage.open(resolved.file)
                ?: return fallback.decode(context, uri, sampleSize)
            native.use {
                return it.decode(0, Rect(0, 0, it.width, it.height), sampleSize, it.configFor(config))
                    ?: throw java.io.IOException("JPEG XL decode failed")
            }
        } finally {
            resolved.cleanup()
        }
    }

    /** A still PNG through the native decoder, or null (not a PNG / mode says system / too small / failed). */
    private fun decodePng(context: Context, uri: Uri, sampleSize: Int): Bitmap? {
        val mode = NativePngMode.mode
        if (mode == NativePngMode.SKIA) return null
        val minBytes = if (mode == NativePngMode.RUST) 0L else NativePngMode.AUTO_ARCHIVE_MIN_BYTES
        val resolved = NativeSources.resolvePng(context, uri, minBytes) ?: return null
        try {
            val native = NativeImage.open(resolved.file) ?: return null
            native.use {
                if (it.format != NativeCodecs.FORMAT_PNG) return null
                if (mode == NativePngMode.AUTO && it.width.toLong() * it.height < NativePngMode.AUTO_IMAGE_MIN_PIXELS) return null
                return NativePngMode.timed({ "image ${it.width}x${it.height} s$sampleSize" }) {
                    it.decode(0, Rect(0, 0, it.width, it.height), sampleSize, it.configFor(config))
                }
            }
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
            return if (NativeCodecs.isAvailable) {
                NativeAwareImageDecoder(inner, base.bitmapConfig ?: Bitmap.Config.ARGB_8888)
            } else {
                inner
            }
        }
    }
}

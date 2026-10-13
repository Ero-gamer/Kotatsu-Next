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

/**
 * Region decoder that serves JPEG XL images through the native codecs and hands everything else to
 * [fallback] (the regular JPEG / Skia decoder). The format check is a 12-byte header read for plain
 * files and a file-name check for archive entries, so ordinary pages pay nothing noticeable.
 */
class NativeAwareRegionDecoder(
    private val fallback: ImageRegionDecoder,
    private val config: Bitmap.Config,
) : ImageRegionDecoder {

    private val lock = ReentrantReadWriteLock()
    private var image: NativeImage? = null
    private var source: NativeSources.Resolved? = null

    @Volatile
    private var recycled = false

    override fun init(context: Context, uri: Uri): Point {
        val resolved = NativeSources.resolve(context, uri)
        val opened = resolved?.let { NativeImage.open(it.file) }
        if (resolved != null && opened != null) {
            lock.write {
                if (recycled) {
                    opened.close()
                    resolved.cleanup()
                    error("Decoder recycled")
                }
                image = opened
                source = resolved
            }
            return Point(opened.width, opened.height)
        }
        resolved?.cleanup()
        return fallback.init(context, uri)
    }

    override fun decodeRegion(sRect: Rect, sampleSize: Int): Bitmap {
        lock.read {
            val native = image ?: return fallback.decodeRegion(sRect, sampleSize)
            return native.decode(0, sRect, sampleSize, config)
                ?: throw java.io.IOException("JPEG XL region decode failed")
        }
    }

    override val isReady: Boolean
        get() = !recycled && (image != null || fallback.isReady)

    override val isRegionDecodeCheap: Boolean
        get() = image != null || fallback.isRegionDecodeCheap

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
        val resolved = NativeSources.resolve(context, uri) ?: return fallback.decode(context, uri, sampleSize)
        try {
            val native = NativeImage.open(resolved.file)
                ?: return fallback.decode(context, uri, sampleSize)
            native.use {
                return it.decode(0, Rect(0, 0, it.width, it.height), sampleSize, config)
                    ?: throw java.io.IOException("JPEG XL decode failed")
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

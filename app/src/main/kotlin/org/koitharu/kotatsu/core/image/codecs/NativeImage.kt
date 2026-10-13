package org.koitharu.kotatsu.core.image.codecs

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** An image opened by the native codecs. Thread safe; [close] waits for running decodes. */
class NativeImage private constructor(
    private var handle: Long,
    val format: Int,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val hasAlpha: Boolean,
    /** Animation repeat count, 0 = forever. */
    val loopCount: Int,
) : Closeable {

    private val lock = ReentrantReadWriteLock()

    val isAnimated: Boolean
        get() = frameCount > 1

    fun frameDurationMs(frame: Int): Int = lock.read {
        if (handle == 0L) 0 else NativeCodecs.nativeFrameDuration(handle, frame)
    }

    /** Decodes [rect] of [frame] at 1/[sample] into a new bitmap, or null on failure. */
    fun decode(frame: Int, rect: Rect, sample: Int, config: Bitmap.Config): Bitmap? {
        val s = sample.coerceAtLeast(1)
        val w = (rect.width() + s - 1) / s
        val h = (rect.height() + s - 1) / s
        if (w <= 0 || h <= 0) return null
        val bitmap = try {
            Bitmap.createBitmap(w, h, config)
        } catch (_: OutOfMemoryError) {
            return null
        }
        return if (decodeInto(frame, rect, s, bitmap)) bitmap else null
    }

    /** [bitmap] must be exactly the sampled size of [rect]. */
    fun decodeInto(frame: Int, rect: Rect, sample: Int, bitmap: Bitmap): Boolean = lock.read {
        handle != 0L && NativeCodecs.nativeDecode(
            handle,
            frame,
            rect.left,
            rect.top,
            rect.width(),
            rect.height(),
            sample.coerceAtLeast(1),
            bitmap,
        )
    }

    override fun close() {
        lock.write {
            if (handle != 0L) {
                NativeCodecs.nativeClose(handle)
                handle = 0L
            }
        }
    }

    /** Safety net for images that were handed to a framework and never closed explicitly. */
    @Suppress("ProtectedInFinal", "unused")
    protected fun finalize() {
        close()
    }

    companion object {

        /** Opens [file]; null when the native library is missing or the file is not a supported image. */
        fun open(file: File): NativeImage? {
            if (!NativeCodecs.isAvailable || !file.isFile) return null
            return try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    val handle = NativeCodecs.nativeOpen(pfd.fd, 0L, file.length())
                    if (handle == 0L) return null
                    val info = NativeCodecs.nativeInfo(handle)
                    if (info == null || info.size < INFO_SIZE) {
                        NativeCodecs.nativeClose(handle)
                        return null
                    }
                    NativeImage(
                        handle = handle,
                        format = info[0],
                        width = info[1],
                        height = info[2],
                        frameCount = info[3],
                        hasAlpha = info[4] != 0,
                        loopCount = info[5],
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

        private const val INFO_SIZE = 6
    }
}

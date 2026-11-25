package org.koitharu.kotatsu.core.image.codecs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import org.aomedia.avif.android.AvifDecoder
import org.koitharu.kotatsu.core.image.mapReadOnlyOrNull
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Animated AVIF through libavif's Java API (whole file held in one direct buffer, so size is capped). */
class AvifFrameSource private constructor(
    private val decoder: AvifDecoder,
    private val durations: IntArray,
    private val data: ByteBuffer, // held so a mapped file stays alive while libavif uses it
) : FrameSource {

    override val width: Int = decoder.width
    override val height: Int = decoder.height
    override val frameCount: Int = decoder.frameCount
    override val hasAlpha: Boolean = decoder.alphaPresent

    // libavif: n = repeat n more times, -1 / -2 = infinite / unknown.
    override val loopCount: Int = decoder.repetitionCount.let { if (it < 0) 0 else it + 1 }

    private var scratch: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun frameDurationMs(frame: Int): Int = durations.getOrElse(frame) { 100 }

    override fun decodeInto(frame: Int, sample: Int, bitmap: Bitmap): Boolean {
        if (sample <= 1 && bitmap.width == width && bitmap.height == height) {
            return decoder.nthFrame(frame, bitmap) == 0
        }
        // libavif only renders full-size bitmaps: render, then scale down.
        val full = scratch ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { scratch = it }
        if (decoder.nthFrame(frame, full) != 0) return false
        Canvas(bitmap).drawBitmap(full, null, Rect(0, 0, bitmap.width, bitmap.height), paint)
        return true
    }

    override fun close() {
        decoder.release()
        data.capacity() // the buffer must outlive the native decoder
        // Native pixel memory goes now, not whenever the GC gets to the wrapper
        scratch?.recycle()
        scratch = null
    }

    companion object {
        private const val MAX_FILE = 32L * 1024 * 1024
        private const val MAX_FRAME_BYTES = 24L * 1024 * 1024

        /** Opens [file]; null when it is too large, not decodable or has a single frame. */
        fun open(file: File): AvifFrameSource? {
            if (!file.isFile || file.length() !in 1..MAX_FILE) return null
            val bytes = file.mapReadOnlyOrNull(MAX_FILE) ?: try {
                val buffer = ByteBuffer.allocateDirect(file.length().toInt())
                file.inputStream().channel.use { while (buffer.hasRemaining() && it.read(buffer) >= 0) Unit }
                buffer.flip()
                buffer
            } catch (_: IOException) {
                return null
            } catch (_: OutOfMemoryError) {
                return null
            }
            val decoder = AvifDecoder.create(bytes) ?: return null
            val seconds = decoder.frameDurations
            // A full-size scratch bitmap is held for the whole play whenever the frames are sampled down (always
            // for big ones): refuse above ~24 MB, the still first frame is shown instead.
            if (decoder.width.toLong() * decoder.height * 4 > MAX_FRAME_BYTES ||
                decoder.frameCount < 2 || seconds == null || seconds.size < decoder.frameCount
            ) {
                decoder.release()
                return null
            }
            val ms = IntArray(decoder.frameCount) { (seconds[it] * 1000).toInt().let { d -> if (d <= 10) 100 else d } }
            return AvifFrameSource(decoder, ms, bytes)
        }
    }
}

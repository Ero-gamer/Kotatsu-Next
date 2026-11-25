package org.koitharu.kotatsu.core.image.codecs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Animated WebP: the RIFF/ANMF container is parsed here (header fields only, frame data stays in the file)
 * and every frame is decoded by the platform WebP decoder (BitmapFactory), then composed on a canvas with
 * the dispose / blend rules. Works on API 23+, needs no native library. Container layout after
 * APNG4Android's awebp (Apache-2.0) and the WebP container spec.
 */
class WebpFrameSource private constructor(
    private val file: RandomAccessFile,
    override val width: Int,
    override val height: Int,
    override val hasAlpha: Boolean,
    override val loopCount: Int,
    private val frames: Array<Frame>,
) : FrameSource {

    class Frame(
        val offset: Long,
        val length: Int,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val durationMs: Int,
        val disposeToBackground: Boolean,
        val blend: Boolean,
    )

    override val frameCount: Int get() = frames.size

    private var canvasBitmap: Bitmap? = null
    private var canvasSample = 0
    private var composed = -1 // index of the last frame drawn on canvasBitmap
    private var scratch: Bitmap? = null
    private val srcPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    private val overPaint = Paint()
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    override fun frameDurationMs(frame: Int): Int = frames.getOrNull(frame)?.durationMs ?: 0

    // Synchronized with close(): the shared canvas bitmap must not be recycled while a frame is being composed.
    @Synchronized
    override fun decodeInto(frame: Int, sample: Int, bitmap: Bitmap): Boolean {
        if (frame !in frames.indices) return false
        val s = sample.coerceAtLeast(1)
        var canvasBmp = canvasBitmap
        if (canvasBmp == null || canvasSample != s) {
            canvasBmp?.recycle()
            canvasBmp = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            canvasBitmap = canvasBmp
            canvasSample = s
            composed = -1
        }
        val canvas = Canvas(canvasBmp)
        var next = composed + 1
        if (frame != next) { // seek or loop restart: replay from the nearest frame that overwrites everything
            next = keyframeAtOrBefore(frame)
            composed = -1
        }
        while (next <= frame) {
            if (!compose(canvas, next, s)) {
                composed = -1
                return false
            }
            composed = next
            next++
        }
        Canvas(bitmap).drawBitmap(canvasBmp, 0f, 0f, srcPaint)
        return true
    }

    @Synchronized
    override fun close() {
        try {
            file.close()
        } catch (_: IOException) {
        }
        canvasBitmap?.recycle()
        canvasBitmap = null
        scratch = null
    }

    private fun keyframeAtOrBefore(index: Int): Int {
        for (i in index downTo 1) {
            val f = frames[i]
            if (f.width == width && f.height == height && !f.blend) return i
        }
        return 0
    }

    private fun compose(canvas: Canvas, index: Int, sample: Int): Boolean {
        if (index == 0 || composed < 0) {
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        } else {
            val prev = frames[index - 1]
            if (prev.disposeToBackground) canvas.drawRect(rectOf(prev, sample), clearPaint)
        }
        val f = frames[index]
        val decoded = decodeFrame(f, sample) ?: return false
        canvas.drawBitmap(decoded, (f.x / sample).toFloat(), (f.y / sample).toFloat(), if (f.blend) overPaint else srcPaint)
        return true
    }

    private fun rectOf(f: Frame, sample: Int): Rect {
        val l = f.x / sample
        val t = f.y / sample
        return Rect(l, t, l + (f.width + sample - 1) / sample, t + (f.height + sample - 1) / sample)
    }

    /** Wraps the frame's ALPH/VP8/VP8L chunks in a stand-alone extended WebP and decodes it. */
    private fun decodeFrame(f: Frame, sample: Int): Bitmap? {
        val data = ByteArray(HEADER_SIZE + f.length)
        put(data, 0, "RIFF")
        putLe32(data, 4, data.size - 8)
        put(data, 8, "WEBPVP8X")
        putLe32(data, 16, 10)
        data[20] = ALPHA_FLAG.toByte()
        putLe24(data, 24, f.width - 1)
        putLe24(data, 27, f.height - 1)
        try {
            file.seek(f.offset)
            file.readFully(data, HEADER_SIZE, f.length)
        } catch (_: IOException) {
            return null
        }
        val bmp = decode(data, sample, scratch) ?: if (scratch != null) decode(data, sample, null) else null
        if (bmp != null) scratch = bmp
        return bmp
    }

    /** Decodes [data], reusing [reuse] when given; null on failure (also when [reuse] is unusable). */
    private fun decode(data: ByteArray, sample: Int, reuse: Bitmap?): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
            inBitmap = reuse
        }
        return try {
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    companion object {
        private const val HEADER_SIZE = 30 // RIFF(12) + VP8X chunk(18)
        private const val ALPHA_FLAG = 0x10
        private const val ANIM_FLAG = 0x02
        private const val MAX_FRAMES = 20_000
        private const val MAX_CANVAS = 16384

        /** Opens [source]; null when it is not a well-formed animated WebP. */
        fun open(source: File): WebpFrameSource? {
            val raf = try {
                RandomAccessFile(source, "r")
            } catch (_: IOException) {
                return null
            }
            return try {
                parse(raf) ?: run {
                    raf.close()
                    null
                }
            } catch (_: Exception) {
                raf.close()
                null
            }
        }

        private fun parse(raf: RandomAccessFile): WebpFrameSource? {
            val length = raf.length()
            val head = ByteArray(12)
            if (length < 12) return null
            raf.readFully(head)
            if (tag(head, 0) != "RIFF" || tag(head, 8) != "WEBP") return null
            var pos = 12L
            var canvasW = 0
            var canvasH = 0
            var alpha = false
            var animated = false
            var loops = 0
            val frames = ArrayList<Frame>()
            val hdr = ByteArray(8)
            val body = ByteArray(16)
            while (pos + 8 <= length) {
                raf.seek(pos)
                raf.readFully(hdr)
                val size = le32(hdr, 4)
                if (size < 0 || pos + 8 + size > length) return null
                val data = pos + 8
                when (tag(hdr, 0)) {
                    "VP8X" -> {
                        if (size < 10) return null
                        raf.readFully(body, 0, 10)
                        animated = body[0].toInt() and ANIM_FLAG != 0
                        alpha = body[0].toInt() and ALPHA_FLAG != 0
                        canvasW = le24(body, 4) + 1
                        canvasH = le24(body, 7) + 1
                    }

                    "ANIM" -> if (size >= 6) {
                        raf.readFully(body, 0, 6)
                        loops = le16(body, 4)
                    }

                    "ANMF" -> {
                        if (!animated || size < 16 + 8 || frames.size >= MAX_FRAMES) return null
                        raf.readFully(body, 0, 16)
                        val f = Frame(
                            offset = data + 16,
                            length = (size - 16).toInt(),
                            x = le24(body, 0) * 2,
                            y = le24(body, 3) * 2,
                            width = le24(body, 6) + 1,
                            height = le24(body, 9) + 1,
                            durationMs = le24(body, 12).let { if (it <= 10) 100 else it },
                            disposeToBackground = body[15].toInt() and 1 != 0,
                            blend = body[15].toInt() and 2 == 0,
                        )
                        if (f.x + f.width > canvasW || f.y + f.height > canvasH) return null
                        frames += f
                    }
                }
                pos = data + size + (size and 1)
            }
            if (!animated || frames.size < 2 || canvasW <= 0 || canvasH <= 0) return null
            if (canvasW > MAX_CANVAS || canvasH > MAX_CANVAS) return null
            return WebpFrameSource(raf, canvasW, canvasH, alpha, loops, frames.toTypedArray())
        }

        private fun tag(b: ByteArray, o: Int) = String(b, o, 4, Charsets.ISO_8859_1)
        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        private fun le24(b: ByteArray, o: Int) = le16(b, o) or ((b[o + 2].toInt() and 0xFF) shl 16)
        private fun le32(b: ByteArray, o: Int): Long = (le24(b, o).toLong()) or ((b[o + 3].toLong() and 0xFF) shl 24)

        private fun put(b: ByteArray, o: Int, s: String) {
            for (i in s.indices) b[o + i] = s[i].code.toByte()
        }

        private fun putLe24(b: ByteArray, o: Int, v: Int) {
            b[o] = v.toByte()
            b[o + 1] = (v shr 8).toByte()
            b[o + 2] = (v shr 16).toByte()
        }

        private fun putLe32(b: ByteArray, o: Int, v: Int) {
            putLe24(b, o, v)
            b[o + 3] = (v shr 24).toByte()
        }
    }
}

package org.koitharu.kotatsu.core.image.codecs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors

/**
 * Plays an animated [NativeImage] (JPEG XL / APNG). Frames are decoded one ahead on a single shared
 * background thread into two reusable bitmaps, so playback costs two frame buffers and no per-frame
 * allocation. Only pause / resume is offered (no speed control): [start] and [stop].
 *
 * All state is touched on the main thread; the worker only fills the bitmap it was handed.
 */
class NativeAnimatedDrawable(
    private val image: NativeImage,
    private val sample: Int,
    private val config: Bitmap.Config,
) : Drawable(),
    Animatable {

    private class Decoded(val index: Int, val slot: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outWidth = (image.width + sample - 1) / sample
    private val outHeight = (image.height + sample - 1) / sample
    private val fullRect = Rect(0, 0, image.width, image.height)
    private val buffers = arrayOfNulls<Bitmap>(2)

    private var shown: Bitmap? = null
    private var shownIndex = -1
    private var shownSlot = 1
    private var shownAt = 0L
    private var pending: Decoded? = null
    private var inFlight = false
    private var running = false
    private var released = false
    private var failed = false
    private var loopsDone = 0

    private val showRunnable = Runnable { showPending() }

    override fun getIntrinsicWidth(): Int = image.width

    override fun getIntrinsicHeight(): Int = image.height

    override fun draw(canvas: Canvas) {
        val bitmap = shown
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, bounds, paint)
        } else if (!inFlight && pending == null && !released && !failed) {
            requestDecode(0) // first frame is shown even while paused
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = if (image.hasAlpha) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE

    override fun isRunning(): Boolean = running

    override fun start() {
        if (released || running || !image.isAnimated) return
        running = true
        failed = false
        loopsDone = 0
        when {
            pending != null -> scheduleShow()
            shown == null -> if (!inFlight) requestDecode(0)
            !inFlight -> requestDecode(nextIndex(shownIndex))
        }
    }

    override fun stop() {
        running = false
        handler.removeCallbacks(showRunnable)
    }

    /** Frees the native image. The drawable shows nothing afterwards. */
    fun release() {
        if (released) return
        released = true
        stop()
        shown = null
        pending = null
        // close() waits for a decode that may still be running, so keep it off the main thread.
        executor.execute { image.close() }
    }

    private fun nextIndex(index: Int): Int = if (index + 1 >= image.frameCount) 0 else index + 1

    private fun requestDecode(index: Int) {
        if (inFlight || released) return
        inFlight = true
        val slot = 1 - shownSlot
        executor.execute {
            var ok = false
            try {
                var target = buffers[slot]
                if (target == null) {
                    target = Bitmap.createBitmap(outWidth, outHeight, config)
                    buffers[slot] = target
                }
                ok = image.decodeInto(index, fullRect, sample, target)
            } catch (_: Throwable) {
                // OOM or native failure: playback ends on the current frame.
            }
            handler.post { onDecoded(Decoded(index, slot), ok) }
        }
    }

    private fun onDecoded(decoded: Decoded, ok: Boolean) {
        inFlight = false
        if (released) return
        if (!ok) {
            running = false
            failed = true
            return
        }
        pending = decoded
        if (running || shown == null) scheduleShow()
    }

    private fun scheduleShow() {
        handler.removeCallbacks(showRunnable)
        val due = if (shown == null) 0L else shownAt + frameDelay(shownIndex)
        val wait = due - SystemClock.uptimeMillis()
        if (wait <= 0) showPending() else handler.postDelayed(showRunnable, wait)
    }

    private fun showPending() {
        val next = pending ?: return
        if (released || (!running && shown != null)) return
        pending = null
        shown = buffers[next.slot]
        shownIndex = next.index
        shownSlot = next.slot
        shownAt = SystemClock.uptimeMillis()
        invalidateSelf()
        if (!running) return
        var following = next.index + 1
        if (following >= image.frameCount) {
            loopsDone++
            if (image.loopCount > 0 && loopsDone >= image.loopCount) {
                running = false
                return
            }
            following = 0
        }
        requestDecode(following)
    }

    private fun frameDelay(index: Int): Long = image.frameDurationMs(index).toLong().coerceAtLeast(MIN_FRAME_MS)

    private companion object {
        const val MIN_FRAME_MS = 20L

        // One worker for every animation: decoding is serialised, so only one animation costs CPU.
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "native-anim").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}

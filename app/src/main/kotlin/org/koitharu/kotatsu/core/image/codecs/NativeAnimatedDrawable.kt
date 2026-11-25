package org.koitharu.kotatsu.core.image.codecs

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Plays an animated [FrameSource] (JPEG XL / APNG / GIF / WebP / AVIF). Frames are decoded one ahead on a single shared
 * background thread into two reusable bitmaps, so playback costs two frame buffers and no per-frame
 * allocation. Small animations additionally keep every decoded frame (see [MAX_CACHE_BYTES]) so loops,
 * scrubbing and frame steps cost no decode at all.
 *
 * All state is touched on the main thread; the worker only fills the bitmap it was handed.
 */
class NativeAnimatedDrawable(
    private val image: FrameSource,
    private val sample: Int,
    private val config: Bitmap.Config,
) : Drawable(),
    Animatable {

    /** [slot] is the double-buffer slot, or -1 for a frame that lives in the frame cache. */
    private class Decoded(val index: Int, val slot: Int, val bitmap: Bitmap?)

    private val handler = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outWidth = (image.width + sample - 1) / sample
    private val outHeight = (image.height + sample - 1) / sample
    private val buffers = arrayOfNulls<Bitmap>(2)

    // Every decoded frame, kept only when the whole animation fits the budgets; null = decode each time.
    private var frameCache: Array<Bitmap?>? = null
    private var cacheBytes = 0L

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
    private var seekIndex = -1

    /** Frame count, for a seek bar. */
    val frameCount: Int get() = image.frameCount

    /** Index of the frame on screen (0 until the first one is decoded). */
    val currentFrame: Int get() = shownIndex.coerceAtLeast(0)

    /** Called on the main thread when playback starts/stops (also at the natural end) and per shown frame. */
    interface Listener {
        fun onStateChanged()

        fun onFrameShown(index: Int)

        /** The last frame was shown (once per play), also when it ends the playback. */
        fun onLoopCompleted() = Unit
    }

    init {
        val frames = image.frameCount
        val bytes = outWidth.toLong() * outHeight * (if (config == Bitmap.Config.RGB_565) 2 else 4) * frames
        if (frames > 1 && bytes in 1..MAX_CACHE_BYTES && reserveCache(bytes)) {
            frameCache = arrayOfNulls(frames)
            cacheBytes = bytes
        }
    }

    var listener: Listener? = null

    /** Playback speed multiplier (0.25..4); only scales the frame delays. */
    var speed: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SPEED, MAX_SPEED)
        }

    /** Loop override: -1 = as in the file, 0 = forever, N = N plays. */
    var loopLimit: Int = -1

    // Start time of every frame (file timing, not scaled by speed); computed once on first use.
    private val offsets: LongArray by lazy {
        val result = LongArray(image.frameCount + 1)
        for (i in 0 until image.frameCount) result[i + 1] = result[i] + image.frameDurationMs(i).toLong().coerceAtLeast(MIN_FRAME_MS)
        result
    }

    /** File time at the start of [frame], in ms. */
    fun elapsedMs(frame: Int): Long = offsets[frame.coerceIn(0, image.frameCount)]

    /** Duration of one play at 1x, in ms. */
    val totalMs: Long get() = offsets[image.frameCount]

    /** Pauses and moves [delta] frames from the one on screen (wraps around). */
    fun step(delta: Int) {
        val n = image.frameCount
        if (n > 1) seekTo(((currentFrame + delta) % n + n) % n)
    }

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
        seekIndex = -1
        listener?.onStateChanged()
        when {
            pending != null -> scheduleShow()
            shown == null -> if (!inFlight) requestDecode(0)
            !inFlight -> requestDecode(nextIndex(shownIndex))
        }
    }

    override fun stop() {
        val wasRunning = running
        running = false
        handler.removeCallbacks(showRunnable)
        if (wasRunning) listener?.onStateChanged()
    }

    /** Pauses and shows frame [index] (scrubbing); stale requests are replaced by the latest one. */
    fun seekTo(index: Int) {
        if (released || !image.isAnimated) return
        val target = index.coerceIn(0, image.frameCount - 1)
        stop()
        pending = null
        if (target == shownIndex && !inFlight) return
        seekIndex = target
        failed = false
        if (!inFlight) requestDecode(target)
    }

    /** Copy of the frame on screen (sampled size, ARGB_8888) for saving; null if none or out of memory. */
    fun snapshot(): Bitmap? {
        val source = shown ?: return null
        return try {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        }
    }

    /** Frees the native image. The drawable shows nothing afterwards. */
    fun release() {
        if (released) return
        released = true
        listener = null
        stop()
        shown = null
        pending = null
        dropCache()
        // close() waits for a decode that may still be running, so keep it off the main thread.
        executor.execute { image.close() }
    }

    /** Frees the cached frames (memory pressure); playback goes on with plain double-buffer decoding. */
    fun trimCache() = dropCache()

    private fun dropCache() {
        if (cacheBytes > 0) {
            cachedTotal.addAndGet(-cacheBytes)
            cacheBytes = 0
        }
        frameCache = null
    }

    private fun reserveCache(bytes: Long): Boolean {
        while (true) {
            val now = cachedTotal.get()
            if (now + bytes > MAX_TOTAL_CACHE_BYTES) return false
            if (cachedTotal.compareAndSet(now, now + bytes)) return true
        }
    }

    private fun nextIndex(index: Int): Int = if (index + 1 >= image.frameCount) 0 else index + 1

    private fun requestDecode(index: Int) {
        if (inFlight || released) return
        inFlight = true
        val cache = frameCache
        val cached = cache?.get(index)
        if (cached != null) {
            // Already decoded in an earlier loop: no work for the decoder, same async path as a decode.
            handler.post { onDecoded(Decoded(index, -1, cached), true) }
            return
        }
        val slot = if (cache != null) -1 else 1 - shownSlot
        executor.execute {
            var ok = false
            var target: Bitmap? = null
            try {
                target = if (slot < 0) {
                    Bitmap.createBitmap(outWidth, outHeight, config)
                } else {
                    buffers[slot] ?: Bitmap.createBitmap(outWidth, outHeight, config).also { buffers[slot] = it }
                }
                ok = image.decodeInto(index, sample, target)
            } catch (_: Throwable) {
                // OOM or native failure: playback ends on the current frame.
            }
            val frame = target
            handler.post {
                if (ok && slot < 0 && frame != null) cache?.set(index, frame)
                onDecoded(Decoded(index, slot, frame), ok)
            }
        }
    }

    private fun onDecoded(decoded: Decoded, ok: Boolean) {
        inFlight = false
        if (released) return
        if (!ok) {
            val wasRunning = running
            running = false
            failed = true
            seekIndex = -1
            if (wasRunning) listener?.onStateChanged()
            return
        }
        if (seekIndex >= 0) {
            if (decoded.index != seekIndex) {
                requestDecode(seekIndex) // the scrub moved on while this one decoded
                return
            }
            seekIndex = -1
            pending = decoded
            showPending(force = true)
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

    private fun showPending(force: Boolean = false) {
        val next = pending ?: return
        if (released || (!running && shown != null && !force)) return
        pending = null
        shown = next.bitmap
        shownIndex = next.index
        if (next.slot >= 0) shownSlot = next.slot
        shownAt = SystemClock.uptimeMillis()
        invalidateSelf()
        listener?.onFrameShown(next.index)
        if (!running) return
        var following = next.index + 1
        if (following >= image.frameCount) {
            loopsDone++
            listener?.onLoopCompleted()
            val limit = if (loopLimit >= 0) loopLimit else image.loopCount
            if (limit > 0 && loopsDone >= limit) {
                running = false
                listener?.onStateChanged()
                return
            }
            following = 0
        }
        requestDecode(following)
    }

    private fun frameDelay(index: Int): Long = (image.frameDurationMs(index) / speed).toLong().coerceAtLeast(MIN_FRAME_MS)

    private companion object {
        const val MIN_FRAME_MS = 20L
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f

        // Frame cache budgets: one animation, and all animations alive together.
        const val MAX_CACHE_BYTES = 16L * 1024 * 1024
        const val MAX_TOTAL_CACHE_BYTES = 32L * 1024 * 1024
        val cachedTotal = AtomicLong()

        // One worker for every animation: decoding is serialised, so only one animation costs CPU.
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "native-anim").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }
}

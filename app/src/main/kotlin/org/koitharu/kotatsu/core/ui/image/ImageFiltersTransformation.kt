package org.koitharu.kotatsu.core.ui.image

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Coil3 [Transformation] used **only for the before/after preview bitmap** in
 * [ColorFilterConfigActivity]. It is NOT used for reader tile decoding — actual tile
 * post-processing is done by the GPU shader in [GpuTileRenderer].
 *
 * Applies lightweight CPU sharpening (USM unsharp-mask, no neighbour OOM risk at preview size)
 * and vibrance (per-pixel channel mix). Both are single-pass, allocation-minimal, and safe on
 * a thumbnail-scale bitmap (preview is loaded at screen width, not full JPEG size).
 *
 * **Removed:** Denoise, Dither, and Grain CPU loops — these caused OOM on full-res tile bitmaps
 * and produced no perceptible benefit at small preview scale. All three are now GPU-only.
 */
class ImageFiltersTransformation(
    private val sharpening: Float,
    private val vibrance: Float = 0f,
) : Transformation() {

    override val cacheKey: String = "img_filters_s${sharpening}_v${vibrance}_v9_gpu"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val doSharpen  = sharpening > 0.01f
        val doVibrance = vibrance != 0f
        if (!doSharpen && !doVibrance) return input
        return cpuSemaphore.withPermit { process(input, doSharpen, doVibrance) }
    }

    /**
     * Always copies [input] — even when it is already ARGB_8888 and mutable. Coil
     * deliberately hands transformations mutable bitmaps for in-place editing, but here
     * [input] is *also* the same object held by the caller and bound directly to an
     * ImageView (see [ColorFilterConfigActivity]'s `sourceBitmap`). Mutating it in place
     * would race the main thread's concurrent rendering of that exact Bitmap and corrupt
     * it permanently for every subsequent preview render. A copy is the only safe option.
     */
    private fun process(input: Bitmap, doSharpen: Boolean, doVibrance: Boolean): Bitmap {
        val w = input.width
        val h = input.height
        if (w < 3 || h < 3) return input

        val working = input.copy(Bitmap.Config.ARGB_8888, true)

        val src = IntArray(w * h)
        working.getPixels(src, 0, w, 0, 0, w, h)

        val k   = if (doSharpen) kernelStrength(sharpening) else 0f
        // When sharpening is active, write to a separate buffer to avoid corrupt neighbours.
        val out = if (doSharpen) IntArray(w * h) else src

        for (y in 0 until h) {
            val rowStart    = y * w
            val hasAbove    = y > 0
            val hasBelow    = y < h - 1
            for (x in 0 until w) {
                val idx = rowStart + x
                val px  = src[idx]
                var r: Int
                var g: Int
                var b: Int

                if (doSharpen && x > 0 && x < w - 1 && hasAbove && hasBelow) {
                    val top    = src[idx - w]
                    val bottom = src[idx + w]
                    val left   = src[idx - 1]
                    val right  = src[idx + 1]
                    val cr = (px    shr 16) and 0xFF
                    val cg = (px    shr  8) and 0xFF
                    val cb =  px           and 0xFF
                    val tr = (top   shr 16) and 0xFF; val tg = (top   shr 8) and 0xFF; val tb = top   and 0xFF
                    val br = (bottom shr 16) and 0xFF; val bg = (bottom shr 8) and 0xFF; val bb = bottom and 0xFF
                    val lr = (left  shr 16) and 0xFF; val lg = (left  shr 8) and 0xFF; val lb = left  and 0xFF
                    val rr = (right shr 16) and 0xFF; val rg = (right shr 8) and 0xFF; val rb = right and 0xFF
                    r = sharpenChannel(cr, tr, br, lr, rr, k)
                    g = sharpenChannel(cg, tg, bg, lg, rg, k)
                    b = sharpenChannel(cb, tb, bb, lb, rb, k)
                } else {
                    r = (px shr 16) and 0xFF
                    g = (px shr  8) and 0xFF
                    b =  px         and 0xFF
                }

                if (doVibrance) {
                    val factor = vibranceFactor(r, g, b, vibrance)
                    if (factor != 1f) {
                        val mean = (r + g + b) / 3f
                        r = clamp255(mean + (r - mean) * factor)
                        g = clamp255(mean + (g - mean) * factor)
                        b = clamp255(mean + (b - mean) * factor)
                    }
                }

                out[idx] = (px and ALPHA_MASK) or (r shl 16) or (g shl 8) or b
            }
        }

        working.setPixels(out, 0, w, 0, 0, w, h)
        return working
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ImageFiltersTransformation &&
            sharpening == other.sharpening &&
            vibrance   == other.vibrance
    }

    override fun hashCode(): Int = 31 * sharpening.hashCode() + vibrance.hashCode()

    private companion object {
        private val cpuSemaphore = Semaphore(1)
        private const val ALPHA_MASK = 0xFF000000.toInt()

        private fun clamp255(v: Float): Int = when {
            v <= 0f   -> 0
            v >= 255f -> 255
            else      -> (v + 0.5f).toInt()
        }

        /** USM-style sharpening kernel strength mapped from [0,1] to [1.5, 4.0]. */
        private fun kernelStrength(s: Float): Float = 1.5f + s * 2.5f

        /** 5-tap cross USM: centre - (average of 4 orthogonal neighbours) * k. */
        private fun sharpenChannel(c: Int, t: Int, b: Int, l: Int, r: Int, k: Float): Int {
            val laplacian = (4 * c - t - b - l - r).toFloat()
            return clamp255(c + laplacian * k / 4f)
        }

        /**
         * Per-pixel vibrance factor: boosts muted colours (low saturation) more than vivid ones.
         * Returns 1.0 when colour is perfectly neutral (no-op branch in the hot loop).
         */
        private fun vibranceFactor(r: Int, g: Int, b: Int, vibrance: Float): Float {
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val sat  = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC
            return 1f + vibrance * (1f - sat)
        }
    }
}

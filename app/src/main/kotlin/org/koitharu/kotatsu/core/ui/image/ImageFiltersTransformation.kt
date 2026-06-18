package org.koitharu.kotatsu.core.ui.image

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Coil3 [Transformation] that bakes bitmap-level filters into the page bitmap once, so the
 * result can be cached to disk and SSIV never has to re-process it on every tile/scroll.
 *
 * Both sharpness and vibrance are pure-CPU, allocation-light, single-pass operations — no GPU/
 * GL involved (see [SharpnessProcessor] and [VibranceProcessor] for why each needed to move
 * off the GPU/ColorMatrix approaches that were tried first). When both are active they run in
 * ONE combined getPixels -> loop -> setPixels pass instead of two separate passes, halving the
 * extra IntArray allocations and avoiding reading the bitmap twice.
 *
 * All other filters (contrast, saturation, brightness) remain real-time ColorMatrix paint
 * filters on SSIV — no bitmap processing needed for those.
 */
class ImageFiltersTransformation(
    private val sharpening: Float,
    private val vibrance: Float = 0f,
) : Transformation() {

    override val cacheKey: String = "img_filters_s${sharpening}_v${vibrance}_v7_cpu"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val doSharpen = sharpening > 0.01f
        val doVibrance = vibrance != 0f
        if (!doSharpen && !doVibrance) return input

        return cpuSemaphore.withPermit { process(input, doSharpen, doVibrance) }
    }

    private fun process(input: Bitmap, doSharpen: Boolean, doVibrance: Boolean): Bitmap {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) return input

        val needsCopy = input.config != Bitmap.Config.ARGB_8888 || !input.isMutable
        val working = if (needsCopy) input.copy(Bitmap.Config.ARGB_8888, true) else input

        val src = IntArray(w * h)
        working.getPixels(src, 0, w, 0, 0, w, h)

        val k = if (doSharpen) SharpnessProcessor.kernelStrength(sharpening) else 0f
        val v = vibrance

        // Sharpening reads neighbours from `src` while writing elsewhere, so the read and
        // write buffers must differ — sharing one would corrupt not-yet-processed neighbours.
        // When sharpening is off, vibrance has no neighbour dependency and can safely mutate
        // `src` in place, skipping a second w*h allocation entirely.
        val out = if (doSharpen) IntArray(w * h) else src

        for (y in 0 until h) {
            val rowStart = y * w
            val hasRowAbove = y > 0
            val hasRowBelow = y < h - 1
            for (x in 0 until w) {
                val idx = rowStart + x
                val px = src[idx]
                var r: Int
                var g: Int
                var b: Int

                if (doSharpen && x > 0 && x < w - 1 && hasRowAbove && hasRowBelow) {
                    val top = src[idx - w]
                    val bottom = src[idx + w]
                    val left = src[idx - 1]
                    val right = src[idx + 1]
                    r = SharpnessProcessor.sharpenChannel(
                        (px shr 16) and 0xFF, (top shr 16) and 0xFF, (bottom shr 16) and 0xFF,
                        (left shr 16) and 0xFF, (right shr 16) and 0xFF, k,
                    )
                    g = SharpnessProcessor.sharpenChannel(
                        (px shr 8) and 0xFF, (top shr 8) and 0xFF, (bottom shr 8) and 0xFF,
                        (left shr 8) and 0xFF, (right shr 8) and 0xFF, k,
                    )
                    b = SharpnessProcessor.sharpenChannel(
                        px and 0xFF, top and 0xFF, bottom and 0xFF, left and 0xFF, right and 0xFF, k,
                    )
                } else {
                    // Border pixels (1px edge) are left un-sharpened — negligible visual impact
                    // on manga pages (usually blank margins) and avoids bounds-check branching
                    // for every interior pixel.
                    r = (px shr 16) and 0xFF
                    g = (px shr 8) and 0xFF
                    b = px and 0xFF
                }

                if (doVibrance) {
                    val factor = VibranceProcessor.vibranceFactor(r, g, b, v)
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

    private fun clamp255(v: Float): Int = when {
        v <= 0f -> 0
        v >= 255f -> 255
        else -> (v + 0.5f).toInt()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ImageFiltersTransformation && sharpening == other.sharpening && vibrance == other.vibrance
    }

    override fun hashCode(): Int = 31 * sharpening.hashCode() + vibrance.hashCode()

    companion object {
        // Single-flight guard: bounds peak memory (each call holds 1-2 extra w*h IntArrays)
        // when prefetching multiple pages around a settings change. No shared mutable state
        // to protect anymore (no GL context) — this is purely a memory-pressure limiter for
        // the 2GB-class hardware this targets.
        private val cpuSemaphore = Semaphore(1)

        private const val ALPHA_MASK = 0xFF000000.toInt()
    }
}

package org.koitharu.kotatsu.core.ui.image

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

/**
 * Coil3 [Transformation] that bakes bitmap-level filters into the page bitmap for the
 * ColorFilterConfigActivity preview. Mirrors the pipeline in
 * [com.davemorrissey.labs.subscaleview.decoder.FilteringRegionDecoder] exactly —
 * keep both in sync when changing filter math or constants.
 *
 * Pipeline order per pixel: denoise -> sharpen -> vibrance -> dither+grain.
 * Denoise always runs when sharpening is active. Dither+grain always run when
 * either sharpening or vibrance is active.
 */
class ImageFiltersTransformation(
    private val sharpening: Float,
    private val vibrance: Float = 0f,
) : Transformation() {

    // Bumped to v8_cpu: denoise + dither/grain added — old cached results are stale.
    override val cacheKey: String = "img_filters_s${sharpening}_v${vibrance}_v8_cpu"

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
                    val top    = src[idx - w]
                    val bottom = src[idx + w]
                    val left   = src[idx - 1]
                    val right  = src[idx + 1]
                    val tr  = (top    shr 16) and 0xFF; val tg  = (top    shr 8) and 0xFF; val tb  = top    and 0xFF
                    val brr = (bottom shr 16) and 0xFF; val bg  = (bottom shr 8) and 0xFF; val bb  = bottom and 0xFF
                    val lr  = (left   shr 16) and 0xFF; val lg  = (left   shr 8) and 0xFF; val lb  = left   and 0xFF
                    val rr  = (right  shr 16) and 0xFF; val rg  = (right  shr 8) and 0xFF; val rb  = right  and 0xFF
                    val cr  = (px     shr 16) and 0xFF; val cg  = (px     shr 8) and 0xFF; val cb  = px     and 0xFF

                    // Denoise before sharpen: edge-aware bilateral-lite blend so the Laplacian
                    // amplifies real edges rather than JPEG/compression noise.
                    val dr = SharpnessProcessor.denoiseChannel(cr, tr, brr, lr, rr)
                    val dg = SharpnessProcessor.denoiseChannel(cg, tg, bg, lg, rg)
                    val db = SharpnessProcessor.denoiseChannel(cb, tb, bb, lb, rb)

                    r = SharpnessProcessor.sharpenChannel(dr, tr, brr, lr, rr, k)
                    g = SharpnessProcessor.sharpenChannel(dg, tg, bg, lg, rg, k)
                    b = SharpnessProcessor.sharpenChannel(db, tb, bb, lb, rb, k)
                } else {
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

                // Dither+grain: tiny luma-only signed delta from a precomputed 64×64 table
                // combining an 8×8 ordered Bayer dither pattern (reduces banding from
                // float→int rounding above) with light seeded random grain. Same delta
                // on r/g/b → no chroma speckle. Indexed by in-image position mod 64.
                val noise = DITHER_GRAIN[((y and 63) shl 6) or (x and 63)]
                if (noise != 0) {
                    r = (r + noise).coerceIn(0, 255)
                    g = (g + noise).coerceIn(0, 255)
                    b = (b + noise).coerceIn(0, 255)
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
        // Single-flight guard: bounds peak memory when prefetching multiple pages.
        private val cpuSemaphore = Semaphore(1)

        private const val ALPHA_MASK = 0xFF000000.toInt()
        private const val DITHER_AMPLITUDE = 3f
        private const val GRAIN_AMPLITUDE = 5f

        /**
         * 64×64 precomputed dither+grain table (deterministic seed 0xC0FFEE).
         * Combines an 8×8 ordered Bayer dither pattern (tiled 8×) with light random grain.
         * Identical to the table in FilteringRegionDecoder — keep them in sync.
         */
        private val DITHER_GRAIN: IntArray = buildDitherGrainTable()

        private fun buildDitherGrainTable(): IntArray {
            val bayer8 = intArrayOf(
                 0, 32,  8, 40,  2, 34, 10, 42,
                48, 16, 56, 24, 50, 18, 58, 26,
                12, 44,  4, 36, 14, 46,  6, 38,
                60, 28, 52, 20, 62, 30, 54, 22,
                 3, 35, 11, 43,  1, 33,  9, 41,
                51, 19, 59, 27, 49, 17, 57, 25,
                15, 47,  7, 39, 13, 45,  5, 37,
                63, 31, 55, 23, 61, 29, 53, 21,
            )
            val random = Random(0xC0FFEE)
            val table = IntArray(64 * 64)
            for (y in 0 until 64) {
                for (x in 0 until 64) {
                    val bayerValue = bayer8[(y % 8) * 8 + (x % 8)]
                    val ditherBias = (bayerValue / 63f - 0.5f) * DITHER_AMPLITUDE
                    val grainBias = (random.nextFloat() - 0.5f) * GRAIN_AMPLITUDE
                    table[y * 64 + x] = (ditherBias + grainBias).let {
                        when {
                            it <= -8f -> -8
                            it >= 8f  ->  8
                            else      -> Math.round(it)
                        }
                    }
                }
            }
            return table
        }
    }
}

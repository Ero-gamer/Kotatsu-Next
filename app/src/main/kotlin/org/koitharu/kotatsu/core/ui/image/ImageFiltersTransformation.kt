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
    private val denoise: Float = 0f,
    private val dither: Float = 0f,
    private val grain: Float = 0f,
) : Transformation() {

    override val cacheKey: String =
        "img_filters_s\${sharpening}_v\${vibrance}_dn\${denoise}_dt\${dither}_gr\${grain}_v8_cpu"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val doSharpen  = sharpening > 0.01f
        val doVibrance = vibrance != 0f
        val doDenoise  = denoise > 0.01f && doSharpen
        val doDither   = dither > 0.01f
        val doGrain    = grain > 0.01f
        if (!doSharpen && !doVibrance && !doDither && !doGrain) return input
        return cpuSemaphore.withPermit { process(input, doSharpen, doVibrance, doDenoise, doDither, doGrain) }
    }

    private fun process(input: Bitmap, doSharpen: Boolean, doVibrance: Boolean, doDenoise: Boolean, doDither: Boolean, doGrain: Boolean): Bitmap {
        val w = input.width
        val h = input.height
        if (w <= 0 || h <= 0) return input

        val needsCopy = input.config != Bitmap.Config.ARGB_8888 || !input.isMutable
        val working = if (needsCopy) input.copy(Bitmap.Config.ARGB_8888, true) else input

        val src = IntArray(w * h)
        working.getPixels(src, 0, w, 0, 0, w, h)

        val k = if (doSharpen) SharpnessProcessor.kernelStrength(sharpening) else 0f
        val v = vibrance
        val dgTable = if (doDither || doGrain) buildDitherGrainTable(dither, grain) else null

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
                    val top=src[idx-w]; val bottom=src[idx+w]; val left=src[idx-1]; val right=src[idx+1]
                    val cr=(px shr 16)and 0xFF; val cg=(px shr 8)and 0xFF; val cb=px and 0xFF
                    val tr=(top shr 16)and 0xFF;   val tg=(top shr 8)and 0xFF;   val tb=top and 0xFF
                    val brr=(bottom shr 16)and 0xFF;val brg=(bottom shr 8)and 0xFF;val brb=bottom and 0xFF
                    val lr=(left shr 16)and 0xFF;  val lg=(left shr 8)and 0xFF;  val lb=left and 0xFF
                    val rr=(right shr 16)and 0xFF; val rg=(right shr 8)and 0xFF; val rb=right and 0xFF
                    val dr=if(doDenoise) SharpnessProcessor.denoiseChannel(cr,tr,brr,lr,rr) else cr
                    val dg=if(doDenoise) SharpnessProcessor.denoiseChannel(cg,tg,brg,lg,rg) else cg
                    val db=if(doDenoise) SharpnessProcessor.denoiseChannel(cb,tb,brb,lb,rb) else cb
                    r=SharpnessProcessor.sharpenChannel(dr,tr,brr,lr,rr,k)
                    g=SharpnessProcessor.sharpenChannel(dg,tg,brg,lg,rg,k)
                    b=SharpnessProcessor.sharpenChannel(db,tb,brb,lb,rb,k)
                } else {
                    r=(px shr 16)and 0xFF; g=(px shr 8)and 0xFF; b=px and 0xFF
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

                if (dgTable!=null){val n=dgTable[((y and 63) shl 6)or(x and 63)];if(n!=0){r=(r+n).coerceIn(0,255);g=(g+n).coerceIn(0,255);b=(b+n).coerceIn(0,255)}}
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
        return other is ImageFiltersTransformation && sharpening==other.sharpening && vibrance==other.vibrance &&
            denoise==other.denoise && dither==other.dither && grain==other.grain
    }
    override fun hashCode(): Int = listOf(sharpening,vibrance,denoise,dither,grain).fold(0){h,v->31*h+v.hashCode()}

    companion object {
        // Single-flight guard: bounds peak memory (each call holds 1-2 extra w*h IntArrays)
        // when prefetching multiple pages around a settings change. No shared mutable state
        // to protect anymore (no GL context) — this is purely a memory-pressure limiter for
        // the 2GB-class hardware this targets.
        private val cpuSemaphore = Semaphore(1)

        private const val ALPHA_MASK = 0xFF000000.toInt()

        private fun buildDitherGrainTable(ditherAmp: Float, grainAmp: Float): IntArray {
            val bayer8 = intArrayOf(0,32,8,40,2,34,10,42,48,16,56,24,50,18,58,26,12,44,4,36,14,46,6,38,60,28,52,20,62,30,54,22,3,35,11,43,1,33,9,41,51,19,59,27,49,17,57,25,15,47,7,39,13,45,5,37,63,31,55,23,61,29,53,21)
            val rand = java.util.Random(0xC0FFEEL)
            val dMax = ditherAmp * 3f; val gMax = grainAmp * 5f
            return IntArray(64*64){i->
                val x=i and 63; val y=i shr 6
                ((bayer8[(y%8)*8+(x%8)]/63f-0.5f)*dMax+(rand.nextFloat()-0.5f)*gMax).toInt().coerceIn(-8,8)
            }
        }
    }
}

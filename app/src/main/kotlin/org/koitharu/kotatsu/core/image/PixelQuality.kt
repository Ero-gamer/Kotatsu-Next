package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Finds earlier JPEG compression from pixels alone ("JPEG ghost"): a sample of the image is recompressed
 * at every JPEG quality; the error curve dips sharply at the quality it was last compressed with, as long
 * as the 8x8 block grid is intact (no resize / crop off the grid). Works for JPEG files, and for PNG / WebP /
 * other files that were made from a JPEG. Flat blocks are ignored, only luma is compared.
 */
object PixelQuality {

    enum class Status { FOUND, NOT_FOUND, INCONCLUSIVE }

    /** [quality] 20..97 and [score] 0..1 (strength of the dip) are only meaningful for [Status.FOUND]. */
    class Result(val status: Status, val quality: Int = 0, val score: Float = 0f)

    private const val SIZE = 384
    private const val MIN_SIZE = 64
    private const val Q_MIN = 20
    private const val Q_MAX = 97
    private const val FLAT_VARIANCE = 9 // luma variance of an 8x8 block below which it is "flat"
    private const val MIN_NON_FLAT = 0.10f
    private const val GOOD_NON_FLAT = 0.30f
    private const val MIN_SCORE = 0.15f
    private const val MIN_LEVEL = 0.01 // mean error below this: nothing to measure

    /** Sample centers as fractions of the free space; the first one with enough detail wins. */
    private val SPOTS = arrayOf(0.5f to 0.5f, 0.3f to 0.3f, 0.7f to 0.7f, 0.3f to 0.7f, 0.7f to 0.3f)

    /** Returns null when the image cannot be region-decoded here (AVIF / JXL on old Android, animated, broken). */
    suspend fun analyze(data: ByteArray): Result? {
        val decoder = try {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(data, 0, data.size, false)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        try {
            val side = minOf(SIZE, decoder.width and 15.inv(), decoder.height and 15.inv())
            if (side < MIN_SIZE) return Result(Status.INCONCLUSIVE)
            var luma: IntArray? = null
            var bestFraction = -1f
            var mask = BooleanArray(0)
            for ((fx, fy) in SPOTS) {
                currentCoroutineContext().ensureActive()
                val x = ((decoder.width - side) * fx).toInt() and 15.inv()
                val y = ((decoder.height - side) * fy).toInt() and 15.inv()
                val bitmap = decoder.decodeRegion(Rect(x, y, x + side, y + side), null) ?: continue
                if (bitmap.width != side || bitmap.height != side) {
                    bitmap.recycle()
                    continue
                }
                val l = lumaOf(bitmap, side)
                bitmap.recycle()
                val m = flatMask(l, side)
                val fraction = m.count { it }.toFloat() / m.size
                if (fraction > bestFraction) {
                    bestFraction = fraction
                    luma = l
                    mask = m
                }
                if (fraction >= GOOD_NON_FLAT) break
            }
            val y = luma ?: return null
            if (bestFraction < MIN_NON_FLAT) return Result(Status.INCONCLUSIVE)
            return detect(errorCurve(y, mask, side))
        } catch (_: IOException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: OutOfMemoryError) {
            return null
        } finally {
            decoder.recycle()
        }
    }

    private fun lumaOf(bitmap: Bitmap, side: Int): IntArray {
        val px = IntArray(side * side)
        bitmap.getPixels(px, 0, side, 0, 0, side, side)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (77 * ((c shr 16) and 255) + 150 * ((c shr 8) and 255) + 29 * (c and 255)) shr 8
        }
        return px
    }

    /** True for every 8x8 block (row-major) that has enough detail to measure. */
    private fun flatMask(luma: IntArray, side: Int): BooleanArray {
        val blocks = side / 8
        return BooleanArray(blocks * blocks) { b ->
            val bx = (b % blocks) * 8
            val by = (b / blocks) * 8
            var sum = 0
            var sumSq = 0
            for (j in 0 until 8) {
                val row = (by + j) * side + bx
                for (i in 0 until 8) {
                    val v = luma[row + i]
                    sum += v
                    sumSq += v * v
                }
            }
            sumSq - sum * sum / 64 > FLAT_VARIANCE * 64
        }
    }

    /** Mean squared luma error of the detailed blocks after a JPEG round trip, for Q_MIN..Q_MAX. */
    private suspend fun errorCurve(luma: IntArray, mask: BooleanArray, side: Int): DoubleArray {
        val gray = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val source = IntArray(luma.size) { 0xFF000000.toInt() or (luma[it] * 0x010101) }
        gray.setPixels(source, 0, side, 0, 0, side, side)
        val blocks = side / 8
        val used = mask.count { it }
        val out = ByteArrayOutputStream(side * side / 2)
        val px = IntArray(luma.size)
        val curve = DoubleArray(Q_MAX - Q_MIN + 1)
        var reuse: Bitmap? = null
        try {
            for (q in Q_MIN..Q_MAX) {
                currentCoroutineContext().ensureActive()
                out.reset()
                gray.compress(Bitmap.CompressFormat.JPEG, q, out)
                val bytes = out.toByteArray()
                val decoded = decodeReusing(bytes, reuse) ?: return DoubleArray(curve.size)
                if (reuse != null && reuse !== decoded) reuse.recycle()
                reuse = decoded
                if (decoded.width != side || decoded.height != side) return DoubleArray(curve.size)
                decoded.getPixels(px, 0, side, 0, 0, side, side)
                var err = 0L
                for (b in mask.indices) {
                    if (!mask[b]) continue
                    val bx = (b % blocks) * 8
                    val by = (b / blocks) * 8
                    for (j in 0 until 8) {
                        val row = (by + j) * side + bx
                        for (i in 0 until 8) {
                            val d = ((px[row + i] shr 8) and 255) - luma[row + i]
                            err += d * d
                        }
                    }
                }
                curve[q - Q_MIN] = err.toDouble() / (used * 64)
            }
        } finally {
            reuse?.recycle()
            gray.recycle()
        }
        return curve
    }

    /**
     * Decodes into [reuse] when possible. The bitmap is always mutable, so the very next call can reuse it (an
     * immutable first bitmap cannot be an `inBitmap`, which made the first two rounds allocate a bitmap each).
     */
    private fun decodeReusing(bytes: ByteArray, reuse: Bitmap?): Bitmap? {
        val o = BitmapFactory.Options().apply {
            inMutable = true
            inBitmap = reuse
        }
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        } catch (_: IllegalArgumentException) {
            // The reuse bitmap did not fit: a fresh (mutable) one instead
            o.inBitmap = null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        }
    }

    /** The recompression quality whose error sits clearly below its four neighbours. */
    private fun detect(curve: DoubleArray): Result {
        var bestScore = 0.0
        var bestQ = 0
        for (i in 2 until curve.size - 2) {
            val around = (curve[i - 2] + curve[i - 1] + curve[i + 1] + curve[i + 2]) / 4
            if (around < MIN_LEVEL) continue
            val score = (around - curve[i]) / around
            if (score > bestScore) {
                bestScore = score
                bestQ = Q_MIN + i
            }
        }
        return if (bestScore >= MIN_SCORE) {
            Result(Status.FOUND, bestQ, bestScore.toFloat().coerceAtMost(1f))
        } else {
            Result(Status.NOT_FOUND)
        }
    }
}

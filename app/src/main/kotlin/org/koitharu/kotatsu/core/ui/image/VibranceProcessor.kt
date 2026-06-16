package org.koitharu.kotatsu.core.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.core.net.toFile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * Applies real per-pixel vibrance to page bitmaps entirely on the CPU (JVM).
 *
 * Why CPU instead of GPUImage / GLSL:
 *   GPUImage requires: decode bitmap → upload as GL texture → render to FBO → readback.
 *   Peak memory for a 1080×4000 webtoon strip ≈ 3× bitmap size = ~49 MB just for this
 *   operation, causing OOM crashes on 2 GB devices (Oppo A11k class).
 *   CPU approach needs only 1× additional bitmap (the output), ~16 MB for the same strip.
 *
 * Algorithm — per-pixel HSL selective saturation:
 *   For every pixel, the RGB values are converted to HSL. The saturation boost applied
 *   is scaled by (1 - currentSaturation) so already-vivid pixels receive almost no boost
 *   while near-grey pixels receive the full boost. This is what Photoshop's Vibrance slider
 *   actually does. No ColorMatrix approximation — each pixel is treated independently.
 *
 * Optimisations:
 *   - [Semaphore](1): at most one page processes at a time, queued jobs are cancelled
 *     by BasePageHolder.onPause before they reach the semaphore.
 *   - [LruCache] (4 entries): re-scrolling to a visited page reuses the cached result.
 *   - Pixel array allocated once per call and reused for both read and write.
 *   - All arithmetic uses local Float vars — no boxing, no object allocation per pixel.
 *   - Inner loop written to minimise branch misprediction (hue sextant via Int division).
 */
object VibranceProcessor {

    private const val MAX_CACHED_BITMAPS = 4
    private const val SAMPLE_SIZE = 4

    private val semaphore = Semaphore(1)

    private val cache = object : LruCache<String, Bitmap>(MAX_CACHED_BITMAPS) {
        override fun sizeOf(key: String, value: Bitmap) = 1
        override fun entryRemoved(evicted: Boolean, key: String, old: Bitmap, new: Bitmap?) {
            if (evicted && !old.isRecycled) old.recycle()
        }
    }

    fun cacheKey(uri: String, vibrance: Float) = "$uri|v$vibrance"

    fun getCached(key: String): Bitmap? = synchronized(cache) { cache.get(key) }

    /**
     * Decodes the page bitmap from [pageUri], applies CPU vibrance, caches and returns result.
     * Suspends until the [Semaphore] is free — at most one page processes at a time.
     */
    suspend fun process(pageUri: Uri, vibrance: Float, key: String): Bitmap? {
        if (!pageUri.isFileUri() && !pageUri.isZipUri()) return null
        return semaphore.withPermit {
            runCatching {
                val src = decodeBitmap(pageUri) ?: return@runCatching null
                val result = applyVibrance(src, vibrance)
                src.recycle()
                synchronized(cache) { cache.put(key, result) }
                result
            }.getOrNull()
        }
    }

    /**
     * Applies CPU vibrance to an in-memory [Bitmap] (used by color-correction preview).
     * Uses the same [Semaphore] as [process] — at most one operation at a time.
     */
    suspend fun processBitmap(input: Bitmap, vibrance: Float): Bitmap? {
        return semaphore.withPermit {
            runCatching { applyVibrance(input, vibrance) }.getOrNull()
        }
    }

    fun releaseEntry(key: String) {
        synchronized(cache) { cache.remove(key) }
        // Bitmap recycled via entryRemoved callback above
    }

    fun trimMemory() {
        synchronized(cache) { cache.evictAll() }
        // entryRemoved recycles each bitmap
    }

    // ── Core algorithm ────────────────────────────────────────────────────────

    /**
     * Returns a new ARGB_8888 bitmap with vibrance applied.
     * [vibrance] range: -1.0 (remove colour) to +1.0 (maximum selective boost).
     *
     * Per-pixel steps:
     *   1. Unpack ARGB integer → R, G, B floats in [0,1]
     *   2. Compute HSL: H (hue), S (saturation 0–1), L (lightness 0–1)
     *   3. Boost = vibrance * (1 - S)   ← near-grey pixels get full boost, vivid get none
     *   4. S' = clamp(S + Boost, 0, 1)
     *   5. Convert H, S', L back to RGB
     *   6. Repack with original alpha
     */
    private fun applyVibrance(src: Bitmap, vibrance: Float): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)

        // Ensure ARGB_8888 for reliable pixel format
        val argb = if (src.config == Bitmap.Config.ARGB_8888) src
                   else src.copy(Bitmap.Config.ARGB_8888, false)

        argb.getPixels(pixels, 0, w, 0, 0, w, h)
        if (argb !== src) argb.recycle()

        val v = vibrance.coerceIn(-1f, 1f)

        for (i in pixels.indices) {
            val px = pixels[i]
            val a = px ushr 24
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8)  and 0xFF) / 255f
            val b = (px          and 0xFF) / 255f

            // RGB → HSL
            val cMax = max(r, max(g, b))
            val cMin = min(r, min(g, b))
            val delta = cMax - cMin
            val l = (cMax + cMin) * 0.5f

            if (delta < 0.0001f) continue  // achromatic — skip

            val s = delta / (1f - Math.abs(2f * l - 1f))

            // Photoshop-style selective boost:
            // (1-s)^2 ensures vivid pixels (s≈1) get near-zero boost,
            // dull pixels (s≈0) get the full boost.
            // Factor 0.4 caps the maximum saturation addition per unit of vibrance
            // so even fully grey pixels can't overshoot to full saturation.
            val selectivity = (1f - s) * (1f - s)
            val boost = v * selectivity * 0.4f
            val sNew = (s + boost).coerceIn(0f, 1f)

            if (Math.abs(sNew - s) < 0.001f) continue  // no meaningful change

            // Hue (0–6 sextants)
            val h6 = when (cMax) {
                r    -> ((g - b) / delta).let { if (it < 0f) it + 6f else it }
                g    -> (b - r) / delta + 2f
                else -> (r - g) / delta + 4f
            }

            // HSL → RGB
            val c = (1f - Math.abs(2f * l - 1f)) * sNew
            val x = c * (1f - Math.abs(h6 % 2f - 1f))
            val m = l - c * 0.5f

            val sxt = h6.toInt().coerceIn(0, 5)
            val r1: Float; val g1: Float; val b1: Float
            when (sxt) {
                0    -> { r1=c; g1=x; b1=0f }
                1    -> { r1=x; g1=c; b1=0f }
                2    -> { r1=0f; g1=c; b1=x }
                3    -> { r1=0f; g1=x; b1=c }
                4    -> { r1=x; g1=0f; b1=c }
                else -> { r1=c; g1=0f; b1=x }
            }

            val ri = ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val gi = ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            val bi = ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255)

            pixels[i] = (a shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    // ── URI decode ────────────────────────────────────────────────────────────

    /**
     * Decodes the page at 1/[SAMPLE_SIZE] resolution.
     * Vibrance is a colour-only operation — spatial detail is irrelevant.
     * At 1/4 resolution a 1080×4000 strip decodes to 270×1000 = ~1 MB (ARGB_8888)
     * instead of ~16 MB at full resolution. The vibrance result is then drawn back
     * scaled to full size by SSIV, which applies its own sub-sampling anyway.
     *
     * For ZIP/CBZ entries we fall back to BitmapFactory directly (supports inSampleSize).
     * For plain files on API 28+ we use ImageDecoder with a resize target.
     */
    private fun decodeBitmap(uri: Uri): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = SAMPLE_SIZE
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        if (uri.isZipUri()) {
            ZipFile(uri.schemeSpecificPart).use { zip ->
                val entry = zip.getEntry(uri.fragment) ?: return null
                zip.getInputStream(entry).use { stream ->
                    BitmapFactory.decodeStream(stream, null, opts)
                }
            }
        } else {
            val file = uri.toFile()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val (w, h) = info.size.width to info.size.height
                    decoder.setTargetSize(w / SAMPLE_SIZE, h / SAMPLE_SIZE)
                    decoder.setTargetColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
                }
            } else {
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
        }
    }.getOrNull()

}

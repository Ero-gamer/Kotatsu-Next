package org.koitharu.kotatsu.core.ui.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.net.toFile
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

/**
 * Computes a per-page vibrance saturation BOOST (Float) from a downsampled page decode.
 *
 * Architecture:
 *   SSIV's full-resolution tiled image source is NEVER replaced. Replacing it with a
 *   downsampled bitmap causes permanent blurriness. Instead:
 *   1. Decode the page at 1/[SAMPLE_SIZE] resolution (colour analysis only, ~1 MB).
 *   2. Compute the page's average saturation distribution via HSL conversion.
 *   3. Derive a ColorMatrix that approximates the selective saturation boost for this
 *      specific page's colour profile.
 *   4. Return the boost value so the caller can composite it into the full
 *      ColorMatrix pipeline (ReaderColorFilter.toColorFilter) alongside all other
 *      active filters, instead of replacing ssiv.colorFilter outright.
 *
 * Why not a global static matrix:
 *   The boost factor in the ColorMatrix is tuned to the page's own average saturation.
 *   A page of mostly grey tones gets a stronger matrix; a page of vivid colours gets
 *   a gentler one. This per-page calibration is what makes it behave like real vibrance
 *   (selective) rather than uniform saturation, within the limits of a static matrix.
 *
 * Resource profile:
 *   - Semaphore(1): one page analysed at a time, cancelled on scroll-off.
 *   - In-memory cache(8): re-scrolling reuses the cached boost value — no re-decode.
 *   - Decode at 1/4 res: ~1 MB peak per page, recycled immediately after analysis.
 *   - No bitmap stored in cache — only a single Float per page.
 */
object VibranceProcessor {

    private const val MAX_CACHED_FILTERS = 8
    private const val SAMPLE_SIZE = 4

    private val semaphore = Semaphore(1)

    /** Key → ColorMatrixColorFilter. Lightweight — no bitmap stored. */
    private val cache = HashMap<String, Float>(MAX_CACHED_FILTERS)
    private val cacheLock = Any()

    fun cacheKey(uri: String, vibrance: Float) = "$uri|v$vibrance"

    fun getCached(key: String): Float? = synchronized(cacheLock) { cache[key] }

    /**
     * Decodes the page at low resolution, analyses its saturation distribution,
     * and returns a [ColorMatrixColorFilter] that approximates HSL vibrance for
     * this specific page. The filter is safe to set directly on ssiv.colorFilter.
     *
     * Returns null if the URI is unsupported, decode fails, or the job is cancelled.
     */
    suspend fun computeBoost(
        pageUri: Uri,
        vibrance: Float,
        key: String,
    ): Float? {
        if (!pageUri.isFileUri() && !pageUri.isZipUri()) return null

        getCached(key)?.let { return it }

        return semaphore.withPermit {
            getCached(key)?.let { return@withPermit it }

            runCatching {
                val bmp = decodeSampled(pageUri) ?: return@runCatching null
                val avgSat = computeAverageSaturation(bmp)
                bmp.recycle()

                val boost = computeBoostValue(vibrance, avgSat)
                synchronized(cacheLock) {
                    if (cache.size >= MAX_CACHED_FILTERS) {
                        cache.keys.firstOrNull()?.let { cache.remove(it) }
                    }
                    cache[key] = boost
                }
                boost
            }.getOrNull()
        }
    }

    /**
     * Computes a vibrance ColorFilter for the preview screen where we already
     * have a bitmap in memory. Analyses the bitmap directly — no disk decode.
     */
    suspend fun computeBoostForBitmap(
        bitmap: Bitmap,
        vibrance: Float,
    ): Float? = semaphore.withPermit {
        runCatching {
            val avgSat = computeAverageSaturation(bitmap)
            computeBoostValue(vibrance, avgSat)
        }.getOrNull()
    }

    fun releaseEntry(key: String) = synchronized(cacheLock) { cache.remove(key) }

    fun trimMemory() = synchronized(cacheLock) { cache.clear() }

    // ── Core ──────────────────────────────────────────────────────────────────

    /**
     * Samples pixels from [bmp] and returns their mean HSL saturation in [0,1].
     * Uses stride sampling (every 4th pixel) to keep cost low on the IO thread.
     */
    private fun computeAverageSaturation(bmp: Bitmap): Float {
        val w = bmp.width; val h = bmp.height
        val stride = 4  // sample every 4th pixel

        // NOTE: pixels are read and accumulated inline (no IntArray buffer).
        // A previous version pre-sized a buffer with (w/stride)*(h/stride) (floor),
        // but the sampling loops below actually run ceil(w/stride)*ceil(h/stride)
        // times whenever w or h isn't an exact multiple of `stride` — true for
        // almost every real page — which overran the buffer, threw, and was
        // silently swallowed by the caller's runCatching, always yielding a 0f
        // boost. Accumulating directly avoids the sizing mismatch entirely and
        // also skips an extra allocation.
        var satSum = 0f
        var count = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val px = bmp.getPixel(x, y)
                val r = ((px shr 16) and 0xFF) / 255f
                val g = ((px shr 8)  and 0xFF) / 255f
                val b = (px          and 0xFF) / 255f
                val cMax = max(r, max(g, b))
                val cMin = min(r, min(g, b))
                val delta = cMax - cMin
                if (delta >= 0.001f) {
                    val l = (cMax + cMin) * 0.5f
                    val denom = 1f - Math.abs(2f * l - 1f)
                    if (denom >= 0.001f) {
                        satSum += delta / denom
                        count++
                    }
                }
                x += stride
            }
            y += stride
        }
        return if (count == 0) 0f else (satSum / count).coerceIn(0f, 1f)
    }

    /**
     * Builds a [ColorMatrix] that approximates HSL vibrance for a page whose
     * average saturation is [avgSat].
     *
     * The boost applied is proportional to (1 - avgSat)^2 — pages with mostly
     * dull colours get a strong boost; pages already vivid get a gentle one.
     * This is the per-page calibration that distinguishes vibrance from saturation.
     *
     * The matrix itself uses standard Rec.709 luma weights so the boost is
     * perceptually uniform across hues (same formula as setSaturation internally,
     * but with a vibrance-scaled coefficient instead of a fixed one).
     */
    /**
     * Returns the additional saturation boost (NOT a final scale) for this page.
     * Caller composites this into the full ColorMatrix pipeline via
     * ReaderColorFilter.toColorFilter(vibranceBoost) so it combines correctly
     * with brightness/contrast/saturation/grayscale/invert instead of replacing them.
     */
    private fun computeBoostValue(vibrance: Float, avgSat: Float): Float {
        val v = vibrance.coerceIn(-1f, 1f)
        val selectivity = (1f - avgSat) * (1f - avgSat)
        return v * selectivity * 0.6f
    }

    // ── Decode ────────────────────────────────────────────────────────────────

    private fun decodeSampled(uri: Uri): Bitmap? = runCatching {
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
                    decoder.setTargetSize(
                        info.size.width / SAMPLE_SIZE,
                        info.size.height / SAMPLE_SIZE,
                    )
                }
            } else {
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
        }
    }.getOrNull()
}

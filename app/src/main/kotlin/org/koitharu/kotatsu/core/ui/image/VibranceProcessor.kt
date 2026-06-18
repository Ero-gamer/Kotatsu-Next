package org.koitharu.kotatsu.core.ui.image

/**
 * Pure per-pixel selective-vibrance math — no bitmap/array handling here, see
 * [ImageFiltersTransformation] for the single combined getPixels/loop/setPixels pass that
 * calls [vibranceFactor] for every pixel alongside [SharpnessProcessor]'s sharpen math.
 *
 * Why this can't be a ColorMatrix:
 *   A [android.graphics.ColorMatrix] is one fixed linear transform applied identically to
 *   every pixel. It cannot boost one pixel more than another based on that pixel's own
 *   existing saturation — any attempt to do so by deriving one scalar from the page's
 *   *average* saturation just produces a smaller/larger uniform Saturation, not real vibrance.
 *   Real vibrance requires reading each pixel's own value, which only a per-pixel loop can do.
 *
 * [vibranceFactor] returns a primitive Float (no array/object allocation) so the caller's
 * per-pixel loop stays allocation-free — important when iterating multi-megapixel webtoon
 * strips on a low-RAM device.
 */
object VibranceProcessor {

    /** How strongly a fully-dull pixel (S=0) is pushed when the slider is maxed (v=1). */
    private const val VIBRANCE_SCALAR = 1.5f

    /**
     * Returns the multiplicative factor to apply to (channel - mean) for a pixel with the
     * given [r]/[g]/[b] (0..255) and [vibrance] (-1..1). 1f means "no change". Caller computes:
     *   newChannel = mean + (channel - mean) * factor
     * for each of r, g, b, where mean = (r+g+b)/3f.
     */
    fun vibranceFactor(r: Int, g: Int, b: Int, vibrance: Float): Float {
        if (vibrance == 0f) return 1f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC
        if (delta == 0) return 1f // pure grey — nothing to selectively boost

        // HSL saturation computed directly from integer max/min (0..255), avoiding the
        // float-lightness formula's division-by-zero risk at L=0 or L=1.
        val lSum = maxC + minC // 0..510
        val denom = (if (lSum <= 255) lSum else 510 - lSum).coerceAtLeast(1)
        val sat = delta.toFloat() / denom

        // Selectivity: (1-S)^2 — dull/muted pixels (low S) receive most of the boost,
        // already-vivid pixels (high S) receive almost none. S is THIS pixel's own
        // saturation, not a page average, so two pixels in the same image with different
        // existing saturation get genuinely different boosts.
        val selectivity = (1f - sat) * (1f - sat)
        return 1f + vibrance.coerceIn(-1f, 1f) * selectivity * VIBRANCE_SCALAR
    }

    /**
     * No-op. Kept so [org.koitharu.kotatsu.reader.ui.pager.BasePageHolder]'s existing
     * onTrimMemory call site doesn't need to change: there is no in-memory cache to trim —
     * filtered pages are cached to disk by PageLoader's own LRU file cache.
     */
    fun trimMemory() = Unit
}

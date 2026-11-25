package org.koitharu.kotatsu.reader.domain

import android.graphics.ColorMatrix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a page's measured [PageStats] into ONE color matrix that applies the enabled auto filters together
 * (contrast, then brightness gain, then saturation). Everything is affine per channel, so the result is a
 * single ColorMatrix: one paint filter, no extra redraw, no tile reload.
 *
 * Values are signed: contrast stretches flat pages and gently compresses harsh color pages; brightness
 * lightens dark pages and darkens too-bright ones towards a per-type target; saturation only ever boosts.
 * Pages already near their target are left untouched (dead bands), so a chapter does not flicker.
 */
object AutoColor {

    const val MAX_GAIN = 2.2f

    private const val FULL_RANGE_SPAN = 230
    private const val MIN_SPAN = 40
    private const val MAX_LOW = 96
    private const val HARSH_SPREAD = 215
    private const val MIN_COMPRESS = 0.85f
    private const val COMPRESS_PIVOT = 128f
    private const val STRENGTH = 0.8f

    private const val PAPER_MEDIAN = 200
    private const val MIN_COLORED_PERMILLE = 30
    private const val TARGET_PAPER = 248f
    private const val TARGET_COLOR = 128f
    private const val TARGET_GREY = 140f
    private const val BAND_PAPER = 0.06f
    private const val BAND_OTHER = 0.10f
    private const val MIN_GAIN = 0.8f
    private const val MAX_BRIGHT_GAIN = 1.35f

    private const val TARGET_CHROMA = 0.38f
    private const val MAX_SAT = 1.5f
    private const val MIN_SAT_STEP = 1.05f

    private enum class PageClass { PAPER, COLOR, GREY }

    private fun classify(s: PageStats): PageClass = when {
        s.median >= PAPER_MEDIAN -> PageClass.PAPER
        s.coloredPermille >= MIN_COLORED_PERMILLE -> PageClass.COLOR
        else -> PageClass.GREY
    }

    /** The combined matrix, or null when nothing needs changing for this page. */
    fun matrix(
        stats: PageStats,
        contrast: Boolean,
        brightness: Boolean,
        saturation: Boolean,
    ): ColorMatrix? {
        val cls = classify(stats)
        var scale = 1f
        var offset = 0f
        if (contrast) {
            val span = stats.hi - stats.lo
            if (span in MIN_SPAN until FULL_RANGE_SPAN) {
                val low = min(stats.lo, MAX_LOW)
                scale = min(255f / max(stats.hi - low, 1), MAX_GAIN)
                offset = -low * scale
            } else if (cls == PageClass.COLOR) {
                val spread = stats.p95 - stats.p5
                if (spread > HARSH_SPREAD) {
                    val raw = (HARSH_SPREAD.toFloat() / spread).coerceIn(MIN_COMPRESS, 1f)
                    scale = 1f - (1f - raw) * STRENGTH
                    offset = COMPRESS_PIVOT * (1f - scale)
                }
            }
        }
        var gain = 1f
        if (brightness) {
            val median = (scale * stats.median + offset).coerceIn(1f, 255f)
            val target = when (cls) {
                PageClass.PAPER -> TARGET_PAPER
                PageClass.COLOR -> TARGET_COLOR
                PageClass.GREY -> TARGET_GREY
            }
            val band = if (cls == PageClass.PAPER) BAND_PAPER else BAND_OTHER
            val raw = target / median
            if (abs(raw - 1f) > band) {
                var g = raw.coerceIn(MIN_GAIN, MAX_BRIGHT_GAIN)
                if (g > 1f) {
                    // Lightening must not blow out the page's bright end.
                    val ref = if (cls == PageClass.COLOR) stats.p95 else stats.hi
                    val refOut = (scale * ref + offset).coerceAtLeast(1f)
                    g = min(g, max(1f, 255f / refOut))
                }
                gain = g
            }
        }
        var sat = 1f
        if (saturation && stats.coloredPermille >= MIN_COLORED_PERMILLE && stats.chromaMean > 0) {
            val lift = scale * gain
            val chroma = stats.chromaMean / 1000f * lift
            val peak = stats.chromaP99 / 1000f * lift
            if (chroma > 0.01f) {
                var g = (TARGET_CHROMA / chroma).coerceIn(1f, MAX_SAT)
                if (peak > 0f) g = min(g, 0.9f / peak)
                g = 1f + (max(g, 1f) - 1f) * STRENGTH
                if (g >= MIN_SAT_STEP) sat = g
            }
        }
        if (scale == 1f && offset == 0f && gain == 1f && sat == 1f) return null
        val s = scale * gain
        val t = offset * gain
        val cm = ColorMatrix(
            floatArrayOf(
                s, 0f, 0f, 0f, t,
                0f, s, 0f, 0f, t,
                0f, 0f, s, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        if (sat != 1f) cm.postConcat(ColorMatrix().also { it.setSaturation(sat) })
        return cm
    }
}

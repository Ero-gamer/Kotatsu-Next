package org.koitharu.kotatsu.reader.domain

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.view.View

/**
 * Immutable snapshot of all reader color-correction parameters.
 *
 * ### CPU / Canvas (ColorMatrix) — zero per-pixel cost:
 * - [brightness] (positive values), [contrast], [saturation] — applied as a `ColorMatrix` paint filter on SSIV.
 * - [isAutoContrast], [isAutoBrightness], [isAutoSaturation] — per-page values measured once ([PageStatsEngine]) and
 *   folded with [AutoColor] into the same single matrix; each replaces its manual slider while on.
 * - [overlayAlpha] with [overlayRed]/[overlayGreen]/[overlayBlue], and negative [brightness] — NOT part of the matrix:
 *   [overlayColor] is one translucent ARGB layer drawn over the finished page (`SubsamplingScaleImageView.overlayColor`,
 *   [applyOverlay] for other views). A matrix clamps once at its end, so a tint folded into it vanished wherever contrast
 *   or brightness had already pushed a channel past white.
 *
 * ### GPU (GLSL shader via [GpuFilteringDecoder]) — single-pass on decode.
 * Every filter is an independent value (0 = off), none is mutually exclusive with another:
 * - [vibrance]            → `u_enableVibrance` + `u_vibranceIntensity`
 * - [denoise]             → `u_enableDenoise` + `u_denoiseStrength` (3x3 luma-weighted denoise)
 * - [isLineDarkenEnabled] → `u_enableDarken`
 * - [rcas]                → real AMD FidelityFX FSR1 RCAS (single shader pass)
 * - [adaptiveSharpen]     → real bacondither Adaptive-Sharpen (two shader passes: edge, then sharpen)
 *
 * [rcas] and [adaptiveSharpen] are independent, like every other filter here — neither excludes
 * the other; enabling both runs RCAS first and Adaptive-Sharpen sharpens its output.
 *
 * Bicubic *scalers* (Catmull-Rom, Robidoux) are not filters: they are a separate reader setting
 * ([org.koitharu.kotatsu.core.prefs.AppSettings.readerScaler]) applied at draw time.
 *
 * ### Deprecated / no-op:
 * - [dither] — CPU Bayer-dither loop removed; field kept for DB/serialisation compat only.
 * - [grain]  — CPU random-grain loop removed; field kept for DB/serialisation compat only.
 *   Both default to 0f and are never applied.
 */
data class ReaderColorFilter(
    val brightness: Float,
    val contrast: Float,
    val saturation: Float,
    val vibrance: Float,
    val denoise: Float = 0f,
    /** Sharpen intensity, real AMD FidelityFX RCAS. 0..1 maps linearly onto `u_rcasCon`. */
    val rcas: Float = 0f,
    /** Sharpen intensity, real bacondither Adaptive-Sharpen. 0..1 maps onto `curve_height` in
     *  the algorithm's own sane range [0.3, 2.0]. */
    val adaptiveSharpen: Float = 0f,
    /** Deband intensity — f3kdb/flash3kyuu_deband's own documented "square" mode, independently
     *  implemented from its published docs (see `deband.frag`'s doc comment; not GPL-encumbered).
     *  0..1 maps onto range/threshold/grain together — see `GpuTileRenderer`'s deband stage. */
    val deband: Float = 0f,
    /** Local-threshold black & white mix, 0 (off) .. 1 (pure B/W). Last GPU stage, see `binarize.frag`. */
    val binarize: Float = 0f,
    @Deprecated("CPU grain filter removed. Field retained for DB compatibility only.")
    val dither: Float = 0f,
    @Deprecated("CPU grain filter removed. Field retained for DB compatibility only.")
    val grain: Float = 0f,
    val isInverted: Boolean,
    val isGrayscale: Boolean,
    val isBookBackground: Boolean,
    /** Line darkening (`u_enableDarken`) — Anime4K-inspired heuristic, not the Anime4K algorithm. */
    val isLineDarkenEnabled: Boolean = false,
    /** Stretches each page's own brightness range to full contrast; the manual [contrast] is ignored while on. */
    val isAutoContrast: Boolean = false,
    /** Lightens dark pages and darkens too-bright ones towards a per-type target; the manual [brightness] is ignored while on. */
    val isAutoBrightness: Boolean = false,
    /** Boosts dull pages' saturation (never lowers it); the manual [saturation] is ignored while on. */
    val isAutoSaturation: Boolean = false,
    /** Color overlay: a flat color ([overlayRed], [overlayGreen], [overlayBlue], each 0..1) mixed over the page at [overlayAlpha] opacity; off at 0. */
    val overlayRed: Float = 0f,
    val overlayGreen: Float = 0f,
    val overlayBlue: Float = 0f,
    val overlayAlpha: Float = 0f,
) {
    /** True when any per-page auto filter is on (the page's stats are then needed). */
    val hasAuto: Boolean
        get() = isAutoContrast || isAutoBrightness || isAutoSaturation

    val isEmpty: Boolean
        get() =
            !isGrayscale && !isInverted && !isBookBackground && !isLineDarkenEnabled && !hasAuto &&
                overlayAlpha == 0f && overlayRed == 0f && overlayGreen == 0f && overlayBlue == 0f &&
                brightness == 0f && contrast == 0f && saturation == 0f && vibrance == 0f && denoise == 0f &&
                rcas == 0f && adaptiveSharpen == 0f && deband == 0f && binarize == 0f
    // dither and grain intentionally excluded — they're always ignored.

    /**
     * CPU/Canvas ColorMatrix covering positive Brightness, Contrast, Saturation, Invert, Grayscale and
     * Book-background tint. The color overlay / negative brightness ([overlayColor]) and every GPU filter are excluded.
     */
    fun toColorFilter(): ColorMatrixColorFilter = cpuFilter

    /** Built once per immutable snapshot: every page holder shares it instead of allocating its own. */
    private val cpuFilter: ColorMatrixColorFilter by lazy(LazyThreadSafetyMode.PUBLICATION) { buildColorFilter() }

    /**
     * [toColorFilter] with the page's own [stats] folded in first (while an auto filter is on). Not cached:
     * it differs per page, and building one small matrix per page is cheap.
     */
    fun toColorFilter(stats: PageStats?): ColorMatrixColorFilter = if (hasAuto && stats != null) {
        buildColorFilter(stats)
    } else {
        cpuFilter
    }

    private fun buildColorFilter(stats: PageStats? = null): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        if (stats != null) {
            AutoColor.matrix(stats, isAutoContrast, isAutoBrightness, isAutoSaturation && !isGrayscale)
                ?.let { cm.postConcat(it) }
        }
        if (isGrayscale) cm.postConcat(ColorMatrix().also { it.setSaturation(0f) })
        if (isInverted) cm.postConcat(INVERT_MATRIX)
        if (brightness > 0f && !isAutoBrightness) cm.postConcat(brightnessMatrix(brightness))
        if (contrast != 0f && !isAutoContrast) cm.postConcat(contrastMatrix(contrast))
        if (saturation != 0f && !isAutoSaturation && !isGrayscale) cm.postConcat(saturationMatrix(saturation))
        if (isBookBackground) cm.postConcat(BOOK_MATRIX)
        return ColorMatrixColorFilter(cm)
    }

    /** True when any filter that [toColorFilter] actually encodes is on (the GPU-only ones do not count). */
    val hasCpuMatrix: Boolean
        get() = isGrayscale || isInverted || isBookBackground || (brightness > 0f && !isAutoBrightness) ||
            (contrast != 0f && !isAutoContrast) || (saturation != 0f && !isAutoSaturation)

    /** [toColorFilter], or null when it would be an identity matrix (animations then skip the per-frame filter). */
    fun toCpuColorFilterOrNull(): ColorMatrixColorFilter? = if (hasCpuMatrix) toColorFilter() else null

    /**
     * The layer drawn over the finished page: black at `-brightness` for negative brightness (same result as scaling
     * by `1 + brightness`, but applied after everything else), then the colour overlay on top of it. Both are folded
     * into one straight-alpha ARGB colour; `0` when neither is on.
     */
    val overlayColor: Int by lazy(LazyThreadSafetyMode.PUBLICATION) { computeOverlayColor() }

    private fun computeOverlayColor(): Int = OverlayColor.argb(
        darken = if (brightness < 0f && !isAutoBrightness) -brightness else 0f,
        red = overlayRed,
        green = overlayGreen,
        blue = overlayBlue,
        alpha = overlayAlpha,
    )

    fun getBackgroundTint(): ColorStateList? = if (isBookBackground) {
        ColorStateList.valueOf(Color.rgb(255, 255, (255 * BOOK_BLUE_FACTOR).toInt()))
    } else {
        null
    }

    companion object {
        private const val BOOK_BLUE_FACTOR = 0.92f

        @Suppress("DEPRECATION")
        val EMPTY =
            ReaderColorFilter(
                brightness = 0f,
                contrast = 0f,
                saturation = 0f,
                vibrance = 0f,
                denoise = 0f,
                rcas = 0f,
                adaptiveSharpen = 0f,
                deband = 0f,
                binarize = 0f,
                dither = 0f,
                grain = 0f,
                isInverted = false,
                isGrayscale = false,
                isBookBackground = false,
                isLineDarkenEnabled = false,
                isAutoContrast = false,
                isAutoBrightness = false,
                isAutoSaturation = false,
                overlayRed = 0f,
                overlayGreen = 0f,
                overlayBlue = 0f,
                overlayAlpha = 0f,
            )

        private val INVERT_MATRIX =
            ColorMatrix(
                floatArrayOf(
                    -1f,
                    0f,
                    0f,
                    0f,
                    255f,
                    0f,
                    -1f,
                    0f,
                    0f,
                    255f,
                    0f,
                    0f,
                    -1f,
                    0f,
                    255f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                ),
            )

        private val BOOK_MATRIX =
            ColorMatrix(
                floatArrayOf(
                    1f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    BOOK_BLUE_FACTOR,
                    0f,
                    0f,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                ),
            )

        private fun brightnessMatrix(b: Float): ColorMatrix = ColorMatrix().also { it.setScale(b + 1f, b + 1f, b + 1f, 1f) }

        private fun contrastMatrix(c: Float): ColorMatrix {
            val s = c + 1f
            val t = (-0.5f * s + 0.5f) * 255f
            return ColorMatrix(
                floatArrayOf(
                    s,
                    0f,
                    0f,
                    0f,
                    t,
                    0f,
                    s,
                    0f,
                    0f,
                    t,
                    0f,
                    0f,
                    s,
                    0f,
                    t,
                    0f,
                    0f,
                    0f,
                    1f,
                    0f,
                ),
            )
        }

        private fun saturationMatrix(s: Float): ColorMatrix = ColorMatrix().also { it.setSaturation((s + 1f).coerceIn(0f, 4f)) }
    }
}

/**
 * Draws [color] (see [ReaderColorFilter.overlayColor]) above the content of a view that is not a
 * `SubsamplingScaleImageView`, as its foreground. It covers the whole view, so any letterbox is tinted as well.
 */
fun View.applyOverlay(color: Int) {
    val current = (foreground as? ColorDrawable)?.color
    if (Color.alpha(color) == 0) {
        if (foreground != null) foreground = null
    } else if (current != color) {
        foreground = ColorDrawable(color)
    }
}

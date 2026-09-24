package org.koitharu.kotatsu.reader.domain

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * Immutable snapshot of all reader colour-correction parameters.
 *
 * ### CPU / Canvas (ColorMatrix) — zero per-pixel cost:
 * - [brightness], [contrast], [saturation] — applied as a `ColorMatrix` paint filter on SSIV.
 *
 * ### GPU (GLSL shader via [GpuFilteringDecoder]) — single-pass on decode.
 * Every filter is an independent value (0 = off), none is mutually exclusive with another:
 * - [vibrance]            → `u_enableVibrance` + `u_vibranceIntensity`
 * - [denoise]             → `u_enableDenoise` + `u_denoiseStrength` (3x3 luma-weighted denoise)
 * - [isLineDarkenEnabled] → `u_enableDarken`
 * - [rcasUsm]             → `u_enableRcasUsm` + `u_rcasUsmIntensity` (RCAS-style clamp + unsharp mask)
 * - [adaptiveSmoothstep]  → `u_enableAdaptiveSmoothstep` + `u_adaptiveSmoothstepIntensity`
 * - [adaptiveSigmoid]     → `u_enableAdaptiveSigmoid` + `u_adaptiveSigmoidIntensity`
 *
 * Bicubic *scalers* (Catmull-Rom, B-Spline) are not filters: they are a separate reader setting
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
    /** Sharpen intensity, RCAS-style clamped unsharp mask (`u_rcasUsmIntensity`). */
    val rcasUsm: Float = 0f,
    /** Sharpen intensity, adaptive with a smoothstep edge weight (`u_adaptiveSmoothstepIntensity`). */
    val adaptiveSmoothstep: Float = 0f,
    /** Sharpen intensity, adaptive with a true logistic-sigmoid edge weight (`u_adaptiveSigmoidIntensity`). */
    val adaptiveSigmoid: Float = 0f,
    @Deprecated("CPU grain filter removed. Field retained for DB compatibility only.")
    val dither: Float = 0f,
    @Deprecated("CPU grain filter removed. Field retained for DB compatibility only.")
    val grain: Float = 0f,
    val isInverted: Boolean,
    val isGrayscale: Boolean,
    val isBookBackground: Boolean,
    /** Line darkening (`u_enableDarken`) — Anime4K-inspired heuristic, not the Anime4K algorithm. */
    val isLineDarkenEnabled: Boolean = false,
) {

    val isEmpty: Boolean
        get() = !isGrayscale && !isInverted && !isBookBackground && !isLineDarkenEnabled &&
            brightness == 0f && contrast == 0f && saturation == 0f && vibrance == 0f && denoise == 0f &&
            rcasUsm == 0f && adaptiveSmoothstep == 0f && adaptiveSigmoid == 0f
    // dither and grain intentionally excluded — they're always ignored.

    /**
     * CPU/Canvas ColorMatrix covering Brightness, Contrast, Saturation, Invert, Grayscale,
     * and Book-background tint. Every other filter is excluded — those are handled by the GPU shader.
     */
    fun toColorFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        if (isGrayscale) cm.setSaturation(0f)
        if (isInverted) cm.postConcat(INVERT_MATRIX)
        if (brightness != 0f) cm.postConcat(brightnessMatrix(brightness))
        if (contrast != 0f) cm.postConcat(contrastMatrix(contrast))
        if (saturation != 0f && !isGrayscale) cm.postConcat(saturationMatrix(saturation))
        if (isBookBackground) cm.postConcat(BOOK_MATRIX)
        return ColorMatrixColorFilter(cm)
    }

    fun getBackgroundTint(): ColorStateList? = if (isBookBackground) {
        ColorStateList.valueOf(Color.rgb(255, 255, (255 * BOOK_BLUE_FACTOR).toInt()))
    } else {
        null
    }

    companion object {

        private const val BOOK_BLUE_FACTOR = 0.92f

        @Suppress("DEPRECATION")
        val EMPTY = ReaderColorFilter(
            brightness = 0f, contrast = 0f,
            saturation = 0f, vibrance = 0f, denoise = 0f,
            rcasUsm = 0f, adaptiveSmoothstep = 0f, adaptiveSigmoid = 0f,
            dither = 0f, grain = 0f,
            isInverted = false, isGrayscale = false, isBookBackground = false,
            isLineDarkenEnabled = false,
        )

        private val INVERT_MATRIX = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

        private val BOOK_MATRIX = ColorMatrix(
            floatArrayOf(
                1f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, BOOK_BLUE_FACTOR, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        )

        private fun brightnessMatrix(b: Float): ColorMatrix = ColorMatrix().also { it.setScale(b + 1f, b + 1f, b + 1f, 1f) }

        private fun contrastMatrix(c: Float): ColorMatrix {
            val s = c + 1f
            val t = (-0.5f * s + 0.5f) * 255f
            return ColorMatrix(
                floatArrayOf(
                    s, 0f, 0f, 0f, t,
                    0f, s, 0f, 0f, t,
                    0f, 0f, s, 0f, t,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
        }

        private fun saturationMatrix(s: Float): ColorMatrix = ColorMatrix().also { it.setSaturation((s + 1f).coerceIn(0f, 4f)) }
    }
}

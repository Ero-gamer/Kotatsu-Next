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
 * ### GPU (GLSL shader via [GpuFilteringDecoder]) — single-pass on tile decode:
 * - [sharpening] → `u_sharpenMode` / `u_sharpness`  (RCAS+USM at ≤0.5, Adaptive above)
 * - [vibrance]   → `u_enableVibrance`
 * - [denoise]    → `u_enableDenoise`
 *
 * ### Deprecated / no-op:
 * - [dither] — CPU Bayer-dither loop removed; field kept for DB/serialisation compat only.
 * - [grain]  — CPU random-grain loop removed; field kept for DB/serialisation compat only.
 *   Both default to 0f and are never applied.
 */
data class ReaderColorFilter(
    val brightness: Float,
    val contrast: Float,
    val sharpening: Float,
    val saturation: Float,
    val vibrance: Float,
    val denoise: Float = 0f,
    @Deprecated("CPU grain filter removed. Field retained for DB compatibility only.")
    val dither: Float = 0f,
    @Deprecated("CPU grain filter removed. Field retained for DB compatibility only.")
    val grain: Float = 0f,
    val isInverted: Boolean,
    val isGrayscale: Boolean,
    val isBookBackground: Boolean,
) {

    val isEmpty: Boolean
        get() = !isGrayscale && !isInverted && !isBookBackground &&
            brightness == 0f && contrast == 0f && sharpening == 0f &&
            saturation == 0f && vibrance == 0f && denoise == 0f
    // dither and grain intentionally excluded — they're always ignored.

    /**
     * CPU/Canvas ColorMatrix covering Brightness, Contrast, Saturation, Invert, Grayscale,
     * and Book-background tint. Sharpening, Vibrance, and Denoise are excluded — they are
     * handled by the GPU shader.
     */
    fun toColorFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        if (isGrayscale) cm.setSaturation(0f)
        if (isInverted)  cm.postConcat(INVERT_MATRIX)
        if (brightness != 0f) cm.postConcat(brightnessMatrix(brightness))
        if (contrast   != 0f) cm.postConcat(contrastMatrix(contrast))
        if (saturation != 0f && !isGrayscale) cm.postConcat(saturationMatrix(saturation))
        if (isBookBackground) cm.postConcat(BOOK_MATRIX)
        return ColorMatrixColorFilter(cm)
    }

    fun getBackgroundTint(): ColorStateList? = if (isBookBackground) {
        ColorStateList.valueOf(Color.rgb(255, 255, (255 * BOOK_BLUE_FACTOR).toInt()))
    } else null

    companion object {

        private const val BOOK_BLUE_FACTOR = 0.92f

        @Suppress("DEPRECATION")
        val EMPTY = ReaderColorFilter(
            brightness = 0f, contrast = 0f, sharpening = 0f,
            saturation = 0f, vibrance = 0f, denoise = 0f,
            dither = 0f, grain = 0f,
            isInverted = false, isGrayscale = false, isBookBackground = false,
        )

        private val INVERT_MATRIX = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
             0f,-1f, 0f, 0f, 255f,
             0f, 0f,-1f, 0f, 255f,
             0f, 0f, 0f, 1f,   0f,
        ))

        private val BOOK_MATRIX = ColorMatrix(floatArrayOf(
            1f, 0f,                0f, 0f, 0f,
            0f, 1f,                0f, 0f, 0f,
            0f, 0f, BOOK_BLUE_FACTOR, 0f, 0f,
            0f, 0f,                0f, 1f, 0f,
        ))

        private fun brightnessMatrix(b: Float): ColorMatrix =
            ColorMatrix().also { it.setScale(b + 1f, b + 1f, b + 1f, 1f) }

        private fun contrastMatrix(c: Float): ColorMatrix {
            val s = c + 1f
            val t = (-0.5f * s + 0.5f) * 255f
            return ColorMatrix(floatArrayOf(
                s, 0f, 0f, 0f, t,
                0f,  s, 0f, 0f, t,
                0f, 0f,  s, 0f, t,
                0f, 0f, 0f, 1f, 0f,
            ))
        }

        private fun saturationMatrix(s: Float): ColorMatrix =
            ColorMatrix().also { it.setSaturation((s + 1f).coerceIn(0f, 4f)) }
    }
}

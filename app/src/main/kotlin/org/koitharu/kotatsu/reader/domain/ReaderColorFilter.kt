package org.koitharu.kotatsu.reader.domain

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * All image-adjustment parameters for a manga.
 *
 * Real-time ColorMatrix paint filters (applied instantly to the SSIV view via [toColorFilter]):
 *   [brightness], [contrast], [saturation], [isInverted], [isGrayscale], [isBookBackground]
 *
 * Bitmap pre-processing filters (baked into page file at load, cached to disk):
 *   [sharpening] — via GPUImageSharpenFilter in ImageFiltersTransformation / PageLoader
 *
 * Per-visible-page GPU filters (applied on-screen, released when page scrolls off):
 *   [vibrance] — via VibranceProcessor (GPUImageVibranceFilter) in BasePageHolder
 */
data class ReaderColorFilter(
    val brightness: Float,
    val contrast: Float,
    val sharpening: Float,
    /** Uniform saturation scale via ColorMatrix — applied in real-time on SSIV paint. */
    val saturation: Float,
    /** GLSL vibrance — applied by VibranceProcessor in BasePageHolder on visible pages only. */
    val vibrance: Float,
    val isInverted: Boolean,
    val isGrayscale: Boolean,
    val isBookBackground: Boolean,
) {

    val isEmpty: Boolean
        get() = !isGrayscale && !isInverted && !isBookBackground &&
            brightness == 0f && contrast == 0f && sharpening == 0f &&
            saturation == 0f && vibrance == 0f

    /**
     * ColorMatrixColorFilter applied directly to the SSIV paint — zero re-decode cost.
     * Includes brightness, contrast, saturation, invert, grayscale and book-background tint.
     * GLSL vibrance is NOT here — VibranceProcessor applies it per visible page in BasePageHolder.
     */
    fun toColorFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        if (isGrayscale) cm.setSaturation(0f)
        if (isInverted) cm.postConcat(INVERT_MATRIX)
        if (brightness != 0f) cm.postConcat(brightnessMatrix(brightness))
        if (contrast != 0f) cm.postConcat(contrastMatrix(contrast))
        if (saturation != 0f) cm.postConcat(saturationMatrix(saturation))
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

        val EMPTY = ReaderColorFilter(
            brightness = 0f,
            contrast = 0f,
            sharpening = 0f,
            saturation = 0f,
            vibrance = 0f,
            isInverted = false,
            isGrayscale = false,
            isBookBackground = false,
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

        private fun brightnessMatrix(b: Float): ColorMatrix {
            val s = b + 1f
            return ColorMatrix().also { it.setScale(s, s, s, 1f) }
        }

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

        /**
         * Uniform saturation scale. saturation = -1..+1; 0 = unchanged.
         * Boosts ALL colours equally.
         */
        private fun saturationMatrix(s: Float): ColorMatrix {
            return ColorMatrix().also { it.setSaturation((s + 1f).coerceIn(0f, 4f)) }
        }

    }
}

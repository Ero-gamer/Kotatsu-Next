package org.koitharu.kotatsu.reader.domain

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * All image-adjustment parameters for a manga.
 *
 * Real-time GPU paint filters (applied instantly to the SSIV view via [toColorFilter]):
 *   [brightness], [contrast], [vibrance], [isInverted], [isGrayscale], [isBookBackground]
 *
 * Bitmap pre-processing filter (baked into page file at load, cached to disk):
 *   [sharpening] — via GPUImageSharpenFilter in ImageFiltersTransformation / PageLoader
 */
data class ReaderColorFilter(
    val brightness: Float,
    val contrast: Float,
    val sharpening: Float,
    val vibrance: Float,
    val isInverted: Boolean,
    val isGrayscale: Boolean,
    val isBookBackground: Boolean,
) {

    val isEmpty: Boolean
        get() = !isGrayscale && !isInverted && !isBookBackground &&
            brightness == 0f && contrast == 0f && sharpening == 0f && vibrance == 0f

    /**
     * ColorMatrixColorFilter applied directly to the SSIV paint — zero re-decode cost,
     * works for every chapter type (online, offline, zip/cbz).
     *
     * Includes brightness, contrast (ColorMatrix approximation), vibrance
     * (via saturation), invert, grayscale and book-background tint.
     */
    fun toColorFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        if (isGrayscale) cm.setSaturation(0f)
        if (isInverted) cm.postConcat(INVERT_MATRIX)
        if (brightness != 0f) cm.postConcat(brightnessMatrix(brightness))
        if (contrast != 0f) cm.postConcat(contrastMatrix(contrast))
        if (vibrance != 0f) cm.postConcat(vibranceMatrix(vibrance))
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
         * Selective saturation boost: vivid colours become more vivid,
         * near-grey colours are less affected. Approximated via saturation matrix.
         * vibrance = -1..+1; 0 = unchanged.
         */
        private fun vibranceMatrix(v: Float): ColorMatrix {
            // Boost: positive vibrance increases saturation, negative decreases.
            // Using setSaturation gives a simple, quality approximation suitable for manga.
            return ColorMatrix().also { it.setSaturation((v + 1f).coerceIn(0f, 4f)) }
        }
    }
}

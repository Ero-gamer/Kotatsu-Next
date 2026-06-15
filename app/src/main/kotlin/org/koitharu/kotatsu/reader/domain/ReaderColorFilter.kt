package org.koitharu.kotatsu.reader.domain

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * All image-adjustment parameters for a manga.
 *
 * Real-time ColorMatrix paint filters (applied instantly to the SSIV view via [toColorFilter]):
 *   [brightness], [contrast], [saturation], [vibrance], [isInverted], [isGrayscale], [isBookBackground]
 *
 * Bitmap pre-processing filters (baked into page file at load, cached to disk):
 *   [sharpening] — via GPUImageSharpenFilter in ImageFiltersTransformation / PageLoader
 */
data class ReaderColorFilter(
    val brightness: Float,
    val contrast: Float,
    val sharpening: Float,
    /** Uniform saturation scale via ColorMatrix — applied in real-time on SSIV paint. */
    val saturation: Float,
    /** ColorMatrix vibrance — luma-weighted selective boost, real-time paint filter. */
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
     * Includes brightness, contrast, saturation, vibrance, invert, grayscale and book-background tint.
     */
    fun toColorFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        if (isGrayscale) cm.setSaturation(0f)
        if (isInverted) cm.postConcat(INVERT_MATRIX)
        if (brightness != 0f) cm.postConcat(brightnessMatrix(brightness))
        if (contrast != 0f) cm.postConcat(contrastMatrix(contrast))
        if (saturation != 0f) cm.postConcat(saturationMatrix(saturation))
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

        /**
         * Luma-weighted vibrance via ColorMatrix. vibrance = -1..+1; 0 = unchanged.
         *
         * Approximates selective saturation: channels further from luma (more colourful)
         * receive less boost than near-grey channels. Better than uniform saturation for
         * preserving already-vivid colours while lifting dull ones.
         *
         * The matrix is derived from the standard Rec.709 luma weights (0.2126R 0.7152G 0.0722B)
         * and scales each channel's deviation from luma by (1 + v) where v ∈ [-1, 1].
         * This is a static approximation — not per-pixel — but perceptually superior to
         * setSaturation() for manga/webtoon content.
         */
        private fun vibranceMatrix(v: Float): ColorMatrix {
            val s = (v * 3f).coerceIn(-3f, 3f) // map -1..1 to a perceptible range
            val rw = 0.2126f; val gw = 0.7152f; val bw = 0.0722f
            // Each channel = luma + (channel - luma) * (1 + s)
            // = luma*(1 - (1+s)) + channel*(1+s)
            // = luma*(-s) + channel*(1+s)
            val rBoost = 1f + s * (1f - rw) // red gets large boost (low luma weight)
            val gBoost = 1f + s * (1f - gw) // green gets moderate boost
            val bBoost = 1f + s * (1f - bw) // blue gets large boost (low luma weight)
            val rLeak  = -s * rw            // luma bleed into red
            val gLeak  = -s * gw
            val bLeak  = -s * bw
            return ColorMatrix(floatArrayOf(
                rBoost, gLeak,  bLeak,  0f, 0f,
                rLeak,  gBoost, bLeak,  0f, 0f,
                rLeak,  gLeak,  bBoost, 0f, 0f,
                0f,     0f,     0f,     1f, 0f,
            ))
        }
    }
}

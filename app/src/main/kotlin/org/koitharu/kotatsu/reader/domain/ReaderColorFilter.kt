package org.koitharu.kotatsu.reader.domain

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * Immutable snapshot of all image-adjustment parameters for a manga/chapter.
 *
 * GPU-baked filters (applied via [ImageFiltersTransformation] in PageLoader, cached to disk):
 *   - [contrast]   → GPUImageContrastFilter
 *   - [sharpening] → GPUImageSharpenFilter
 *   - [vibrance]   → GPUImageVibranceFilter
 *
 * Real-time ColorMatrix filters (applied directly to the SSIV view via [toColorFilter]):
 *   - [brightness], [isInverted], [isGrayscale], [isBookBackground]
 *
 * The preview in ColorFilterConfigActivity uses [toPreviewColorFilter] which also
 * includes a ColorMatrix-approximation of contrast for immediate visual feedback.
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

    /** Indicates whether any GPU-pipeline parameter is active. */
    val hasGpuFilters: Boolean
        get() = contrast != 0f || sharpening > 0.01f || vibrance < -0.01f || vibrance > 0.01f

    /**
     * ColorFilter applied to the SSIV view in the reader.
     * Does NOT include contrast or vibrance — those are GPU-baked into the bitmap.
     */
    fun toColorFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        if (isGrayscale) cm.setSaturation(0f)
        if (isInverted) cm.postConcat(INVERT_MATRIX)
        if (brightness != 0f) cm.postConcat(brightnessMatrix(brightness))
        if (isBookBackground) cm.postConcat(BOOK_MATRIX)
        return ColorMatrixColorFilter(cm)
    }

    /**
     * ColorFilter for the live preview pane in ColorFilterConfigActivity.
     * Adds a ColorMatrix-approximation of contrast so the user sees immediate feedback
     * (the actual render uses GPUImageContrastFilter for higher quality).
     */
    fun toPreviewColorFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        if (isGrayscale) cm.setSaturation(0f)
        if (isInverted) cm.postConcat(INVERT_MATRIX)
        if (brightness != 0f) cm.postConcat(brightnessMatrix(brightness))
        if (contrast != 0f) cm.postConcat(contrastMatrix(contrast))
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
                -1f, 0f, 0f, 1f, 255f,
                0f, -1f, 0f, 1f, 255f,
                0f, 0f, -1f, 1f, 255f,
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

        private fun brightnessMatrix(brightness: Float): ColorMatrix {
            val s = brightness + 1f
            return ColorMatrix().also { it.setScale(s, s, s, 1f) }
        }

        private fun contrastMatrix(contrast: Float): ColorMatrix {
            val s = contrast + 1f
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
    }
}

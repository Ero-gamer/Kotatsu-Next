package org.koitharu.kotatsu.reader.domain

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

data class ReaderColorFilter(
    val brightness: Float,
    val contrast: Float,
    val sharpening: Float,
    val saturation: Float,
    val vibrance: Float,
    // Default 0f so existing callers (AppSettings etc.) compile without changes.
    val denoise: Float = 0f,
    val dither: Float = 0f,
    val grain: Float = 0f,
    val isInverted: Boolean,
    val isGrayscale: Boolean,
    val isBookBackground: Boolean,
) {

    val isEmpty: Boolean
        get() = !isGrayscale && !isInverted && !isBookBackground &&
            brightness == 0f && contrast == 0f && sharpening == 0f &&
            saturation == 0f && vibrance == 0f &&
            denoise == 0f && dither == 0f && grain == 0f

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
    } else null

    companion object {

        private const val BOOK_BLUE_FACTOR = 0.92f

        val EMPTY = ReaderColorFilter(
            brightness = 0f, contrast = 0f, sharpening = 0f,
            saturation = 0f, vibrance = 0f,
            denoise = 0f, dither = 0f, grain = 0f,
            isInverted = false, isGrayscale = false, isBookBackground = false,
        )

        private val INVERT_MATRIX = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))

        private val BOOK_MATRIX = ColorMatrix(floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, BOOK_BLUE_FACTOR, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))

        private fun brightnessMatrix(b: Float) =
            ColorMatrix().also { it.setScale(b + 1f, b + 1f, b + 1f, 1f) }

        private fun contrastMatrix(c: Float): ColorMatrix {
            val s = c + 1f; val t = (-0.5f * s + 0.5f) * 255f
            return ColorMatrix(floatArrayOf(s,0f,0f,0f,t, 0f,s,0f,0f,t, 0f,0f,s,0f,t, 0f,0f,0f,1f,0f))
        }

        private fun saturationMatrix(s: Float) =
            ColorMatrix().also { it.setSaturation((s + 1f).coerceIn(0f, 4f)) }
    }
}

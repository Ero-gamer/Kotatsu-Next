package org.koitharu.kotatsu.core.ui.image

import android.content.Context
import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter

/**
 * GPU-accelerated Coil3 [Transformation] applying sharpening via GPUImageSharpenFilter.
 *
 * Contrast and vibrance are handled in real time by [ReaderColorFilter.toColorFilter]
 * (SSIV paint ColorMatrix — zero re-decode cost, works for all source types).
 *
 * Sharpening requires bitmap pre-processing because a convolution kernel changes pixel
 * data; it cannot be expressed as a 5×4 ColorMatrix.
 *
 * sharpening: 0.0 → 1.0 (0 = no-op → GPUImageSharpenFilter 0.0–2.0)
 */
class ImageFiltersTransformation(
    private val context: Context,
    private val sharpening: Float,
) : Transformation() {

    override val cacheKey: String = "gpu_sharpen_s${sharpening}_v2"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (sharpening <= 0.01f) return input

        // HARDWARE bitmaps cannot be read by GPUImage's off-screen PixelBuffer.
        val safeInput = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            input
        }

        val filter = GPUImageSharpenFilter(sharpening * 2f)
        val gpuImage = GPUImage(context.applicationContext)
        gpuImage.setFilter(filter)

        // getBitmapWithFilterApplied returns null only on EGL context failure.
        val result = gpuImage.getBitmapWithFilterApplied(safeInput)

        // Recycle the temporary copy ONLY if GPUImage produced a new bitmap.
        // If result is null we fall back to safeInput, so we must NOT recycle it.
        if (safeInput !== input && result != null) {
            safeInput.recycle()
        }

        return result ?: safeInput
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ImageFiltersTransformation && sharpening == other.sharpening
    }

    override fun hashCode(): Int = sharpening.hashCode()
}

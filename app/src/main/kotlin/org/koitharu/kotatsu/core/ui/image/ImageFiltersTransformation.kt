package org.koitharu.kotatsu.core.ui.image

import android.content.Context
import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageContrastFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVibranceFilter

/**
 * GPU-accelerated Coil3 [Transformation] applying Contrast, Sharpening and Vibrance
 * via GPUImage (OpenGL ES 2.0 off-screen PixelBuffer — safe on any background thread).
 *
 * Results are automatically cached in Coil's memory/disk pipeline via [cacheKey].
 * The caller (PageLoader) additionally persists processed bitmaps in ProcessedPageCache
 * so re-reads of the same chapter page are instant.
 *
 * Parameter ranges (user-facing, mapped internally):
 *   contrast  : -1.0 → +1.0  (0 = unchanged → GPUImageContrastFilter 0.0–2.0, normal=1.0)
 *   sharpening:  0.0 → +1.0  (0 = off        → GPUImageSharpenFilter 0.0–2.0)
 *   vibrance  : -1.0 → +1.0  (0 = unchanged  → GPUImageVibranceFilter −1.2–+1.2)
 */
class ImageFiltersTransformation(
    private val context: Context,
    private val contrast: Float = 0f,
    private val sharpening: Float = 0f,
    private val vibrance: Float = 0f,
) : Transformation() {

    override val cacheKey: String =
        "gpu_filters_c${contrast}_s${sharpening}_v${vibrance}_v1"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        // Collect only the filters that are actually active to keep the pipeline lean
        val filters = ArrayList<GPUImageFilter>(3)

        if (contrast != 0f) {
            // GPUImageContrastFilter: 1.0 = unchanged, range 0.0–4.0
            // User range -1..+1 → internal range 0..2
            filters.add(GPUImageContrastFilter((contrast + 1f).coerceIn(0f, 4f)))
        }

        if (sharpening > 0.01f) {
            // GPUImageSharpenFilter: 0.0 = unchanged, positive = sharper
            // User range 0..1 → internal range 0..2
            filters.add(GPUImageSharpenFilter(sharpening * 2f))
        }

        if (vibrance < -0.01f || vibrance > 0.01f) {
            // GPUImageVibranceFilter: 0.0 = unchanged; range −1.2..+1.2 is typical
            // User range -1..+1 → scaled to -1.2..+1.2 for a natural look
            val vibranceFilter = GPUImageVibranceFilter()
            vibranceFilter.setVibrance(vibrance * 1.2f)
            filters.add(vibranceFilter)
        }

        if (filters.isEmpty()) return input

        // Hardware-accelerated bitmaps cannot be read by GPUImage's PixelBuffer;
        // copy to a software-backed ARGB_8888 bitmap first.
        val safeInput = if (input.config == Bitmap.Config.HARDWARE) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            input
        }

        return try {
            // GPUImage(Context) creates an off-screen EGL surface — no main-thread required.
            val gpuImage = GPUImage(context.applicationContext)
            gpuImage.setFilter(if (filters.size == 1) filters[0] else GPUImageFilterGroup(filters))
            // getBitmapWithFilterApplied returns null only on EGL init failure
            gpuImage.getBitmapWithFilterApplied(safeInput) ?: safeInput
        } finally {
            if (safeInput !== input) safeInput.recycle()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ImageFiltersTransformation &&
            contrast == other.contrast &&
            sharpening == other.sharpening &&
            vibrance == other.vibrance
    }

    override fun hashCode(): Int {
        var r = contrast.hashCode()
        r = 31 * r + sharpening.hashCode()
        r = 31 * r + vibrance.hashCode()
        return r
    }
}

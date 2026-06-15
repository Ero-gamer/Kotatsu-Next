package org.koitharu.kotatsu.core.ui.image

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import android.content.Context

/**
 * GPU-accelerated Coil3 [Transformation] applying sharpening via GPUImage.
 *
 * All other filters (contrast, saturation, vibrance, brightness) are applied in real time
 * as ColorMatrix paint filters on SSIV — no bitmap processing required for those.
 *
 * sharpening: 0.0 → 1.0 (mapped to GPUImageSharpenFilter 0.0 → 2.0)
 */
class ImageFiltersTransformation(
    private val context: Context,
    private val sharpening: Float,
) : Transformation() {

    override val cacheKey: String = "gpu_sharpen_s${sharpening}_v5"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (sharpening <= 0.01f) return input
        return gpuSemaphore.withPermit { applySharpening(input) }
    }

    private fun applySharpening(input: Bitmap): Bitmap {
        val needsCopy = input.config != Bitmap.Config.ARGB_8888
        val argbInput = if (needsCopy) input.copy(Bitmap.Config.ARGB_8888, false) else input
        return try {
            val gpu = getOrCreateGpuImage(context)
            gpu.setFilter(GPUImageSharpenFilter(sharpening * 2f))
            val result = gpu.getBitmapWithFilterApplied(argbInput)
            if (result != null) {
                if (needsCopy) argbInput.recycle()
                result
            } else {
                invalidateGpuContext()
                if (needsCopy) argbInput.recycle()
                input
            }
        } catch (e: Exception) {
            invalidateGpuContext()
            if (needsCopy) argbInput.recycle()
            input
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        return other is ImageFiltersTransformation && sharpening == other.sharpening
    }

    override fun hashCode(): Int = sharpening.hashCode()

    companion object {
        private val gpuSemaphore = Semaphore(1)
        @Volatile private var sharedGpuImage: GPUImage? = null

        private fun getOrCreateGpuImage(context: Context): GPUImage {
            return sharedGpuImage ?: GPUImage(context.applicationContext).also { sharedGpuImage = it }
        }

        internal fun invalidateGpuContext() {
            sharedGpuImage = null
        }
    }
}

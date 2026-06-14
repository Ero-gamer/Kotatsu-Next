package org.koitharu.kotatsu.core.ui.image

import android.content.Context
import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageFilterGroup
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter
import jp.co.cyberagent.android.gpuimage.filter.GPUImageVibranceFilter
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * GPU-accelerated Coil3 [Transformation] applying sharpening and/or GLSL vibrance via GPUImage.
 *
 * Contrast, saturation and brightness are applied in real time as ColorMatrix paint filters
 * on SSIV — no bitmap processing required for those.
 *
 * Design notes:
 * - [gpuSemaphore] serialises all GPU bitmap ops to a single concurrent slot.
 *   GPUImage uses an off-screen EGL pbuffer; multiple concurrent sessions on low-end
 *   devices causes GPU memory spikes and driver instability.
 * - A shared [gpuImage] instance is reused across calls (EGL context created only once).
 *   This is safe because the semaphore guarantees single-threaded GPU access.
 * - All input configs are normalised to ARGB_8888 before processing. RGB_565 inputs
 *   (used on ≤2 GB RAM devices for JPEG pages) cause native GL errors not catchable by Kotlin.
 * - The temporary ARGB copy is recycled immediately after GPUImage produces its result.
 * - When both sharpening and vibrance are active, a [GPUImageFilterGroup] runs them in a
 *   single GPU pass — same cost as sharpening alone.
 * - GLSL vibrance (GPUImageVibranceFilter) selectively boosts undersaturated pixels:
 *   avg=(r+g+b)/3, mx=max(r,g,b), amt=(mx−avg)*(-vibrance*3); color=mix(color,mx,amt).
 *   High-saturation pixels are largely untouched; near-grey pixels get the biggest lift.
 *
 * sharpening: 0.0 → 1.0 (mapped to GPUImageSharpenFilter 0.0 → 2.0)
 * vibrance:  -1.0 → +1.0 (GPUImageVibranceFilter range)
 */
class ImageFiltersTransformation(
    private val context: Context,
    private val sharpening: Float,
    private val vibrance: Float = 0f,
) : Transformation() {

    override val cacheKey: String = "gpu_filters_s${sharpening}_v${vibrance}_v5"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val needsSharpening = sharpening > 0.01f
        val needsVibrance = vibrance > 0.01f || vibrance < -0.01f
        if (!needsSharpening && !needsVibrance) return input
        return gpuSemaphore.withPermit { applyFilters(input, needsSharpening, needsVibrance) }
    }

    private fun applyFilters(input: Bitmap, needsSharpening: Boolean, needsVibrance: Boolean): Bitmap {
        val needsCopy = input.config != Bitmap.Config.ARGB_8888
        val argbInput = if (needsCopy) input.copy(Bitmap.Config.ARGB_8888, false) else input

        return try {
            val gpu = getOrCreateGpuImage(context)
            val filter: GPUImageFilter = when {
                needsSharpening && needsVibrance -> GPUImageFilterGroup(
                    listOf(
                        GPUImageSharpenFilter(sharpening * 2f),
                        GPUImageVibranceFilter(vibrance),
                    ),
                )
                needsSharpening -> GPUImageSharpenFilter(sharpening * 2f)
                else -> GPUImageVibranceFilter(vibrance)
            }
            gpu.setFilter(filter)
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
        return other is ImageFiltersTransformation &&
            sharpening == other.sharpening &&
            vibrance == other.vibrance
    }

    override fun hashCode(): Int = 31 * sharpening.hashCode() + vibrance.hashCode()

    companion object {
        private val gpuSemaphore = Semaphore(1)

        @Volatile private var sharedGpuImage: GPUImage? = null

        private fun getOrCreateGpuImage(context: Context): GPUImage {
            return sharedGpuImage ?: GPUImage(context.applicationContext).also {
                sharedGpuImage = it
            }
        }

        internal fun invalidateGpuContext() {
            sharedGpuImage = null
        }
    }
}

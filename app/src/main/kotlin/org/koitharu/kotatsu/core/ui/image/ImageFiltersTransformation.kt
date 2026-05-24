package org.koitharu.kotatsu.core.ui.image

import android.content.Context
import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import jp.co.cyberagent.android.gpuimage.GPUImage
import jp.co.cyberagent.android.gpuimage.filter.GPUImageSharpenFilter
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * GPU-accelerated Coil3 [Transformation] applying sharpening via GPUImageSharpenFilter.
 *
 * Contrast, vibrance and brightness are applied in real time as ColorMatrix paint filters
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
 *
 * sharpening: 0.0 → 1.0 (mapped to GPUImageSharpenFilter 0.0 → 2.0)
 */
class ImageFiltersTransformation(
    private val context: Context,
    private val sharpening: Float,
) : Transformation() {

    override val cacheKey: String = "gpu_sharpen_s${sharpening}_v4"

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
                // EGL failure (getBitmapWithFilterApplied returned null).
                // Invalidate the shared context so it is rebuilt fresh next call,
                // preventing the filter from being silently stuck at "no sharpening"
                // after GPU context loss (e.g. app backgrounding on low-end devices).
                invalidateGpuContext()
                if (needsCopy) argbInput.recycle()
                input
            }
        } catch (e: Exception) {
            // Any GPU/EGL exception — invalidate context so next call rebuilds it cleanly.
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
        /** Single permit — GPU processing is strictly sequential. */
        private val gpuSemaphore = Semaphore(1)

        /**
         * Shared GPUImage instance created once and reused.
         * Safe because [gpuSemaphore] ensures only one thread accesses it at a time.
         *
         * Nulled on EGL failure so a fresh context is created on the next call,
         * preventing silent "filter stuck at 0" when the EGL surface is invalidated
         * (e.g. after app backgrounding on low-end devices or OOM recovery).
         */
        @Volatile private var sharedGpuImage: GPUImage? = null

        private fun getOrCreateGpuImage(context: Context): GPUImage {
            return sharedGpuImage ?: GPUImage(context.applicationContext).also {
                sharedGpuImage = it
            }
        }

        /** Clears the shared GPU context so it is recreated on the next filter call.
         *  Called after any EGL/GPU failure to ensure the filter is not silently skipped. */
        internal fun invalidateGpuContext() {
            sharedGpuImage = null
        }
    }
}

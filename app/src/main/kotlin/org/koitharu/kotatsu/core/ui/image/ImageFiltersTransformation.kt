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
 * Contrast, vibrance and brightness are handled in real time by
 * [ReaderColorFilter.toColorFilter] as a ColorMatrix paint on SSIV — no bitmap processing.
 *
 * Design notes:
 * - A single [gpuSemaphore] serialises all GPU bitmap processing across the app.
 *   GPUImage creates an off-screen EGL pbuffer; having many concurrent sessions on
 *   low-end devices causes driver crashes and spikes GPU memory.
 * - Input bitmaps are always converted to ARGB_8888 before being handed to GPUImage.
 *   RGB_565 (used by the app on <2 GB RAM devices for JPEG pages) causes native GL
 *   errors that cannot be caught by Kotlin exception handling.
 * - HARDWARE bitmaps (ImageDecoder default on API 28+) are also converted.
 *   The conversion is done in-place: the copy is recycled immediately after GPUImage
 *   produces its output bitmap, so peak RAM is (source + ARGB copy + GPUImage output),
 *   all three recycled before the method returns except the output.
 *
 * sharpening: 0.0 → 1.0 (maps to GPUImageSharpenFilter 0.0 → 2.0)
 */
class ImageFiltersTransformation(
    private val context: Context,
    private val sharpening: Float,
) : Transformation() {

    override val cacheKey: String = "gpu_sharpen_s${sharpening}_v3"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        if (sharpening <= 0.01f) return input

        return gpuSemaphore.withPermit {
            applySharpening(input)
        }
    }

    private fun applySharpening(input: Bitmap): Bitmap {
        // GPUImage requires ARGB_8888. Convert HARDWARE, RGB_565, or any other config.
        val needsCopy = input.config != Bitmap.Config.ARGB_8888
        val argbInput = if (needsCopy) {
            input.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            input
        }

        return try {
            val gpuImage = GPUImage(context.applicationContext)
            gpuImage.setFilter(GPUImageSharpenFilter(sharpening * 2f))
            val result = gpuImage.getBitmapWithFilterApplied(argbInput)
            if (result != null) {
                // GPUImage produced a new bitmap — recycle intermediate copy if we made one
                if (needsCopy) argbInput.recycle()
                result
            } else {
                // EGL failure: fall back to unfiltered but don't crash
                if (needsCopy) argbInput else input
            }
        } catch (e: Exception) {
            // Catch any GPU driver exception; fall back gracefully
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
        /**
         * Limits concurrent GPUImage bitmap operations to 1 across the whole app.
         * Prevents multi-page webtoon loading from spawning multiple EGL contexts
         * simultaneously, which causes GPU memory spikes and driver instability on
         * low-end devices.
         */
        private val gpuSemaphore = Semaphore(1)
    }
}

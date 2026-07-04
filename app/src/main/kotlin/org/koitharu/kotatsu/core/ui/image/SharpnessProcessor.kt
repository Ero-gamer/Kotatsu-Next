package org.koitharu.kotatsu.core.ui.image

import kotlin.math.abs

/**
 * Pure per-channel sharpen and denoise math — no bitmap/array handling here, see
 * [ImageFiltersTransformation] for the single combined getPixels/loop/setPixels pass.
 *
 * [denoiseChannel] runs immediately before [sharpenChannel] when sharpening is active,
 * replacing the raw center value fed to the Laplacian with a bilateral-lite blend —
 * this stops the sharpen kernel from amplifying JPEG/compression noise alongside real edges.
 * The same 4 neighbour values already fetched for sharpening are reused, so denoise
 * adds zero extra pixel reads and runs as part of the same per-pixel loop.
 *
 * Keep constants in sync with [com.davemorrissey.labs.subscaleview.decoder.FilteringRegionDecoder].
 */
object SharpnessProcessor {

    private const val SHARPEN_SCALAR = 0.5f
    private const val DENOISE_STRENGTH = 0.6f
    private const val DENOISE_RANGE_FALLOFF = 0.15f

    /** Converts the raw 0..1 slider value into the kernel strength used by [sharpenChannel]. */
    fun kernelStrength(amount: Float): Float = amount.coerceIn(0f, 1f) * SHARPEN_SCALAR

    /**
     * Standard 5-point discrete Laplacian sharpen for one channel (0..255).
     * Caller should pass denoised values for [center] when sharpening is active.
     */
    fun sharpenChannel(center: Int, top: Int, bottom: Int, left: Int, right: Int, k: Float): Int {
        val sharpened = center + k * (4 * center - top - bottom - left - right)
        return when {
            sharpened <= 0f -> 0
            sharpened >= 255f -> 255
            else -> (sharpened + 0.5f).toInt()
        }
    }

    /**
     * Edge-aware (bilateral-lite) denoise for one channel (0..255).
     * Blends [center] toward its 4 neighbours, weighting each neighbour DOWN the more
     * it differs from center — preserves real edges while smoothing flat noisy regions.
     */
    fun denoiseChannel(center: Int, top: Int, bottom: Int, left: Int, right: Int): Int {
        val wT = 1f / (1f + abs(center - top) * DENOISE_RANGE_FALLOFF)
        val wB = 1f / (1f + abs(center - bottom) * DENOISE_RANGE_FALLOFF)
        val wL = 1f / (1f + abs(center - left) * DENOISE_RANGE_FALLOFF)
        val wR = 1f / (1f + abs(center - right) * DENOISE_RANGE_FALLOFF)
        val wSum = 1f + wT + wB + wL + wR
        val denoised = (center + top * wT + bottom * wB + left * wL + right * wR) / wSum
        val result = center + DENOISE_STRENGTH * (denoised - center)
        return when {
            result <= 0f -> 0
            result >= 255f -> 255
            else -> (result + 0.5f).toInt()
        }
    }
}

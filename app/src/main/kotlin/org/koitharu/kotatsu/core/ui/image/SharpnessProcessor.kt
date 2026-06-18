package org.koitharu.kotatsu.core.ui.image

/**
 * Pure per-channel sharpen math — no bitmap/array handling here, see
 * [ImageFiltersTransformation] for the single combined getPixels/loop/setPixels pass.
 *
 * Replaces the previous GPUImage/GLSL sharpening pass. Why CPU instead of GPU:
 *   GPUImage needs decode -> GL texture upload -> FBO render -> readback, peaking at roughly
 *   3x the bitmap's memory, AND that memory lives in a separate GPU-side pool that's more
 *   constrained on a low-end integrated GPU. A plain 5-point Laplacian sharpen needs only the
 *   source pixel array plus one same-size output array (both ordinary CPU heap), with no GL
 *   context, no shader compilation, and no texture/FBO allocation at all — meaningfully less
 *   peak memory and no GPU driver risk on the Oppo A11k class of hardware.
 *
 * [sharpenChannel] is a pure function over already-unpacked 0..255 channel values (no
 * allocation), so the caller's per-pixel loop in [ImageFiltersTransformation] stays
 * allocation-free for both sharpening and vibrance.
 */
object SharpnessProcessor {

    /** Maps the 0..1 slider to kernel strength k = amount * SHARPEN_SCALAR (0..0.5). */
    private const val SHARPEN_SCALAR = 0.5f

    /** Converts the raw 0..1 slider value into the kernel strength used by [sharpenChannel]. */
    fun kernelStrength(amount: Float): Float = amount.coerceIn(0f, 1f) * SHARPEN_SCALAR

    /**
     * Standard 5-point discrete Laplacian sharpen for one channel (0..255):
     *   out = center + k * (4*center - top - bottom - left - right)
     * Equivalent to adding k times the discrete Laplacian (an edge/detail estimate) back onto
     * the original value — the classic single-pass "unsharp" kernel. Result clamped to 0..255.
     */
    fun sharpenChannel(center: Int, top: Int, bottom: Int, left: Int, right: Int, k: Float): Int {
        val sharpened = center + k * (4 * center - top - bottom - left - right)
        return when {
            sharpened <= 0f -> 0
            sharpened >= 255f -> 255
            else -> (sharpened + 0.5f).toInt()
        }
    }
}

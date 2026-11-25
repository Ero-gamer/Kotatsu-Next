package org.koitharu.kotatsu.reader.domain

import kotlin.math.roundToInt

/** Pure maths of [ReaderColorFilter.overlayColor]; kept apart from Android types so JVM tests can load it. */
internal object OverlayColor {

    /**
     * Straight-alpha ARGB of "black at [darken], then ([red], [green], [blue]) at [alpha] on top" (all 0..1), or 0
     * when nothing is drawn. Compositing: a = a2 + a1 (1 - a2), color = c2 a2 / a (black adds no colour).
     */
    fun argb(darken: Float, red: Float, green: Float, blue: Float, alpha: Float): Int {
        val a1 = darken.coerceIn(0f, 1f)
        val a2 = alpha.coerceIn(0f, 1f)
        val a = a2 + a1 * (1f - a2)
        if (a <= 0f) return 0
        val k = a2 / a
        // Packed by hand (same layout as Color.argb) so no Android class is touched.
        return ((a * 255f).roundToInt().coerceIn(0, 255) shl 24) or
            ((red.coerceIn(0f, 1f) * k * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((green.coerceIn(0f, 1f) * k * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            (blue.coerceIn(0f, 1f) * k * 255f).roundToInt().coerceIn(0, 255)
    }
}

package org.koitharu.kotatsu.core.image

import org.junit.Assert.assertEquals
import org.junit.Test

class WebpPolicyTest {

    private fun auto(f: ImageFormat, s: SourceQuality) = WebpPolicy.autoTarget(f, s)

    @Test
    fun autoPngIsAlwaysLossyQ98() {
        assertEquals(WebpTarget.Lossy(98), auto(ImageFormat.PNG, SourceQuality.Lossless))
        assertEquals(WebpTarget.Lossy(98), auto(ImageFormat.PNG, SourceQuality.Unknown))
    }

    @Test
    fun autoBmpAndGifBecomeQ95() {
        assertEquals(WebpTarget.Lossy(95), auto(ImageFormat.BMP, SourceQuality.Unknown))
        assertEquals(WebpTarget.Lossy(95), auto(ImageFormat.GIF, SourceQuality.Lossless))
    }

    @Test
    fun autoKeepsLossySourceQuality() {
        assertEquals(WebpTarget.Lossy(82), auto(ImageFormat.JPEG, SourceQuality.Lossy(82)))
        assertEquals(WebpTarget.Lossy(100), auto(ImageFormat.AVIF, SourceQuality.Lossy(100)))
    }

    @Test
    fun autoLosslessSourceBecomesQ95() {
        assertEquals(WebpTarget.Lossy(95), auto(ImageFormat.AVIF, SourceQuality.Lossless))
        assertEquals(WebpTarget.Lossy(95), auto(ImageFormat.JXL, SourceQuality.Lossless))
        assertEquals(WebpTarget.Lossy(95), auto(ImageFormat.WEBP, SourceQuality.Lossless))
    }

    @Test
    fun autoUnknownOrUnreasonableFallsBackToQ95() {
        assertEquals(WebpTarget.Lossy(95), auto(ImageFormat.JPEG, SourceQuality.Unknown))
        assertEquals(WebpTarget.Lossy(95), auto(ImageFormat.JXL, SourceQuality.Unknown))
        assertEquals(WebpTarget.Lossy(95), auto(ImageFormat.AVIF, SourceQuality.Lossy(12)))
    }

    @Test
    fun fixedModeKeepsOlderBehaviour() {
        assertEquals(WebpTarget.Lossless, WebpPolicy.fixedTarget(ImageFormat.PNG, SourceQuality.Unknown, 90))
        assertEquals(WebpTarget.Lossy(90), WebpPolicy.fixedTarget(ImageFormat.JPEG, SourceQuality.Unknown, 90))
        assertEquals(WebpTarget.Lossy(70), WebpPolicy.fixedTarget(ImageFormat.AVIF, SourceQuality.Lossy(70), 90))
        assertEquals(WebpTarget.Lossless, WebpPolicy.target(false, ImageFormat.GIF, SourceQuality.Unknown, 90))
    }
}

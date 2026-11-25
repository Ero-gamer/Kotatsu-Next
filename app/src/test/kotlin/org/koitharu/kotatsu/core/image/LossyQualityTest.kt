package org.koitharu.kotatsu.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LossyQualityTest {

    // JPEG headers (SOI + both DQT tables + SOS stub) made with libjpeg at a known quality.
    private val jpeg30 = "ffd8ffdb0043001b12141714111b1716171e1c1b2028422b28252528513a3d3042605565645f555d5b6a7899816a7190735b5d85b586909ea3abadab6780bcc9baa6c799a8aba4ffdb0043011c1e1e2823284e2b2b4ea46e5d6ea4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4a4ffda0002"
    private val jpeg75 = "ffd8ffdb004300080606070605080707070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffdb0043010909090c0b0c180d0d1832211c213232323232323232323232323232323232323232323232323232323232323232323232323232323232323232323232323232ffda0002"
    private val jpeg95 = "ffd8ffdb0043000201010101010201010102020202020403020202020504040304060506060605060606070908060709070606080b08090a0a0a0a0a06080b0c0b0a0c090a0a0affdb004301020202020202050303050a0706070a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0a0affda0002"
    private val jpegCustom = "ffd8ffdb004300080606070608080707070909080a0c140d0c0b0b0c1912130f141d1a1f1e1d1a1c1c20242e2720222c231c1c2837292c30313434341f27393d38323c2e333432ffdb0043010909090c0b0c180d0d1832211c213232323232323232323232323232323232323232323232323232323232323232323232323232323232323232323232323232ffda0002"
    private val webpLossy75 = "524946460e0b00005745425056503820020b0000b027009d012a4000400002003425b00274ca12211204e7fee61e8b8c"
    private val webpLossless = "524946466e300000574542505650384c623000002f3fc00f009020000001355d"

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun analyze(s: String, format: ImageFormat): LossyQuality.Result? {
        val b = hex(s)
        return LossyQuality.analyze(b, b.size, format)
    }

    @Test
    fun jpegStandardTablesAreExact() {
        for ((q, s) in listOf(30 to jpeg30, 75 to jpeg75, 95 to jpeg95)) {
            val r = analyze(s, ImageFormat.JPEG)
            assertNotNull(r)
            assertEquals(LossyQuality.Kind.EXACT, r!!.kind)
            assertEquals(q, r.quality)
        }
    }

    @Test
    fun jpegCustomTableIsEstimate() {
        val r = analyze(jpegCustom, ImageFormat.JPEG)!!
        assertEquals(LossyQuality.Kind.ESTIMATE, r.kind)
        assertTrue(r.quality in 73..77)
    }

    @Test
    fun webpLossyIsEstimateNearEncoderQuality() {
        val r = analyze(webpLossy75, ImageFormat.WEBP)!!
        assertEquals(LossyQuality.Kind.ESTIMATE, r.kind)
        assertTrue(r.quality in 72..78)
    }

    @Test
    fun losslessFormats() {
        assertEquals(LossyQuality.Kind.LOSSLESS, analyze(webpLossless, ImageFormat.WEBP)!!.kind)
        assertEquals(LossyQuality.Kind.LOSSLESS, analyze("89504e470d0a1a0a", ImageFormat.PNG)!!.kind)
    }

    @Test
    fun garbageAndTruncatedInputGiveNull() {
        assertNull(analyze("ffd8", ImageFormat.JPEG))
        assertNull(analyze("ffd8ffdb0043", ImageFormat.JPEG))
        assertNull(analyze("524946460e0b0000", ImageFormat.WEBP))
        assertNull(analyze("00112233445566778899", ImageFormat.AVIF))
        assertNull(analyze("00112233445566778899", ImageFormat.JXL))
    }
}

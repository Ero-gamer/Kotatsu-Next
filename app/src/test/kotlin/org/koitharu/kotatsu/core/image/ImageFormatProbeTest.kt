package org.koitharu.kotatsu.core.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class ImageFormatProbeTest {

    private fun bytes(build: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(build).toByteArray()

    private fun ByteArrayOutputStream.ascii(s: String) = write(s.toByteArray(Charsets.ISO_8859_1))

    private fun ByteArrayOutputStream.be16(v: Int) {
        write(v shr 8)
        write(v)
    }

    private fun ByteArrayOutputStream.be32(v: Int) {
        write(v ushr 24)
        write(v ushr 16)
        write(v ushr 8)
        write(v)
    }

    private fun ByteArrayOutputStream.le16(v: Int) {
        write(v)
        write(v shr 8)
    }

    private fun ByteArrayOutputStream.le32(v: Long) {
        for (i in 0..3) write((v shr (8 * i)).toInt())
    }

    private fun jpeg(sof: Int, w: Int, h: Int) = bytes {
        write(0xFF)
        write(0xD8)
        write(0xFF)
        write(sof)
        be16(17)
        write(8)
        be16(h)
        be16(w)
        write(3)
        repeat(9) { write(0) }
        write(0xFF)
        write(0xD9)
    }

    private fun png(w: Int, h: Int, colorType: Int, actl: Int = 0) = bytes {
        write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        be32(13)
        ascii("IHDR")
        be32(w)
        be32(h)
        write(8)
        write(colorType)
        write(0)
        write(0)
        write(0)
        be32(0) // crc
        if (actl > 0) {
            be32(8)
            ascii("acTL")
            be32(actl)
            be32(0)
            be32(0)
        }
        be32(0)
        ascii("IDAT")
    }

    private fun info(b: ByteArray) = ImageFormatProbe.probe(b)

    @Test
    fun jpegBaselineSize() {
        val i = info(jpeg(0xC0, 640, 480))
        assertEquals(ImageFormat.JPEG, i.format)
        assertEquals(640, i.width)
        assertEquals(480, i.height)
        assertEquals(3, i.components)
        assertFalse(i.progressive)
    }

    @Test
    fun jpegProgressiveFlag() {
        assertTrue(info(jpeg(0xC2, 10, 20)).progressive)
    }

    @Test
    fun pngSizeAndAlpha() {
        val rgb = info(png(100, 200, colorType = 2))
        assertEquals(ImageFormat.PNG, rgb.format)
        assertEquals(100, rgb.width)
        assertEquals(200, rgb.height)
        assertEquals(false, rgb.hasAlpha)
        assertFalse(rgb.animated)
        assertEquals(true, info(png(1, 1, colorType = 6)).hasAlpha)
    }

    @Test
    fun apngIsAnimated() {
        val i = info(png(64, 64, colorType = 6, actl = 5))
        assertTrue(i.animated)
        assertEquals(5, i.frameCount)
    }

    @Test
    fun gifSize() {
        val b = bytes {
            ascii("GIF89a")
            le16(33)
            le16(44)
            write(0)
            write(0)
            write(0)
            write(0x3B)
        }
        val i = info(b)
        assertEquals(ImageFormat.GIF, i.format)
        assertEquals(33, i.width)
        assertEquals(44, i.height)
    }

    @Test
    fun webpLosslessSize() {
        val b = bytes {
            ascii("RIFF")
            le32(30)
            ascii("WEBP")
            ascii("VP8L")
            le32(10)
            write(0x2F)
            le32((300L - 1) or ((500L - 1) shl 14) or (1L shl 28))
            write(0)
        }
        val i = info(b)
        assertEquals(ImageFormat.WEBP, i.format)
        assertEquals(300, i.width)
        assertEquals(500, i.height)
        assertEquals(true, i.hasAlpha)
    }

    @Test
    fun sniffJxlCodestreamAndContainer() {
        assertEquals(ImageFormat.JXL, ImageFormatProbe.sniff(byteArrayOf(0xFF.toByte(), 0x0A, 0, 0)))
        val container = bytes {
            be32(12)
            ascii("JXL ")
            be32(0x0D0A870A)
        }
        assertEquals(ImageFormat.JXL, ImageFormatProbe.sniff(container))
    }

    @Test
    fun sniffBmp() {
        val b = bytes {
            ascii("BM")
            repeat(12) { write(0) }
            le32(40)
        }
        assertEquals(ImageFormat.BMP, ImageFormatProbe.sniff(b))
    }

    @Test
    fun garbageAndEmptyAreUnknown() {
        assertEquals(ImageFormat.UNKNOWN, info(ByteArray(0)).format)
        assertEquals(ImageFormat.UNKNOWN, info("<html>not an image</html>".toByteArray()).format)
        assertFalse(info(ByteArray(3)).isKnown)
    }

    @Test
    fun truncatedHeaderDoesNotThrow() {
        val full = png(10, 10, colorType = 2)
        for (n in 0..full.size) {
            ImageFormatProbe.probe(full, n, truncated = true)
        }
    }

    @Test
    fun extensionMapping() {
        assertEquals(ImageFormat.JPEG, ImageFormatProbe.formatFromExtension("a.JPEG"))
        assertEquals(ImageFormat.PNG, ImageFormatProbe.formatFromExtension("a.apng"))
        assertEquals(ImageFormat.JXL, ImageFormatProbe.formatFromExtension("x/y.jxl"))
        assertEquals(ImageFormat.UNKNOWN, ImageFormatProbe.formatFromExtension("noext"))
        assertNull(ImageFormat.UNKNOWN.extension)
    }

    @Test
    fun fileSizeIsReported() {
        val b = jpeg(0xC0, 5, 5)
        assertEquals(b.size.toLong(), info(b).fileSize)
    }

    /** One minimal valid header per format, so a format added to [ImageFormat] without a probe entry fails here. */
    private fun samples(): Map<ImageFormat, ByteArray> = mapOf(
        ImageFormat.JPEG to jpeg(0xC0, 8, 8),
        ImageFormat.PNG to png(8, 8, colorType = 2),
        ImageFormat.GIF to bytes {
            ascii("GIF89a")
            le16(8)
            le16(8)
            write(0)
            write(0)
            write(0)
            write(0x3B)
        },
        ImageFormat.WEBP to bytes {
            ascii("RIFF")
            le32(30)
            ascii("WEBP")
            ascii("VP8L")
            le32(10)
            write(0x2F)
            le32(7L or (7L shl 14))
            write(0)
        },
        ImageFormat.AVIF to bytes {
            be32(20)
            ascii("ftyp")
            ascii("avif")
            be32(0)
            ascii("mif1")
        },
        ImageFormat.JXL to byteArrayOf(0xFF.toByte(), 0x0A, 0, 0),
        ImageFormat.BMP to bytes {
            ascii("BM")
            repeat(12) { write(0) }
            le32(40)
        },
    )

    @Test
    fun everyFormatIsConsistentAcrossTheTables() {
        val samples = samples()
        assertEquals(ImageFormat.entries.filter { it != ImageFormat.UNKNOWN }.toSet(), samples.keys)
        for ((format, data) in samples) {
            assertEquals("sniff $format", format, ImageFormatProbe.sniff(data))
            assertEquals("probe $format", format, info(data).format)
            val ext = format.extension
            val mime = format.mimeType
            assertTrue("extension $format", !ext.isNullOrEmpty() && ext == ext.lowercase())
            assertTrue("mime $format", mime != null && mime.startsWith("image/"))
            assertEquals("ext round trip $format", format, ImageFormatProbe.formatFromExtension("page.$ext"))
            assertEquals("upper ext $format", format, ImageFormatProbe.formatFromExtension("PAGE.${ext!!.uppercase()}"))
        }
    }

    @Test
    fun extensionsAndMimeTypesAreUnique() {
        val known = ImageFormat.entries.filter { it != ImageFormat.UNKNOWN }
        assertEquals(known.size, known.map { it.extension }.toSet().size)
        assertEquals(known.size, known.map { it.mimeType }.toSet().size)
    }

    @Test
    fun everyFormatSurvivesTruncationAndAnExtensionlessOrWrongName() {
        for ((format, data) in samples()) {
            for (n in 0..data.size) {
                ImageFormatProbe.probe(data, n, truncated = true)
            }
            // content decides, never the name
            assertEquals(format, ImageFormatProbe.sniff(data))
        }
        assertEquals(ImageFormat.UNKNOWN, ImageFormatProbe.formatFromExtension("page"))
        assertEquals(ImageFormat.UNKNOWN, ImageFormatProbe.formatFromExtension("page.txt"))
    }
}

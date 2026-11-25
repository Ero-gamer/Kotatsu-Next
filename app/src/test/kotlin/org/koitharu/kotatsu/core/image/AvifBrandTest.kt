package org.koitharu.kotatsu.core.image

import org.junit.Assert.assertEquals
import org.junit.Test

class AvifBrandTest {

    private fun ftyp(major: String, vararg compat: String): ByteArray {
        val size = 16 + compat.size * 4
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, size.toByte()))
        out.write("ftyp".toByteArray())
        out.write(major.toByteArray())
        out.write(byteArrayOf(0, 0, 0, 0))
        compat.forEach { out.write(it.toByteArray()) }
        return out.toByteArray()
    }

    private fun format(bytes: ByteArray) = ImageFormatProbe.probe(bytes, bytes.size, truncated = true).format

    @Test
    fun avifMajorBrand() {
        assertEquals(ImageFormat.AVIF, format(ftyp("avif", "mif1", "miaf")))
    }

    @Test
    fun avifCompatibleBrand() {
        assertEquals(ImageFormat.AVIF, format(ftyp("mif1", "avif", "miaf")))
    }

    @Test
    fun genericHeifBrandsWithoutHeicAreAvif() {
        assertEquals(ImageFormat.AVIF, format(ftyp("mif1", "miaf")))
    }

    @Test
    fun heicIsNotAvif() {
        assertEquals(ImageFormat.UNKNOWN, format(ftyp("heic", "mif1", "heic")))
        assertEquals(ImageFormat.UNKNOWN, format(ftyp("mif1", "mif1", "hevc")))
    }
}

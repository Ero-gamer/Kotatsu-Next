package org.koitharu.kotatsu.core.image

import java.util.Locale

/**
 * Builds the "raw details" text (hex dump, container structure, EXIF) from the first bytes of an image.
 * Pure function of [head]; never decodes pixels.
 */
object RawImageDetails {

    const val HEAD_SIZE = 64 * 1024
    private const val MAX_LINES = 400

    fun build(head: ByteArray, length: Int, format: ImageFormat): String {
        val out = StringBuilder(2048)
        val n = minOf(length, head.size)
        out.append("Header (first ").append(minOf(n, 64)).append(" bytes)\n")
        hexDump(head, minOf(n, 64), out)
        out.append("\nStructure\n")
        val r = R(head, n)
        val lines = ArrayList<String>()
        when (format) {
            ImageFormat.JPEG -> jpeg(r, lines)
            ImageFormat.PNG -> png(r, lines)
            ImageFormat.WEBP -> webp(r, lines)
            ImageFormat.GIF -> gif(r, lines)
            ImageFormat.AVIF -> boxes(r, 0, n, 0, lines)
            ImageFormat.JXL -> jxl(r, lines)
            ImageFormat.BMP -> lines.add("BMP DIB header size ${r.u32le(14)}")
            ImageFormat.UNKNOWN -> lines.add("Unknown format")
        }
        for (i in 0 until minOf(lines.size, MAX_LINES)) out.append(lines[i]).append('\n')
        if (lines.size > MAX_LINES) out.append("… ").append(lines.size - MAX_LINES).append(" more\n")
        return out.toString().trimEnd()
    }

    private fun hexDump(b: ByteArray, n: Int, out: StringBuilder) {
        var i = 0
        while (i < n) {
            out.append(String.format(Locale.ROOT, "%04X  ", i))
            val end = minOf(i + 16, n)
            for (j in i until i + 16) {
                if (j < end) out.append(String.format(Locale.ROOT, "%02X ", b[j].toInt() and 0xFF)) else out.append("   ")
            }
            out.append(' ')
            for (j in i until end) {
                val c = b[j].toInt() and 0xFF
                out.append(if (c in 32..126) c.toChar() else '.')
            }
            out.append('\n')
            i += 16
        }
    }

    private class R(val b: ByteArray, val n: Int) {
        fun u8(p: Int) = if (p in 0 until n) b[p].toInt() and 0xFF else -1
        fun u16be(p: Int) = if (p + 1 < n && p >= 0) (u8(p) shl 8) or u8(p + 1) else -1
        fun u16le(p: Int) = if (p + 1 < n && p >= 0) (u8(p + 1) shl 8) or u8(p) else -1
        fun u32be(p: Int): Long = if (p + 3 < n && p >= 0) (u16be(p).toLong() shl 16) or u16be(p + 2).toLong() else -1L
        fun u32le(p: Int): Long = if (p + 3 < n && p >= 0) (u16le(p + 2).toLong() shl 16) or u16le(p).toLong() else -1L
        fun str(p: Int, len: Int): String {
            val sb = StringBuilder(len)
            for (i in p until minOf(p + len, n)) {
                val c = b[i].toInt() and 0xFF
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            return sb.toString()
        }
    }

    // ---- JPEG ----------------------------------------------------------------------------------

    private fun jpeg(r: R, out: MutableList<String>) {
        var pos = 2
        var guard = 0
        while (guard++ < 200) {
            if (r.u8(pos) != 0xFF) break
            var m = r.u8(pos + 1)
            var p = pos + 1
            while (m == 0xFF) m = r.u8(++p)
            if (m < 0) break
            if (m == 0xD9) {
                out.add("EOI")
                break
            }
            if (m == 0x01 || m in 0xD0..0xD8) {
                pos = p + 1
                continue
            }
            val len = r.u16be(p + 1)
            if (len < 2) break
            val name = jpegMarker(m)
            when {
                m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC -> {
                    val comps = r.u8(p + 8)
                    val sb = StringBuilder()
                    for (c in 0 until comps.coerceIn(0, 4)) {
                        val s = r.u8(p + 9 + c * 3 + 1)
                        sb.append(" c").append(r.u8(p + 9 + c * 3)).append('=').append(s shr 4).append('x').append(s and 15)
                    }
                    out.add("$name len=$len precision=${r.u8(p + 3)} ${r.u16be(p + 6)}x${r.u16be(p + 4)} comps=$comps$sb")
                }

                m == 0xE0 -> out.add("$name len=$len ${r.str(p + 3, 5)} v${r.u8(p + 8)}.${r.u8(p + 9)}")

                m == 0xE1 && r.str(p + 3, 4) == "Exif" -> {
                    out.add("$name len=$len Exif")
                    exif(r, p + 9, p + 1 + len, out)
                }

                m == 0xE1 -> out.add("$name len=$len ${r.str(p + 3, 28)}")

                m == 0xE2 -> out.add("$name len=$len ${r.str(p + 3, 12)}")

                m == 0xEE -> out.add("$name len=$len ${r.str(p + 3, 5)} transform=${r.u8(p + 14)}")

                m == 0xFE -> out.add("$name len=$len \"${r.str(p + 3, minOf(len - 2, 60))}\"")

                m == 0xDD -> out.add("$name len=$len restart interval=${r.u16be(p + 3)}")

                else -> out.add("$name len=$len")
            }
            if (m == 0xDA) {
                out.add("(entropy-coded data follows)")
                break
            }
            pos = p + 1 + len
        }
    }

    private fun jpegMarker(m: Int): String = when (m) {
        0xC0 -> "SOF0 baseline"
        0xC1 -> "SOF1 extended"
        0xC2 -> "SOF2 progressive"
        0xC3 -> "SOF3 lossless"
        0xC4 -> "DHT"
        0xC9, 0xCA, 0xCB -> "SOF arithmetic"
        0xDA -> "SOS"
        0xDB -> "DQT"
        0xDD -> "DRI"
        in 0xE0..0xEF -> "APP${m - 0xE0}"
        0xFE -> "COM"
        else -> "FF%02X".format(m)
    }

    private val EXIF_TAGS = mapOf(
        0x010E to "ImageDescription", 0x010F to "Make", 0x0110 to "Model", 0x0112 to "Orientation",
        0x011A to "XResolution", 0x011B to "YResolution", 0x0128 to "ResolutionUnit", 0x0131 to "Software",
        0x0132 to "DateTime", 0x013B to "Artist", 0x0213 to "YCbCrPositioning", 0x8298 to "Copyright",
        0x829A to "ExposureTime", 0x829D to "FNumber", 0x8827 to "ISO", 0x9003 to "DateTimeOriginal",
        0x9004 to "DateTimeDigitized", 0xA001 to "ColorSpace", 0xA002 to "PixelXDimension",
        0xA003 to "PixelYDimension", 0x8769 to "ExifIFD", 0x8825 to "GPSIFD",
    )

    private fun exif(r: R, tiff: Int, end: Int, out: MutableList<String>) {
        val le = r.str(tiff, 2) == "II"
        if (!le && r.str(tiff, 2) != "MM") return
        fun r16(p: Int) = if (le) r.u16le(p) else r.u16be(p)
        fun r32(p: Int) = if (le) r.u32le(p) else r.u32be(p)
        fun ifd(off: Long, label: String, depth: Int) {
            if (off < 8 || tiff + off + 2 > end || depth > 2) return
            val count = r16(tiff + off.toInt()).coerceIn(0, 100)
            for (i in 0 until count) {
                val e = tiff + off.toInt() + 2 + i * 12
                if (e + 12 > end) break
                val tag = r16(e)
                val type = r16(e + 2)
                val cnt = r32(e + 4)
                val name = EXIF_TAGS[tag] ?: "0x%04X".format(tag)
                val valueOff = if (type == 2 && cnt > 4) tiff + r32(e + 8).toInt() else e + 8
                val value = when (type) {
                    2 -> r.str(valueOff, minOf(cnt.toInt(), 64)).trimEnd('.', ' ')

                    3 -> r16(e + 8).toString()

                    4 -> r32(e + 8).toString()

                    5 -> {
                        val o = tiff + r32(e + 8).toInt()
                        "${r32(o)}/${r32(o + 4)}"
                    }

                    else -> "type=$type count=$cnt"
                }
                out.add("  [$label] $name = $value")
                if ((tag == 0x8769 || tag == 0x8825) && type == 4) {
                    ifd(r32(e + 8), if (tag == 0x8769) "Exif" else "GPS", depth + 1)
                }
            }
        }
        ifd(r32(tiff + 4), "IFD0", 0)
    }

    // ---- PNG -----------------------------------------------------------------------------------

    private fun png(r: R, out: MutableList<String>) {
        var pos = 8
        var guard = 0
        while (guard++ < 300) {
            val len = r.u32be(pos)
            if (len < 0) break
            val type = r.str(pos + 4, 4)
            val extra = when (type) {
                "IHDR" -> "${r.u32be(pos + 8)}x${r.u32be(pos + 12)} depth=${r.u8(pos + 16)} " +
                    "colorType=${r.u8(pos + 17)} interlace=${r.u8(pos + 20)}"

                "acTL" -> "frames=${r.u32be(pos + 8)} loops=${r.u32be(pos + 12)}"

                "fcTL" -> "seq=${r.u32be(pos + 8)} ${r.u32be(pos + 12)}x${r.u32be(pos + 16)} " +
                    "delay=${r.u16be(pos + 28)}/${r.u16be(pos + 30)}"

                "gAMA" -> "gamma=${r.u32be(pos + 8)}"

                "pHYs" -> "${r.u32be(pos + 8)}x${r.u32be(pos + 12)} unit=${r.u8(pos + 16)}"

                "tEXt" -> r.str(pos + 8, minOf(len.toInt(), 80))

                "iCCP" -> r.str(pos + 8, minOf(len.toInt(), 40)).substringBefore('.')

                else -> ""
            }
            out.add("$type len=$len $extra".trimEnd())
            if (type == "IDAT") {
                out.add("(image data follows)")
                break
            }
            if (type == "IEND") break
            pos += 12 + len.toInt()
        }
    }

    // ---- WebP / GIF ----------------------------------------------------------------------------

    private fun webp(r: R, out: MutableList<String>) {
        out.add("RIFF size=${r.u32le(4)}")
        var pos = 12
        var guard = 0
        while (guard++ < 300 && pos + 8 <= r.n) {
            val type = r.str(pos, 4)
            val size = r.u32le(pos + 4)
            if (size < 0) break
            val extra = when (type) {
                "VP8X" -> "flags=0x%02X canvas=%dx%d".format(r.u8(pos + 8), r.u16le(pos + 12) + 1, r.u16le(pos + 15) + 1)

                "ANIM" -> "bg=0x%08X loops=%d".format(r.u32le(pos + 8), r.u16le(pos + 12))

                "ANMF" -> "x=${r.u16le(pos + 8) * 2} y=${r.u16le(pos + 11) * 2} duration=${
                    r.u16le(pos + 20) or (r.u8(pos + 22) shl 16)
                }ms"

                "VP8 ", "VP8L" -> ""

                else -> ""
            }
            out.add("$type size=$size $extra".trimEnd())
            pos += 8 + size.toInt() + (size.toInt() and 1)
        }
    }

    private fun gif(r: R, out: MutableList<String>) {
        val packed = r.u8(10)
        out.add("${r.str(0, 6)} screen=${r.u16le(6)}x${r.u16le(8)} globalPalette=${packed and 0x80 != 0} bgIndex=${r.u8(11)}")
        var pos = 13
        if (packed and 0x80 != 0) pos += 3 * (1 shl ((packed and 7) + 1))
        var frames = 0
        var guard = 0
        while (guard++ < 2000 && pos < r.n) {
            when (r.u8(pos)) {
                0x2C -> {
                    frames++
                    val ip = r.u8(pos + 9)
                    if (frames <= 8) {
                        out.add("Frame $frames ${r.u16le(pos + 5)}x${r.u16le(pos + 7)} at ${r.u16le(pos + 1)},${r.u16le(pos + 3)}")
                    }
                    pos += 10
                    if (ip >= 0 && ip and 0x80 != 0) pos += 3 * (1 shl ((ip and 7) + 1))
                    pos++
                    pos = skip(r, pos)
                }

                0x21 -> {
                    val label = r.u8(pos + 1)
                    if (label == 0xF9 && frames < 8) {
                        out.add("GCE delay=${r.u16le(pos + 4) * 10}ms disposal=${(r.u8(pos + 3) shr 2) and 7} transparent=${r.u8(pos + 3) and 1}")
                    } else if (label == 0xFF) {
                        out.add("App ext ${r.str(pos + 3, 11)}")
                    } else if (label == 0xFE) {
                        out.add("Comment ext")
                    }
                    pos = skip(r, pos + 2)
                }

                else -> break
            }
            if (pos < 0) break
        }
        out.add("(frames seen in first 64 KB: $frames)")
    }

    private fun skip(r: R, start: Int): Int {
        var pos = start
        var guard = 0
        while (guard++ < 100_000) {
            val n = r.u8(pos)
            if (n < 0) return -1
            pos += 1 + n
            if (n == 0) return pos
        }
        return -1
    }

    // ---- ISO BMFF (AVIF) / JXL -----------------------------------------------------------------

    private fun boxes(r: R, start: Int, end: Int, depth: Int, out: MutableList<String>) {
        var pos = start
        var guard = 0
        while (guard++ < 200 && pos + 8 <= end) {
            var size = r.u32be(pos)
            var header = 8
            if (size == 1L) {
                size = (r.u32be(pos + 8) shl 32) or r.u32be(pos + 12)
                header = 16
            } else if (size == 0L) {
                size = (end - pos).toLong()
            }
            if (size < header) break
            val type = r.str(pos + 4, 4)
            val pad = "  ".repeat(depth)
            val extra = when (type) {
                "ftyp" -> "major=${r.str(pos + 8, 4)} compat=" + (16 until minOf(size.toInt(), 64) step 4).joinToString(",") { r.str(pos + it, 4) }

                "ispe" -> "${r.u32be(pos + 12)}x${r.u32be(pos + 16)}"

                "pixi" -> "channels=${r.u8(pos + 12)} depth=${r.u8(pos + 13)}"

                "colr" -> r.str(pos + 8, 4) + if (r.str(pos + 8, 4) == "nclx") {
                    " primaries=${r.u16be(pos + 12)} transfer=${r.u16be(pos + 14)} matrix=${r.u16be(pos + 16)} fullRange=${r.u8(pos + 18) shr 7}"
                } else {
                    ""
                }

                "irot" -> "angle=${r.u8(pos + 8) and 3}*90"

                "imir" -> "axis=${r.u8(pos + 8) and 1}"

                "mvhd" -> "timescale=${r.u32be(pos + if (r.u8(pos + 8) == 1) 28 else 20)}"

                else -> ""
            }
            out.add("$pad$type size=$size $extra".trimEnd())
            val end2 = minOf((pos + size).toInt(), end)
            when (type) {
                "meta" -> boxes(r, pos + header + 4, end2, depth + 1, out)
                "iprp", "ipco", "moov", "trak", "mdia", "minf", "stbl" -> boxes(r, pos + header, end2, depth + 1, out)
            }
            pos = (pos + size).toInt()
            if (pos < 0) break
        }
    }

    private fun jxl(r: R, out: MutableList<String>) {
        if (r.u8(0) == 0xFF) {
            out.add("Bare codestream (FF 0A)")
            return
        }
        out.add("ISOBMFF container")
        var pos = 12
        var guard = 0
        while (guard++ < 64 && pos + 8 <= r.n) {
            var size = r.u32be(pos)
            var header = 8
            if (size == 1L) {
                size = (r.u32be(pos + 8) shl 32) or r.u32be(pos + 12)
                header = 16
            }
            if (size < header) break
            out.add("${r.str(pos + 4, 4)} size=$size")
            pos += size.toInt()
        }
    }
}

package org.koitharu.kotatsu.core.image

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

enum class ImageFormat(val mimeType: String?, val extension: String?) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    GIF("image/gif", "gif"),
    AVIF("image/avif", "avif"),
    JXL("image/jxl", "jxl"),
    BMP("image/bmp", "bmp"),
    UNKNOWN(null, null),
}

/**
 * Header-level facts about an image file. Nothing here needs a pixel decode.
 * Unknown numeric values are 0 (sizes, depth, orientation) or -1 (frame count, duration, loops).
 */
data class ImageInfo(
    val format: ImageFormat,
    val width: Int = 0,
    val height: Int = 0,
    val animated: Boolean = false,
    val frameCount: Int = -1,
    val durationMs: Long = -1L,
    /** 0 = loop forever, -1 = unknown. */
    val loopCount: Int = -1,
    val bitDepth: Int = 0,
    val hasAlpha: Boolean? = null,
    val progressive: Boolean = false,
    /** EXIF orientation 1..8, or 0 when absent. */
    val orientation: Int = 0,
    val components: Int = 0,
    val fileSize: Long = 0L,
) {
    val isKnown: Boolean get() = format != ImageFormat.UNKNOWN

    companion object {
        val UNKNOWN = ImageInfo(ImageFormat.UNKNOWN)
    }
}

/** Random-access read abstraction so a raw file, a byte array and a STORED zip entry share one parser. */
interface ByteSource {
    val length: Long

    /** Reads up to [len] bytes at [pos] into [buf]; returns the count, or -1 at/after the end. */
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
}

class ArrayByteSource(private val data: ByteArray, private val size: Int = data.size) : ByteSource {
    override val length: Long get() = size.toLong()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos < 0 || pos >= size) return -1
        val n = minOf(len.toLong(), size - pos).toInt()
        System.arraycopy(data, pos.toInt(), buf, off, n)
        return n
    }
}

/** A window [offset, offset + length) of an open file, e.g. a STORED entry inside a CBZ. */
class FileRangeByteSource(
    private val raf: RandomAccessFile,
    private val offset: Long,
    override val length: Long,
) : ByteSource {

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (pos < 0 || pos >= length) return -1
        val n = minOf(len.toLong(), length - pos).toInt()
        raf.seek(offset + pos)
        return raf.read(buf, off, n)
    }
}

/**
 * Content-based image format / animation probe (JPEG, PNG/APNG, WebP, GIF, AVIF, JXL, BMP).
 * The real format always comes from the header, never from the file name.
 * Results for files are cached per (path, size, mtime).
 */
object ImageFormatProbe {

    private const val CACHE_SIZE = 768
    private const val WINDOW = 8192
    private const val MAX_STEPS = 400_000

    private val cache = object : LinkedHashMap<String, ImageInfo>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageInfo>?) = size > CACHE_SIZE
    }

    /**
     * [truncated] says [bytes] is only the head of a larger file: a GIF whose data runs out after its
     * first frame is then reported as animated (a lone huge first frame is harmless to play).
     */
    fun probe(bytes: ByteArray, length: Int = bytes.size, deep: Boolean = false, truncated: Boolean = false): ImageInfo = probe(ArrayByteSource(bytes, length), deep, truncated)

    /** Cached probe of a whole file. [deep] also counts frames / duration of animations. */
    fun probe(file: File, deep: Boolean = false): ImageInfo {
        val key = "${file.path}|${file.length()}|${file.lastModified()}"
        return cached(key, deep) {
            try {
                RandomAccessFile(file, "r").use { probe(FileRangeByteSource(it, 0, it.length()), deep) }
            } catch (e: IOException) {
                ImageInfo.UNKNOWN
            }
        }
    }

    /** Cached probe of a byte range (a STORED zip entry) inside [file]. */
    fun probeRange(file: File, offset: Long, length: Long, name: String, deep: Boolean = false): ImageInfo {
        val key = "${file.path}|${file.length()}|${file.lastModified()}!$name|$offset"
        return cached(key, deep) {
            try {
                RandomAccessFile(file, "r").use { probe(FileRangeByteSource(it, offset, length), deep) }
            } catch (e: IOException) {
                ImageInfo.UNKNOWN
            }
        }
    }

    fun probe(source: ByteSource, deep: Boolean = false, truncated: Boolean = false): ImageInfo = try {
        Parser(source, deep, truncated).parse().let {
            if (it.isKnown && !it.animated && it.frameCount < 0) it.copy(frameCount = 1) else it
        }.copy(fileSize = source.length)
    } catch (e: IOException) {
        ImageInfo.UNKNOWN
    } catch (e: IndexOutOfBoundsException) {
        ImageInfo.UNKNOWN
    }

    /** Cheap format-only sniff (first 32 bytes). */
    fun sniff(head: ByteArray, length: Int = head.size): ImageFormat = Parser(ArrayByteSource(head, length), false, false).detect()

    fun formatFromExtension(name: String): ImageFormat {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "jpe", "jfif" -> ImageFormat.JPEG
            "png", "apng" -> ImageFormat.PNG
            "webp" -> ImageFormat.WEBP
            "gif" -> ImageFormat.GIF
            "avif" -> ImageFormat.AVIF
            "jxl" -> ImageFormat.JXL
            "bmp" -> ImageFormat.BMP
            else -> ImageFormat.UNKNOWN
        }
    }

    private inline fun cached(key: String, deep: Boolean, load: () -> ImageInfo): ImageInfo {
        synchronized(cache) {
            val hit = cache[key]
            if (hit != null && !(deep && hit.animated && hit.frameCount < 0 && hit.format != ImageFormat.JXL)) {
                return hit
            }
        }
        val info = load()
        synchronized(cache) { cache[key] = info }
        return info
    }

    fun clearCache() = synchronized(cache) { cache.clear() }

    private class Parser(private val src: ByteSource, private val deep: Boolean, private val truncated: Boolean) {
        private val buf = ByteArray(WINDOW)
        private var winStart = -1L
        private var winLen = 0
        private val len = src.length

        private fun u8(pos: Long): Int {
            if (pos < 0 || pos >= len) return -1
            if (pos < winStart || pos >= winStart + winLen) {
                val n = src.read(pos, buf, 0, buf.size)
                if (n <= 0) {
                    winStart = -1L
                    winLen = 0
                    return -1
                }
                winStart = pos
                winLen = n
            }
            return buf[(pos - winStart).toInt()].toInt() and 0xFF
        }

        private fun u16be(p: Long): Int {
            val a = u8(p)
            val b = u8(p + 1)
            return if (a < 0 || b < 0) -1 else (a shl 8) or b
        }

        private fun u16le(p: Long): Int {
            val a = u8(p)
            val b = u8(p + 1)
            return if (a < 0 || b < 0) -1 else (b shl 8) or a
        }

        private fun u24le(p: Long): Int {
            val a = u8(p)
            val b = u8(p + 1)
            val c = u8(p + 2)
            return if (a < 0 || b < 0 || c < 0) -1 else (c shl 16) or (b shl 8) or a
        }

        private fun u32be(p: Long): Long {
            val a = u16be(p)
            val b = u16be(p + 2)
            return if (a < 0 || b < 0) -1L else (a.toLong() shl 16) or b.toLong()
        }

        private fun u32le(p: Long): Long {
            val a = u16le(p)
            val b = u16le(p + 2)
            return if (a < 0 || b < 0) -1L else (b.toLong() shl 16) or a.toLong()
        }

        private fun has(p: Long, s: String): Boolean {
            for (i in s.indices) if (u8(p + i) != s[i].code) return false
            return true
        }

        fun detect(): ImageFormat = when {
            u8(0) == 0xFF && u8(1) == 0xD8 && u8(2) == 0xFF -> ImageFormat.JPEG

            u8(0) == 0x89 && has(1, "PNG") && u8(4) == 0x0D && u8(5) == 0x0A && u8(6) == 0x1A && u8(7) == 0x0A -> {
                ImageFormat.PNG
            }

            has(0, "GIF87a") || has(0, "GIF89a") -> ImageFormat.GIF

            has(0, "RIFF") && has(8, "WEBP") -> ImageFormat.WEBP

            u8(0) == 0xFF && u8(1) == 0x0A -> ImageFormat.JXL

            u32be(0) == 0x0000000CL && has(4, "JXL ") && u32be(8) == 0x0D0A870AL -> ImageFormat.JXL

            has(4, "ftyp") && isAvifBrand() -> ImageFormat.AVIF

            has(0, "BM") && u32le(14) >= 12L -> ImageFormat.BMP

            else -> ImageFormat.UNKNOWN
        }

        fun parse(): ImageInfo = when (detect()) {
            ImageFormat.JPEG -> jpeg()
            ImageFormat.PNG -> png()
            ImageFormat.GIF -> gif()
            ImageFormat.WEBP -> webp()
            ImageFormat.AVIF -> avif()
            ImageFormat.JXL -> jxl()
            ImageFormat.BMP -> bmp()
            ImageFormat.UNKNOWN -> ImageInfo.UNKNOWN
        }

        // ---- JPEG ----------------------------------------------------------------------------

        private fun jpeg(): ImageInfo {
            var pos = 2L
            var orientation = 0
            var steps = 0
            while (steps++ < 512) {
                if (u8(pos) != 0xFF) return ImageInfo(ImageFormat.JPEG, orientation = orientation)
                var m = u8(pos + 1)
                var p = pos + 1
                while (m == 0xFF) {
                    p++
                    m = u8(p)
                }
                if (m < 0 || m == 0xD9 || m == 0xDA) break
                if (m == 0x01 || m in 0xD0..0xD7 || m == 0xD8) {
                    pos = p + 1
                    continue
                }
                val segLen = u16be(p + 1)
                if (segLen < 2) break
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    val h = u16be(p + 4)
                    val w = u16be(p + 6)
                    return ImageInfo(
                        format = ImageFormat.JPEG,
                        width = w.coerceAtLeast(0),
                        height = h.coerceAtLeast(0),
                        bitDepth = u8(p + 3).coerceAtLeast(0),
                        components = u8(p + 8).coerceAtLeast(0),
                        progressive = m == 0xC2 || m == 0xC6 || m == 0xCA || m == 0xCE,
                        hasAlpha = false,
                        orientation = orientation,
                    )
                }
                if (m == 0xE1 && orientation == 0 && has(p + 3, "Exif") && u8(p + 7) == 0) {
                    orientation = exifOrientation(p + 9, p + 1 + segLen)
                }
                pos = p + 1 + segLen
            }
            return ImageInfo(ImageFormat.JPEG, orientation = orientation)
        }

        private fun exifOrientation(tiff: Long, end: Long): Int {
            val little = when {
                has(tiff, "II") -> true
                has(tiff, "MM") -> false
                else -> return 0
            }
            fun r16(p: Long) = if (little) u16le(p) else u16be(p)
            fun r32(p: Long) = if (little) u32le(p) else u32be(p)
            val ifd = r32(tiff + 4)
            if (ifd < 8 || tiff + ifd + 2 > end) return 0
            val count = r16(tiff + ifd).coerceIn(0, 128)
            for (i in 0 until count) {
                val e = tiff + ifd + 2 + i * 12L
                if (e + 12 > end) break
                if (r16(e) == 0x0112 && r16(e + 2) == 3) {
                    val v = r16(e + 8)
                    return if (v in 1..8) v else 0
                }
            }
            return 0
        }

        // ---- PNG / APNG ----------------------------------------------------------------------

        private fun png(): ImageInfo {
            val w = u32be(16).toInt()
            val h = u32be(20).toInt()
            val depth = u8(24)
            val colorType = u8(25)
            var alpha: Boolean? = colorType == 4 || colorType == 6
            var frames = -1
            var loops = -1
            var pos = 8L
            var steps = 0
            while (steps++ < 256) {
                val clen = u32be(pos)
                if (clen < 0) break
                val type = u32be(pos + 4)
                when (type) {
                    CHUNK_ACTL -> {
                        frames = u32be(pos + 8).toInt()
                        loops = u32be(pos + 12).toInt()
                    }

                    CHUNK_TRNS -> alpha = true

                    CHUNK_IDAT, CHUNK_IEND -> break
                }
                pos += 12 + clen
            }
            val animated = frames > 1
            return ImageInfo(
                format = ImageFormat.PNG,
                width = w,
                height = h,
                animated = animated,
                frameCount = if (animated) frames else 1,
                loopCount = if (animated) loops else -1,
                bitDepth = depth.coerceAtLeast(0),
                hasAlpha = alpha,
                components = when (colorType) {
                    0 -> 1
                    2 -> 3
                    3 -> 1
                    4 -> 2
                    6 -> 4
                    else -> 0
                },
            )
        }

        // ---- GIF -----------------------------------------------------------------------------

        private fun gif(): ImageInfo {
            val w = u16le(6)
            val h = u16le(8)
            val packed = u8(10)
            var pos = 13L
            if (packed >= 0 && packed and 0x80 != 0) pos += 3L * (1 shl ((packed and 7) + 1))
            var frames = 0
            var delayCs = 0L
            var loops = -1
            var transparent = false
            var steps = 0
            var hitEof = false
            while (steps++ < MAX_STEPS) {
                when (u8(pos)) {
                    -1 -> {
                        hitEof = true
                        break
                    }

                    0x2C -> {
                        frames++
                        val ip = u8(pos + 9)
                        if (ip < 0) {
                            hitEof = true
                            break
                        }
                        pos += 10
                        if (ip and 0x80 != 0) pos += 3L * (1 shl ((ip and 7) + 1))
                        pos++ // LZW minimum code size
                        pos = skipSubBlocks(pos)
                        if (pos < 0) {
                            hitEof = true
                            break
                        }
                        if (!deep && frames >= 2) break
                    }

                    0x21 -> {
                        val label = u8(pos + 1)
                        if (label == 0xF9 && u8(pos + 2) == 4) {
                            delayCs += u16le(pos + 4).coerceAtLeast(0)
                            if (u8(pos + 3) and 1 != 0) transparent = true
                        } else if (label == 0xFF && u8(pos + 2) == 11 && has(pos + 3, "NETSCAPE2.0") &&
                            u8(pos + 14) == 3 && u8(pos + 15) == 1
                        ) {
                            loops = u16le(pos + 16).coerceAtLeast(0)
                        }
                        pos = skipSubBlocks(pos + 2)
                        if (pos < 0) {
                            hitEof = true
                            break
                        }
                    }

                    else -> break // 0x3B trailer, EOF or garbage
                }
            }
            val animated = frames > 1 || (truncated && hitEof && frames >= 1)
            val complete = (deep || !animated) && !truncated
            return ImageInfo(
                format = ImageFormat.GIF,
                width = w.coerceAtLeast(0),
                height = h.coerceAtLeast(0),
                animated = animated,
                frameCount = if (complete && frames > 0) frames else -1,
                durationMs = if (deep && animated) delayCs * 10 else -1L,
                loopCount = if (animated) (if (loops >= 0) loops else 1) else -1,
                bitDepth = 8,
                hasAlpha = if (transparent) true else null,
                components = 3,
            )
        }

        /** [pos] points at the first sub-block length byte; returns the position after the 0 terminator or -1. */
        private fun skipSubBlocks(start: Long): Long {
            var pos = start
            var steps = 0
            while (steps++ < MAX_STEPS) {
                val n = u8(pos)
                if (n < 0) return -1
                pos += 1 + n
                if (n == 0) return pos
            }
            return -1
        }

        // ---- WebP ----------------------------------------------------------------------------

        private fun webp(): ImageInfo = when {
            has(12, "VP8 ") -> ImageInfo(
                format = ImageFormat.WEBP,
                width = (u16le(26) and 0x3FFF).coerceAtLeast(0),
                height = (u16le(28) and 0x3FFF).coerceAtLeast(0),
                bitDepth = 8,
                hasAlpha = false,
                components = 3,
            )

            has(12, "VP8L") -> {
                val v = u32le(21)
                ImageInfo(
                    format = ImageFormat.WEBP,
                    width = if (v < 0) 0 else ((v and 0x3FFF) + 1).toInt(),
                    height = if (v < 0) 0 else (((v shr 14) and 0x3FFF) + 1).toInt(),
                    bitDepth = 8,
                    hasAlpha = if (v < 0) null else (v shr 28) and 1L == 1L,
                    components = 4,
                )
            }

            has(12, "VP8X") -> webpExtended()

            else -> ImageInfo(ImageFormat.WEBP)
        }

        private fun webpExtended(): ImageInfo {
            val flags = u8(20)
            val w = u24le(24)
            val h = u24le(27)
            val animatedFlag = flags >= 0 && flags and 0x02 != 0
            var frames = 0
            var durationMs = 0L
            var loops = -1
            if (animatedFlag) {
                var pos = 30L
                var steps = 0
                while (steps++ < MAX_STEPS && pos + 8 <= len) {
                    val size = u32le(pos + 4)
                    if (size < 0) break
                    when {
                        has(pos, "ANIM") -> loops = u16le(pos + 12).coerceAtLeast(0)

                        has(pos, "ANMF") -> {
                            frames++
                            durationMs += u24le(pos + 8 + 12).coerceAtLeast(0)
                            if (!deep && frames >= 2) break
                        }
                    }
                    pos += 8 + size + (size and 1)
                }
            }
            val animated = animatedFlag && frames != 1
            return ImageInfo(
                format = ImageFormat.WEBP,
                width = if (w < 0) 0 else w + 1,
                height = if (h < 0) 0 else h + 1,
                animated = animated,
                frameCount = if (animated && deep) {
                    frames
                } else if (!animated) {
                    1
                } else {
                    -1
                },
                durationMs = if (animated && deep) durationMs else -1L,
                loopCount = if (animated) loops else -1,
                bitDepth = 8,
                hasAlpha = if (flags < 0) null else flags and 0x10 != 0,
                components = 4,
            )
        }

        // ---- AVIF (ISO BMFF) -----------------------------------------------------------------

        private fun isAvifBrand(): Boolean {
            val size = u32be(0)
            if (size < 16) return false
            if (has(8, "avif") || has(8, "avis")) return true
            var p = 16L
            val end = minOf(size, 256L)
            while (p + 4 <= end) {
                if (has(p, "avif") || has(p, "avis")) return true
                p += 4
            }
            return false
        }

        private class AvifState {
            var maxArea = 0L
            var w = 0
            var h = 0
            var depth = 0
            var alpha: Boolean? = null
            var channels = 0
            var mono = false
            var samples = -1
            var timescale = 0L
            var duration = 0L
        }

        private fun avif(): ImageInfo {
            val st = AvifState()
            val animated = has(8, "avis") || compatBrand("avis")
            walkBoxes(0, len, 0, st)
            return ImageInfo(
                format = ImageFormat.AVIF,
                width = st.w,
                height = st.h,
                animated = animated,
                frameCount = if (animated) st.samples else 1,
                durationMs = if (animated && st.timescale > 0) st.duration * 1000 / st.timescale else -1L,
                loopCount = if (animated) 0 else -1,
                bitDepth = st.depth,
                hasAlpha = st.alpha ?: false,
                components = st.channels,
            )
        }

        private fun compatBrand(brand: String): Boolean {
            val size = u32be(0)
            var p = 16L
            val end = minOf(size, 256L)
            while (p + 4 <= end) {
                if (has(p, brand)) return true
                p += 4
            }
            return false
        }

        private fun walkBoxes(start: Long, end: Long, depth: Int, st: AvifState) {
            var pos = start
            var steps = 0
            while (steps++ < 4096 && pos + 8 <= end && depth < 8) {
                var size = u32be(pos)
                var header = 8L
                if (size == 1L) {
                    val hi = u32be(pos + 8)
                    val lo = u32be(pos + 12)
                    if (hi < 0 || lo < 0) return
                    size = (hi shl 32) or lo
                    header = 16L
                } else if (size == 0L) {
                    size = end - pos
                }
                if (size < header || pos + size > end) size = end - pos
                if (size < header) return
                val body = pos + header
                val boxEnd = pos + size
                when {
                    has(pos + 4, "meta") -> walkBoxes(body + 4, boxEnd, depth + 1, st)

                    has(pos + 4, "iprp") || has(pos + 4, "ipco") || has(pos + 4, "moov") ||
                        has(pos + 4, "trak") || has(pos + 4, "mdia") || has(pos + 4, "minf") ||
                        has(pos + 4, "stbl") -> walkBoxes(body, boxEnd, depth + 1, st)

                    has(pos + 4, "ispe") -> {
                        val w = u32be(body + 4)
                        val h = u32be(body + 8)
                        if (w > 0 && h > 0 && w * h > st.maxArea) {
                            st.maxArea = w * h
                            st.w = w.toInt()
                            st.h = h.toInt()
                        }
                    }

                    has(pos + 4, "pixi") && st.depth == 0 -> {
                        val ch = u8(body + 4)
                        if (ch > 0) {
                            st.channels = ch
                            st.depth = u8(body + 5).coerceAtLeast(0)
                        }
                    }

                    has(pos + 4, "av1C") && st.depth == 0 -> {
                        val b = u8(body + 2)
                        if (b >= 0) {
                            val high = b and 0x40 != 0
                            val twelve = b and 0x20 != 0
                            st.depth = if (high && twelve) {
                                12
                            } else if (high) {
                                10
                            } else {
                                8
                            }
                        }
                    }

                    has(pos + 4, "auxC") -> if (hasAscii(body + 4, boxEnd, "alpha")) st.alpha = true

                    has(pos + 4, "mvhd") -> {
                        if (u8(body) == 1) {
                            st.timescale = u32be(body + 20)
                            st.duration = (u32be(body + 24) shl 32) or u32be(body + 28)
                        } else {
                            st.timescale = u32be(body + 12)
                            st.duration = u32be(body + 16)
                        }
                    }

                    has(pos + 4, "stsz") && st.samples < 0 -> {
                        val fixed = u32be(body + 4)
                        val count = u32be(body + 8)
                        if (count >= 0 && fixed >= 0) st.samples = count.toInt()
                    }
                }
                pos = boxEnd
            }
        }

        private fun hasAscii(from: Long, to: Long, s: String): Boolean {
            var p = from
            val last = minOf(to - s.length, from + 96)
            while (p <= last) {
                if (has(p, s)) return true
                p++
            }
            return false
        }

        // ---- JPEG XL -------------------------------------------------------------------------

        private fun jxl(): ImageInfo {
            var start = 0L
            if (u8(0) != 0xFF) { // container: find first codestream box
                var pos = 12L
                var found = false
                var steps = 0
                while (steps++ < 64 && pos + 8 <= len) {
                    var size = u32be(pos)
                    var header = 8L
                    if (size == 1L) {
                        size = (u32be(pos + 8) shl 32) or u32be(pos + 12)
                        header = 16L
                    } else if (size == 0L) {
                        size = len - pos
                    }
                    if (size < header) break
                    if (has(pos + 4, "jxlc")) {
                        start = pos + header
                        found = true
                        break
                    }
                    if (has(pos + 4, "jxlp")) {
                        start = pos + header + 4
                        found = true
                        break
                    }
                    pos += size
                }
                if (!found) return ImageInfo(ImageFormat.JXL)
            }
            val bits = Bits(start + 2) // skip FF 0A
            val size = jxlSize(bits) ?: return ImageInfo(ImageFormat.JXL)
            var orientation = 0
            var animated = false
            var depth = 8
            var loops = -1
            val allDefault = bits.read(1) == 1
            if (!allDefault) {
                val extra = bits.read(1) == 1
                var ok = true
                if (extra) {
                    orientation = bits.read(3) + 1
                    if (bits.read(1) == 1) ok = jxlSize(bits) != null // intrinsic size
                    if (ok && bits.read(1) == 1) ok = false // preview header: not parsed
                    if (ok) {
                        animated = bits.read(1) == 1
                        if (animated) {
                            u32(bits, D(0, 100), D(0, 1000), D(10, 1), D(30, 1))
                            u32(bits, D(0, 1), D(0, 1001), D(8, 1), D(10, 1))
                            loops = u32(bits, D(0, 0), D(3, 0), D(16, 0), D(32, 0))
                            bits.read(1) // have_timecodes
                        }
                    }
                }
                if (ok && !bits.failed) {
                    val floating = bits.read(1) == 1
                    depth = if (floating) {
                        u32(bits, D(0, 32), D(0, 16), D(0, 24), D(6, 1))
                    } else {
                        u32(bits, D(0, 8), D(0, 10), D(0, 12), D(6, 1))
                    }
                }
            }
            return ImageInfo(
                format = ImageFormat.JXL,
                width = size.first,
                height = size.second,
                animated = animated && !bits.failed,
                frameCount = if (animated) -1 else 1,
                loopCount = if (animated) loops else -1,
                bitDepth = if (depth in 1..64) depth else 0,
                orientation = orientation,
            )
        }

        /** JXL U32 field: [bits] extra bits (0 = fixed value) plus [off]. */
        private class D(val bits: Int, val off: Int)

        private fun u32(b: Bits, d0: D, d1: D, d2: D, d3: D): Int {
            val d = when (b.read(2)) {
                0 -> d0
                1 -> d1
                2 -> d2
                else -> d3
            }
            return if (d.bits == 0) d.off else b.read(d.bits) + d.off
        }

        private fun jxlSize(b: Bits): Pair<Int, Int>? {
            val small = b.read(1) == 1
            val h: Int
            if (small) {
                h = (b.read(5) + 1) * 8
            } else {
                h = jxlDim(b)
            }
            val ratio = b.read(3)
            val w = if (ratio == 0) {
                if (small) (b.read(5) + 1) * 8 else jxlDim(b)
            } else {
                val (n, d) = RATIOS[ratio - 1]
                (h.toLong() * n / d).toInt()
            }
            return if (b.failed || w <= 0 || h <= 0) null else w to h
        }

        private fun jxlDim(b: Bits): Int = when (b.read(2)) {
            0 -> b.read(9) + 1
            1 -> b.read(13) + 1
            2 -> b.read(18) + 1
            else -> b.read(30) + 1
        }

        /** LSB-first bit reader over the probe window. */
        private inner class Bits(private val base: Long) {
            private var bit = 0L
            var failed = false

            fun read(n: Int): Int {
                var v = 0L
                for (i in 0 until n) {
                    val byte = u8(base + (bit shr 3))
                    if (byte < 0) {
                        failed = true
                        return 0
                    }
                    v = v or (((byte shr (bit and 7).toInt()) and 1).toLong() shl i)
                    bit++
                }
                return v.toInt()
            }
        }

        // ---- BMP -----------------------------------------------------------------------------

        private fun bmp(): ImageInfo {
            val dib = u32le(14)
            return if (dib >= 40) {
                val w = u32le(18).toInt()
                val h = u32le(22).toInt()
                ImageInfo(
                    format = ImageFormat.BMP,
                    width = w,
                    height = kotlin.math.abs(h),
                    bitDepth = u16le(28).coerceAtLeast(0),
                )
            } else {
                ImageInfo(ImageFormat.BMP, width = u16le(18).coerceAtLeast(0), height = u16le(20).coerceAtLeast(0))
            }
        }
    }

    private val RATIOS = arrayOf(1 to 1, 12 to 10, 4 to 3, 3 to 2, 16 to 9, 5 to 4, 2 to 1)

    private const val CHUNK_ACTL = 0x6163544CL
    private const val CHUNK_TRNS = 0x74524E53L
    private const val CHUNK_IDAT = 0x49444154L
    private const val CHUNK_IEND = 0x49454E44L
}

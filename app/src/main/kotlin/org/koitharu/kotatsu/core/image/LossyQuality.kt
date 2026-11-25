package org.koitharu.kotatsu.core.image

/**
 * Lossy compression quality read from the file header only (no pixel decode).
 *  - JPEG: quantization tables (DQT) compared with the standard IJG tables.
 *  - WebP: VP8 key-frame quantizer index, mapped back to the libwebp quality scale.
 *  - AVIF: AV1 base_q_idx of a still picture, mapped back to the libavif quality scale.
 *  - PNG / BMP / lossless WebP: lossless.
 * Values are real ([Kind.EXACT]) only when the file matches a scale exactly; otherwise they are estimates.
 */
object LossyQuality {

    enum class Kind { LOSSLESS, EXACT, ESTIMATE }

    /** [quality] is 0..100 (ignored for [Kind.LOSSLESS]); [detail] is a short technical note or null. */
    data class Result(val kind: Kind, val quality: Int, val detail: String? = null)

    private val LOSSLESS = Result(Kind.LOSSLESS, 100)

    fun analyze(head: ByteArray, length: Int, format: ImageFormat): Result? {
        val len = minOf(length, head.size)
        return try {
            when (format) {
                ImageFormat.JPEG -> jpeg(head, len)
                ImageFormat.WEBP -> webp(head, len)
                ImageFormat.AVIF -> avif(head, len)
                ImageFormat.PNG, ImageFormat.BMP -> LOSSLESS
                else -> null
            }
        } catch (_: IndexOutOfBoundsException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    // ---------------------------------------------------------------- JPEG

    private val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )

    private val STD_LUMA = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
    )

    private val STD_CHROMA = IntArray(64) { 99 }.also {
        val head = intArrayOf(
            17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
            24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
        )
        head.copyInto(it)
    }

    /** Standard table in zigzag order, scaled by IJG quality [q] (1..100). */
    private fun ijgTable(std: IntArray, q: Int, maxValue: Int): IntArray {
        val scale = if (q < 50) 5000 / q else 200 - 2 * q
        return IntArray(64) { i ->
            ((std[ZIGZAG[i]] * scale + 50) / 100).coerceIn(1, maxValue)
        }
    }

    private fun distance(a: IntArray, b: IntArray): Int {
        var sum = 0
        for (i in 0 until 64) sum += kotlin.math.abs(a[i] - b[i])
        return sum
    }

    private fun jpeg(b: ByteArray, len: Int): Result? {
        if (len < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        val tables = arrayOfNulls<IntArray>(4)
        var wide = false
        var i = 2
        while (i + 4 <= len) {
            if (b[i] != 0xFF.toByte()) break
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { // fill byte
                i++
                continue
            }
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2
                continue
            }
            if (marker == 0xDA || marker == 0xD9) break
            val segLen = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (segLen < 2) break
            if (marker == 0xDB) {
                var p = i + 4
                val end = minOf(i + 2 + segLen, len)
                while (p < end) {
                    val pq = (b[p].toInt() and 0xFF) shr 4
                    val tq = b[p].toInt() and 0x0F
                    p++
                    val step = if (pq == 0) 1 else 2
                    if (p + 64 * step > end) break
                    val t = IntArray(64)
                    for (k in 0 until 64) {
                        t[k] = if (pq == 0) {
                            b[p + k].toInt() and 0xFF
                        } else {
                            ((b[p + 2 * k].toInt() and 0xFF) shl 8) or (b[p + 2 * k + 1].toInt() and 0xFF)
                        }
                    }
                    p += 64 * step
                    if (pq != 0) wide = true
                    if (tq < tables.size) tables[tq] = t
                }
            }
            i += 2 + segLen
        }
        val luma = tables[0] ?: return null
        val chroma = tables[1]
        val maxValue = if (wide) 32767 else 255
        var bestQ = 1
        var bestDist = Int.MAX_VALUE
        for (q in 1..100) {
            val d = distance(ijgTable(STD_LUMA, q, maxValue), luma)
            if (d < bestDist) {
                bestDist = d
                bestQ = q
            }
        }
        var exact = bestDist == 0
        if (exact && chroma != null) {
            exact = distance(ijgTable(STD_CHROMA, bestQ, maxValue), chroma) == 0
        }
        return Result(if (exact) Kind.EXACT else Kind.ESTIMATE, bestQ)
    }

    // ---------------------------------------------------------------- WebP

    private fun fourCc(b: ByteArray, p: Int, s: String): Boolean {
        if (p + 4 > b.size) return false
        for (k in 0 until 4) if (b[p + k] != s[k].code.toByte()) return false
        return true
    }

    private fun le32(b: ByteArray, p: Int): Long =
        (b[p].toLong() and 0xFF) or ((b[p + 1].toLong() and 0xFF) shl 8) or
            ((b[p + 2].toLong() and 0xFF) shl 16) or ((b[p + 3].toLong() and 0xFF) shl 24)

    private fun webp(b: ByteArray, len: Int): Result? {
        if (len < 20 || !fourCc(b, 0, "RIFF") || !fourCc(b, 8, "WEBP")) return null
        var p = 12
        while (p + 8 <= len) {
            val size = le32(b, p + 4)
            when {
                fourCc(b, p, "VP8L") -> return LOSSLESS
                fourCc(b, p, "VP8 ") -> return vp8(b, p + 8, len)
                fourCc(b, p, "ANMF") -> p += 8 + 16 // descend into the first frame
                else -> {
                    val next = p.toLong() + 8 + size + (size and 1L)
                    if (next > len) return null
                    p = next.toInt()
                }
            }
        }
        return null
    }

    /** Boolean entropy decoder of VP8 (RFC 6386 section 7). Reading past the end yields zero bits. */
    private class BoolDecoder(val d: ByteArray, var pos: Int, val end: Int) {
        var value = 0
        var range = 255
        var bits = 0

        init {
            value = (byte(0) shl 8) or byte(1)
            pos += 2
        }

        private fun byte(offset: Int): Int = if (pos + offset < end) d[pos + offset].toInt() and 0xFF else 0

        fun bool(prob: Int): Int {
            val split = 1 + (((range - 1) * prob) shr 8)
            val big = split shl 8
            val bit: Int
            if (value >= big) {
                bit = 1
                range -= split
                value -= big
            } else {
                bit = 0
                range = split
            }
            while (range < 128) {
                value = value shl 1
                range = range shl 1
                bits++
                if (bits == 8) {
                    bits = 0
                    if (pos < end) value = value or (d[pos].toInt() and 0xFF)
                    pos++
                }
            }
            return bit
        }

        fun literal(n: Int): Int {
            var v = 0
            repeat(n) { v = (v shl 1) or bool(128) }
            return v
        }

        fun flag(): Boolean = bool(128) == 1
    }

    private fun vp8(b: ByteArray, start: Int, len: Int): Result? {
        if (start + 10 + 2 > len) return null
        val tag = (b[start].toInt() and 0xFF) or ((b[start + 1].toInt() and 0xFF) shl 8) or
            ((b[start + 2].toInt() and 0xFF) shl 16)
        if (tag and 1 != 0) return null // not a key frame
        val d = BoolDecoder(b, start + 10, len)
        d.literal(2) // color space, clamping type
        var qi: Double
        var segQuant: IntArray? = null
        var absolute = false
        if (d.flag()) { // segmentation enabled
            val updateMap = d.flag()
            val updateData = d.flag()
            if (updateData) {
                absolute = d.flag()
                val q = IntArray(4)
                for (k in 0 until 4) {
                    if (d.flag()) {
                        val v = d.literal(7)
                        q[k] = if (d.flag()) -v else v
                    }
                }
                segQuant = q
                for (k in 0 until 4) {
                    if (d.flag()) {
                        d.literal(6)
                        d.literal(1)
                    }
                }
            }
            if (updateMap) {
                for (k in 0 until 3) if (d.flag()) d.literal(8)
            }
        }
        d.literal(1) // filter type
        d.literal(6) // filter level
        d.literal(3) // sharpness
        if (d.flag()) { // loop filter deltas
            if (d.flag()) {
                for (k in 0 until 8) {
                    if (d.flag()) {
                        d.literal(6)
                        d.literal(1)
                    }
                }
            }
        }
        d.literal(2) // token partitions
        val yac = d.literal(7)
        qi = if (segQuant != null) {
            var sum = 0
            for (v in segQuant) sum += (if (absolute) v else yac + v).coerceIn(0, 127)
            sum / 4.0
        } else {
            yac.toDouble()
        }
        qi = qi.coerceIn(0.0, 127.0)
        val v = 1.0 - qi / 127.0
        val linear = v * v * v
        val c = if (linear < 0.5) linear * 1.5 else (linear + 1.0) / 2.0
        val quality = Math.round(c * 100.0).toInt().coerceIn(0, 100)
        return Result(Kind.ESTIMATE, quality, "VP8 q-index ${Math.round(qi)}/127")
    }

    // ---------------------------------------------------------------- AVIF (AV1 still picture)

    private class BitReader(val d: ByteArray, var bit: Long, val end: Int) {
        fun read(n: Int): Int {
            var v = 0
            repeat(n) {
                val byteIndex = (bit shr 3).toInt()
                if (byteIndex >= end) throw IndexOutOfBoundsException()
                v = (v shl 1) or ((d[byteIndex].toInt() shr (7 - (bit and 7).toInt())) and 1)
                bit++
            }
            return v
        }

        fun flag(): Boolean = read(1) == 1
    }

    private fun indexOf(b: ByteArray, len: Int, s: String, from: Int = 0): Int {
        var i = from
        while (i + 4 <= len) {
            if (b[i] == s[0].code.toByte() && fourCc(b, i, s)) return i
            i++
        }
        return -1
    }

    /** OBU callback receives (type, payloadStart, payloadEnd); stops when it returns non-null. */
    private inline fun <T : Any> scanObus(b: ByteArray, from: Int, to: Int, block: (Int, Int, Int) -> T?): T? {
        var p = from
        while (p < to) {
            val h = b[p].toInt() and 0xFF
            val type = (h shr 3) and 15
            val ext = (h shr 2) and 1
            val hasSize = (h shr 1) and 1
            p += 1 + ext
            var size = (to - p).toLong()
            if (hasSize == 1) {
                var v = 0L
                var n = 0
                while (true) {
                    if (p >= to || n >= 8) return null
                    val x = b[p++].toInt() and 0xFF
                    v = v or ((x and 0x7F).toLong() shl (7 * n))
                    n++
                    if (x and 0x80 == 0) break
                }
                size = v
            }
            if (size < 0 || p + size > to) return null
            block(type, p, p + size.toInt())?.let { return it }
            p += size.toInt()
        }
        return null
    }

    private class AvSeq(val width: Int, val height: Int, val sb128: Boolean, val superres: Boolean)

    private fun avSeq(b: ByteArray, s: Int, e: Int): AvSeq? {
        val r = BitReader(b, s * 8L, e)
        r.read(3) // profile
        r.read(1) // still picture
        if (!r.flag()) return null // only reduced still picture headers are handled
        r.read(5) // level
        val wb = r.read(4) + 1
        val hb = r.read(4) + 1
        val w = r.read(wb) + 1
        val h = r.read(hb) + 1
        val sb = r.flag()
        r.read(2) // filter intra, intra edge
        val sup = r.flag()
        return AvSeq(w, h, sb, sup)
    }

    private fun tileLog2(blk: Int, target: Int): Int {
        var k = 0
        while ((blk shl k) < target) k++
        return k
    }

    private fun avFrameQIdx(b: ByteArray, s: Int, e: Int, seq: AvSeq): Int? {
        val r = BitReader(b, s * 8L, e)
        r.read(1) // disable_cdf_update
        val sct = r.flag() // allow_screen_content_tools (SELECT in reduced headers)
        if (sct) r.read(1) // force_integer_mv (SELECT)
        if (seq.superres && r.flag()) return null // superres in use: unsupported
        if (r.flag()) r.read(32) // render size differs
        if (sct) r.read(1) // allow_intrabc
        val sbShift = if (seq.sb128) 7 else 6
        val sbSize = 1 shl sbShift
        val sbCols = (seq.width + sbSize - 1) / sbSize
        val sbRows = (seq.height + sbSize - 1) / sbSize
        val maxTileWidthSb = 4096 shr sbShift
        val maxTileAreaSb = (4096 * 2304) shr (2 * sbShift)
        val minLog2Cols = tileLog2(maxTileWidthSb, sbCols)
        val maxLog2Cols = tileLog2(1, sbCols)
        val maxLog2Rows = tileLog2(1, sbRows)
        val minLog2Tiles = maxOf(minLog2Cols, tileLog2(maxTileAreaSb, sbRows * sbCols))
        if (!r.flag()) return null // non-uniform tile spacing: unsupported
        var cols = minLog2Cols
        while (cols < maxLog2Cols) {
            if (r.flag()) cols++ else break
        }
        var rows = maxOf(minLog2Tiles - cols, 0)
        while (rows < maxLog2Rows) {
            if (r.flag()) rows++ else break
        }
        if (cols > 0 || rows > 0) {
            r.read(cols + rows) // context_update_tile_id
            r.read(2) // tile_size_bytes_minus_1
        }
        return r.read(8) // base_q_idx
    }

    private fun avif(b: ByteArray, len: Int): Result? {
        val mdat = indexOf(b, len, "mdat")
        if (mdat < 0) return null
        var seq: AvSeq? = null
        val qIdx = scanObus(b, mdat + 4, len) { type, s, e ->
            when (type) {
                1 -> {
                    seq = avSeq(b, s, e) ?: seq
                    null
                }
                3, 6 -> seq?.let { avFrameQIdx(b, s, e, it) } ?: -1
                2 -> null
                else -> -1
            }
        } ?: return null
        if (qIdx < 0) return null
        // libavif quality <-> AV1 base_q_idx, interpolated from measured encodes.
        val xs = intArrayOf(0, 16, 32, 60, 92, 148, 196, 236, 255)
        val ys = intArrayOf(100, 95, 90, 80, 70, 50, 30, 10, 0)
        var quality = 0
        for (k in 0 until xs.size - 1) {
            if (qIdx <= xs[k + 1]) {
                val t = (qIdx - xs[k]).toDouble() / (xs[k + 1] - xs[k])
                quality = Math.round(ys[k] + (ys[k + 1] - ys[k]) * t).toInt()
                break
            }
        }
        return Result(Kind.ESTIMATE, quality.coerceIn(0, 100), "AV1 q-index $qIdx/255")
    }
}

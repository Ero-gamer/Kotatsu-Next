package org.koitharu.kotatsu.core.image.codecs

import android.content.Context
import android.net.Uri
import android.util.LruCache
import org.koitharu.kotatsu.core.image.ImageFormat
import org.koitharu.kotatsu.core.image.ImageFormatProbe
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipFile

/** Resolves JPEG XL / still AVIF image URIs to files the codecs can read. */
internal object NativeSources {

    private const val DIR = "native_codecs"
    private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000
    private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
    private const val SCHEME_FILE = "file"
    private const val SCHEME_ZIP = "file+zip"
    private const val ASSET_PREFIX = "/android_asset/"

    class Resolved(val file: File, val isTemporary: Boolean) {
        fun cleanup() {
            if (isTemporary) file.delete()
        }
    }

    /**
     * The JPEG XL file behind [uri], or null when [uri] is not one: plain files are sniffed by content,
     * archive entries are only considered when their name says `.jxl`, which keeps ordinary pages free
     * of any extra I/O. (Animated PNG goes through the animation pipeline instead.)
     */
    fun resolve(context: Context, uri: Uri): Resolved? = try {
        when (uri.scheme) {
            SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                val file = if (path.startsWith(ASSET_PREFIX, ignoreCase = true)) null else File(path)
                if (file != null && file.isFile && isJxlFile(file)) Resolved(file, false) else null
            }

            SCHEME_ZIP -> {
                val name = uri.fragment
                val archive = File(uri.schemeSpecificPart)
                if (name != null && (name.endsWith(".jxl", true) || (!name.hasExtension() && isJxlEntry(archive, name)))) {
                    extract(context, archive, name)
                } else {
                    null
                }
            }

            else -> null
        }
    } catch (_: IOException) {
        null
    }

    /**
     * The PNG file behind [uri], or null when [uri] is not one (or, for archive entries, is smaller than
     * [minArchiveBytes], which keeps small pages from being extracted for nothing). Plain files are sniffed by
     * content, archive entries when their name says `.png` or has no extension.
     */
    fun resolvePng(context: Context, uri: Uri, minArchiveBytes: Long): Resolved? = try {
        when (uri.scheme) {
            SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                val file = if (path.startsWith(ASSET_PREFIX, ignoreCase = true)) null else File(path)
                if (file != null && file.isFile && isPngFile(file)) Resolved(file, false) else null
            }

            SCHEME_ZIP -> {
                val name = uri.fragment
                val archive = File(uri.schemeSpecificPart)
                if (name != null && (name.endsWith(".png", true) || !name.hasExtension()) && isPngEntry(archive, name, minArchiveBytes)) {
                    extract(context, archive, name)
                } else {
                    null
                }
            }

            else -> null
        }
    } catch (_: IOException) {
        null
    }

    private fun isPngFile(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(PNG_SIGNATURE.size)
            raf.read(head) == head.size && head.contentEquals(PNG_SIGNATURE)
        }
    } catch (_: IOException) {
        false
    }

    private fun isPngEntry(archive: File, name: String, minBytes: Long): Boolean = try {
        val head = entryHead(archive, name) ?: return false
        if (head.size in 0 until minBytes) return false
        head.n >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { head.bytes[it] == PNG_SIGNATURE[it] }
    } catch (_: IOException) {
        false
    }

    private fun extract(context: Context, archive: File, name: String): Resolved? {
        val dir = File(context.cacheDir, DIR).also { it.mkdirs() }
        trim(dir)
        val temp = File(dir, UUID.randomUUID().toString())
        var ok = false
        try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(name) ?: return null
                if (entry.size !in 1..MAX_ENTRY_BYTES) return null
                zip.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it) } }
            }
            ok = true
            return Resolved(temp, true)
        } finally {
            if (!ok) temp.delete()
        }
    }

    private fun trim(dir: File) {
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > STALE_AFTER_MS) it.delete() }
    }

    /**
     * The still AVIF file behind [uri], or null when [uri] is not one: plain files are sniffed by content, archive
     * entries when their name says `.avif` or has no extension (then a head read decides).
     */
    fun resolveAvif(context: Context, uri: Uri): Resolved? = try {
        when (uri.scheme) {
            SCHEME_FILE -> {
                val path = uri.schemeSpecificPart
                val file = if (path.startsWith(ASSET_PREFIX, ignoreCase = true)) null else File(path)
                if (file != null && file.isFile && isStillAvif(file)) Resolved(file, false) else null
            }

            SCHEME_ZIP -> {
                val name = uri.fragment
                val archive = File(uri.schemeSpecificPart)
                if (name != null && (name.endsWith(".avif", true) || !name.hasExtension()) && isStillAvifEntry(archive, name)) {
                    extract(context, archive, name)
                } else {
                    null
                }
            }

            else -> null
        }
    } catch (_: IOException) {
        null
    }

    private fun isStillAvif(file: File): Boolean = try {
        ImageFormatProbe.probe(file).let { it.format == ImageFormat.AVIF && !it.animated }
    } catch (_: Exception) {
        false
    }

    private fun isStillAvifEntry(archive: File, name: String): Boolean = try {
        val head = entryHead(archive, name) ?: return false
        ImageFormatProbe.probe(head.bytes, head.n, truncated = head.size > head.n).let { it.format == ImageFormat.AVIF && !it.animated }
    } catch (_: Exception) {
        false
    }

    private class EntryHead(val bytes: ByteArray, val n: Int, val size: Long)

    // One head read per archive entry serves the JXL, PNG and AVIF probes (one ZipFile open instead of up to three).
    private val headCache = LruCache<String, EntryHead>(HEAD_CACHE_ENTRIES)

    private fun entryHead(archive: File, name: String): EntryHead? {
        val key = "${archive.path}|${archive.length()}|${archive.lastModified()}|$name"
        headCache.get(key)?.let { return it }
        val head = ZipFile(archive).use { zip ->
            val entry = zip.getEntry(name) ?: return null
            val buf = ByteArray(AVIF_HEAD)
            var n = 0
            zip.getInputStream(entry).use { input ->
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r <= 0) break
                    n += r
                }
            }
            EntryHead(buf, n, entry.size)
        }
        headCache.put(key, head)
        return head
    }

    private fun String.hasExtension(): Boolean = substringAfterLast('/').contains('.')

    /** Entries saved without an extension: a 12-byte header read decides, so ordinary pages cost almost nothing. */
    private fun isJxlEntry(archive: File, name: String): Boolean = try {
        val head = entryHead(archive, name) ?: return false
        isJxl(head.bytes, head.n)
    } catch (_: IOException) {
        false
    }

    private fun isJxlFile(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(HEAD)
            val n = raf.read(head)
            isJxl(head, n)
        }
    } catch (_: IOException) {
        false
    }

    fun isJxl(head: ByteArray, n: Int): Boolean {
        val codestream = n >= 2 && head[0] == 0xFF.toByte() && head[1] == 0x0A.toByte()
        val container = n >= 12 && CONTAINER_SIGNATURE.indices.all { head[it] == CONTAINER_SIGNATURE[it] }
        return codestream || container
    }

    /** `00 00 00 0C 'J' 'X' 'L' ' '`: the start of the ISO BMFF signature box. */
    private val CONTAINER_SIGNATURE = byteArrayOf(0, 0, 0, 0x0C, 'J'.code.toByte(), 'X'.code.toByte(), 'L'.code.toByte(), ' '.code.toByte())

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    private const val HEAD = 12
    private const val AVIF_HEAD = 32 * 1024
    private const val HEAD_CACHE_ENTRIES = 4
}

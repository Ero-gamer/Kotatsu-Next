package org.koitharu.kotatsu.core.image.codecs

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipFile

/** Resolves JPEG XL image URIs to files the native codecs can read. */
internal object NativeSources {

    private const val DIR = "native_codecs"
    private const val STALE_AFTER_MS = 60L * 60 * 1000
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
                if (name != null && name.endsWith(".jxl", true)) {
                    extract(context, File(uri.schemeSpecificPart), name)
                } else {
                    null
                }
            }

            else -> null
        }
    } catch (_: IOException) {
        null
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

    private const val HEAD = 12
}

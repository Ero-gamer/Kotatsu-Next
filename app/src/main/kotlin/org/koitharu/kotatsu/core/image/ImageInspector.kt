package org.koitharu.kotatsu.core.image

import android.content.Context
import android.net.Uri
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** Reads header-level info for a page image (raw file, CBZ entry or content uri) without decoding pixels. */
object ImageInspector {

    private const val MAX_READ = 8L * 1024 * 1024

    class Report(
        val fileName: String,
        /** Extension exactly as in the file name (may be wrong or empty). */
        val extension: String,
        val info: ImageInfo,
        val container: String,
        val head: ByteArray,
        val headLength: Int,
    ) {
        val extensionFormat: ImageFormat get() = ImageFormatProbe.formatFromExtension(fileName)
        val isExtensionMismatch: Boolean
            get() = info.isKnown && extensionFormat != info.format
    }

    @Throws(IOException::class)
    fun inspect(context: Context, uri: Uri): Report? = when {
        uri.isZipUri() -> inspectZip(File(uri.schemeSpecificPart), uri.fragment ?: return null)
        uri.isFileUri() -> inspectFile(uri.toFileOrNull() ?: return null)
        else -> inspectStream(context, uri)
    }

    private fun inspectFile(file: File): Report? {
        if (!file.isFile) return null
        val info = ImageFormatProbe.probe(file, deep = true)
        val head = ByteArray(minOf(file.length(), RawImageDetails.HEAD_SIZE.toLong()).toInt())
        file.inputStream().use { it.readNBytesCompat(head) }
        return Report(file.name, extensionOf(file.name), info, "File", head, head.size)
    }

    private fun inspectZip(zipFile: File, entryName: String): Report? = ZipFile(zipFile).use { zip ->
        val entry = zip.getEntry(entryName) ?: return null
        val size = entry.size
        val cap = if (size in 0..MAX_READ) size.toInt() else MAX_READ.toInt()
        val data = ByteArray(cap)
        val read = zip.getInputStream(entry).use { it.readNBytesCompat(data) }
        val info = ImageFormatProbe.probe(data, read, deep = true).let { if (size > 0) it.copy(fileSize = size) else it }
        val method = if (entry.method == ZipEntry.STORED) "CBZ (stored)" else "CBZ (deflated)"
        val name = entryName.substringAfterLast('/')
        Report(name, extensionOf(name), info, method, data.copyOf(minOf(read, RawImageDetails.HEAD_SIZE)), minOf(read, RawImageDetails.HEAD_SIZE))
    }

    private fun inspectStream(context: Context, uri: Uri): Report? {
        val data = ByteArray(MAX_READ.toInt())
        val read = context.contentResolver.openInputStream(uri)?.use { it.readNBytesCompat(data) } ?: return null
        val info = ImageFormatProbe.probe(data, read, deep = true)
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: ""
        return Report(name, extensionOf(name), info, "Content", data.copyOf(minOf(read, RawImageDetails.HEAD_SIZE)), minOf(read, RawImageDetails.HEAD_SIZE))
    }

    private fun extensionOf(name: String) = if ('.' in name) name.substringAfterLast('.') else ""

    private fun java.io.InputStream.readNBytesCompat(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}

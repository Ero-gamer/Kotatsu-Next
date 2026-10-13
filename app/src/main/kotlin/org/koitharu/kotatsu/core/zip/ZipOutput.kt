package org.koitharu.kotatsu.core.zip

import androidx.annotation.WorkerThread
import androidx.collection.ArraySet
import okio.Closeable
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.withChildren
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Writes a zip. Image entries are STORED (no deflate): page images are already compressed, so deflating
 * costs CPU on every write and extract on every read for ~0% gain, and STORED entries can be
 * memory-mapped straight out of the archive. Text entries (index, metadata) stay deflated.
 */
class ZipOutput(
    val file: File,
    private val compressionLevel: Int = Deflater.DEFAULT_COMPRESSION,
) : Closeable {

    private val entryNames = ArraySet<String>()
    private var cachedOutput: ZipOutputStream? = null
    private var append: Boolean = false

    @Blocking
    fun put(name: String, file: File): Boolean = withOutput { output ->
        output.appendFile(file, name)
    }

    @Blocking
    fun put(name: String, content: String): Boolean = withOutput { output ->
        output.appendText(content, name)
    }

    @Blocking
    fun addDirectory(name: String): Boolean {
        val entry = if (name.endsWith("/")) {
            ZipEntry(name)
        } else {
            ZipEntry("$name/")
        }
        return if (entryNames.add(entry.name)) {
            withOutput { output ->
                output.putNextEntry(entry)
                output.closeEntry()
            }
            true
        } else {
            false
        }
    }

    @Blocking
    fun copyEntryFrom(other: ZipFile, entry: ZipEntry, name: String = entry.name): Boolean = if (entryNames.add(name)) {
        val zipEntry = copiedEntry(entry, name)
        withOutput { output ->
            output.putNextEntry(zipEntry)
            try {
                other.getInputStream(entry).use { input ->
                    input.copyTo(output)
                }
            } finally {
                output.closeEntry()
            }
        }
        true
    } else {
        false
    }

    @Blocking
    fun finish() = withOutput { output ->
        output.finish()
    }

    @Synchronized
    override fun close() {
        cachedOutput?.closeSafe()
        cachedOutput = null
    }

    @WorkerThread
    private fun ZipOutputStream.appendFile(fileToZip: File, name: String): Boolean {
        if (fileToZip.isDirectory) {
            val entry = if (name.endsWith("/")) {
                ZipEntry(name)
            } else {
                ZipEntry("$name/")
            }
            if (!entryNames.add(entry.name)) {
                return false
            }
            putNextEntry(entry)
            closeEntry()
            fileToZip.withChildren { children ->
                children.forEach { childFile ->
                    appendFile(childFile, "$name/${childFile.name}")
                }
            }
        } else {
            FileInputStream(fileToZip).use { fis ->
                if (!entryNames.add(name)) {
                    return false
                }
                val zipEntry = if (isStoredName(name)) storedEntry(name, fileToZip) else ZipEntry(name)
                putNextEntry(zipEntry)
                try {
                    fis.copyTo(this)
                } finally {
                    closeEntry()
                }
            }
        }
        return true
    }

    @WorkerThread
    private fun ZipOutputStream.appendText(content: String, name: String): Boolean {
        if (!entryNames.add(name)) {
            return false
        }
        val zipEntry = ZipEntry(name)
        putNextEntry(zipEntry)
        try {
            content.byteInputStream().copyTo(this)
        } finally {
            closeEntry()
        }
        return true
    }

    @Synchronized
    private fun <T> withOutput(block: (ZipOutputStream) -> T): T = try {
        (cachedOutput ?: newOutput(append)).withOutputImpl(block).also {
            append = true // after 1st success write
        }
    } catch (e: NullPointerException) { // probably NullPointerException: Deflater has been closed
        e.printStackTraceDebug()
        newOutput(append).withOutputImpl(block)
    }

    private fun <T> ZipOutputStream.withOutputImpl(block: (ZipOutputStream) -> T): T {
        val res = block(this)
        flush()
        return res
    }

    private fun newOutput(append: Boolean) = ZipOutputStream(FileOutputStream(file, append)).also {
        it.setLevel(compressionLevel)
        cachedOutput?.closeSafe()
        cachedOutput = it
    }

    private fun copiedEntry(entry: ZipEntry, name: String): ZipEntry {
        if (!isStoredName(name) || entry.crc < 0 || entry.size < 0) return ZipEntry(name)
        return ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = entry.size
            compressedSize = entry.size
            crc = entry.crc
        }
    }

    private fun Closeable.closeSafe() {
        try {
            close()
        } catch (e: NullPointerException) {
            // Don't throw the "Deflater has been closed" exception
            e.printStackTraceDebug()
        }
    }

    private companion object {

        private val TEXT_SUFFIXES = arrayOf(".json", ".txt", ".xml", ".html")

        /** Everything that is not a known text entry is binary (images) and gets STORED. */
        fun isStoredName(name: String): Boolean {
            if (name.endsWith("/")) return false
            return TEXT_SUFFIXES.none { name.endsWith(it, ignoreCase = true) } &&
                true
        }

        fun storedEntry(name: String, source: File): ZipEntry {
            val crc = CRC32()
            val buffer = ByteArray(32 * 1024)
            FileInputStream(source).use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    crc.update(buffer, 0, n)
                }
            }
            return ZipEntry(name).apply {
                method = ZipEntry.STORED
                size = source.length()
                compressedSize = source.length()
                this.crc = crc.value
            }
        }
    }
}

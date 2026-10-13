package org.koitharu.kotatsu.local.data

import org.koitharu.kotatsu.core.image.ImageFormat
import org.koitharu.kotatsu.core.image.ImageFormatProbe
import org.koitharu.kotatsu.core.zip.ZipOutput
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Manual, on-demand maintenance for downloaded archives (never runs automatically):
 *  - [repackStored]: rewrites image entries as STORED so pages can be read straight from the archive;
 *  - [fixExtensions]: renames entries whose extension disagrees with the real (header-sniffed) format.
 * Both write a temporary archive next to the original, verify it, then atomically replace the original.
 */
object CbzMaintenance {

    private const val TMP_SUFFIX = ".repack.tmp"
    private const val SPACE_MARGIN = 8L * 1024 * 1024
    private const val MAX_DEPTH = 4

    class Summary(val scanned: Int, val changed: Int, val failed: Int)

    fun listArchives(dirs: List<File>): List<File> {
        val out = ArrayList<File>()
        for (dir in dirs) collect(dir, 0, out)
        return out
    }

    private fun collect(dir: File, depth: Int, out: MutableList<File>) {
        val children = dir.listFiles() ?: return
        for (f in children) {
            when {
                f.isDirectory && depth < MAX_DEPTH -> collect(f, depth + 1, out)
                f.isZipArchive && !f.name.endsWith(TMP_SUFFIX) -> out.add(f)
            }
        }
    }

    /** Removes temp archives left by an interrupted run. */
    fun cleanupTemp(dirs: List<File>) {
        fun walk(dir: File, depth: Int) {
            dir.listFiles()?.forEach {
                if (it.isDirectory && depth < MAX_DEPTH) {
                    walk(it, depth + 1)
                } else if (it.name.endsWith(TMP_SUFFIX)) {
                    it.delete()
                }
            }
        }
        dirs.forEach { walk(it, 0) }
    }

    fun repackStored(file: File): Boolean = rewrite(file, renames = false)

    fun fixExtensions(file: File): Boolean = rewrite(file, renames = true)

    @Throws(IOException::class)
    private fun rewrite(file: File, renames: Boolean): Boolean {
        val parent = file.parentFile ?: return false
        val tmp = File(parent, file.name + TMP_SUFFIX)
        tmp.delete()
        var replace = false
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                val coverName = coverEntryName(zip)
                val taken = HashSet<String>(entries.size * 2)
                entries.forEach { taken.add(it.name) }
                val newNames = HashMap<ZipEntry, String>()
                var needsStored = false
                for (e in entries) {
                    if (e.isDirectory || isTextName(e.name)) continue
                    if (renames) {
                        if (e.name == coverName) continue // the index refers to the cover by exact name
                        val fixed = fixedName(zip, e, taken) ?: continue
                        taken.add(fixed)
                        newNames[e] = fixed
                    } else if (e.method != ZipEntry.STORED) {
                        needsStored = true
                    }
                }
                if (newNames.isEmpty() && !needsStored) return false
                if (parent.usableSpace < file.length() + SPACE_MARGIN) throw IOException("Not enough free space")
                ZipOutput(tmp).use { out ->
                    for (e in entries) {
                        if (e.isDirectory) {
                            out.addDirectory(e.name)
                        } else {
                            out.copyEntryFrom(zip, e, newNames[e] ?: e.name)
                        }
                    }
                    out.finish()
                }
                ZipFile(tmp).use { check ->
                    if (check.size() != zip.size()) throw IOException("Verification failed")
                }
                replace = true
            }
            if (replace && !tmp.renameTo(file)) throw IOException("Cannot replace ${file.name}")
            return replace
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun coverEntryName(zip: ZipFile): String? {
        val index = zip.getEntry("index.json") ?: return null
        return runCatching {
            MangaIndex(zip.getInputStream(index).use { it.reader().readText() }).getCoverEntry()
        }.getOrNull()
    }

    private fun isTextName(name: String): Boolean = name.endsWith(".json", true) || name.endsWith(".txt", true) ||
        name.endsWith(".xml", true) || name.endsWith(".html", true)

    /** Returns the corrected entry name, or null when the extension already matches / is unknown / would collide. */
    private fun fixedName(zip: ZipFile, e: ZipEntry, taken: Set<String>): String? {
        val head = ByteArray(64)
        val n = zip.getInputStream(e).use { it.read(head).coerceAtLeast(0) }
        val real = ImageFormatProbe.sniff(head, n)
        val ext = real.extension ?: return null
        if (real == ImageFormat.BMP || ImageFormatProbe.formatFromExtension(e.name) == real) return null
        val candidate = e.name.substringBeforeLast('.', e.name) + "." + ext
        return candidate.takeIf { it != e.name && it !in taken }
    }
}

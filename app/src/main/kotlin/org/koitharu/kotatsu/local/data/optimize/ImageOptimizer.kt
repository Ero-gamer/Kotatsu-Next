package org.koitharu.kotatsu.local.data.optimize

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.image.ImageFormat
import org.koitharu.kotatsu.core.image.ImageFormatProbe
import org.koitharu.kotatsu.core.image.ImageInfo
import org.koitharu.kotatsu.core.image.PageConverter
import org.koitharu.kotatsu.core.image.SourceQuality
import org.koitharu.kotatsu.core.image.TallImageSplitter
import org.koitharu.kotatsu.core.image.WebpEncoding
import org.koitharu.kotatsu.core.image.WebpPolicy
import org.koitharu.kotatsu.core.image.WebpTarget
import org.koitharu.kotatsu.core.zip.ZipOutput
import org.koitharu.kotatsu.local.data.MangaIndex
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Rewrites the pages of a downloaded CBZ: splits very tall images and / or converts pages to WebP
 * (see [OptimizeOptions]).
 *
 * Safety model, per archive:
 *  - the original is never modified; a new archive is written next to it as `<name>.opt.tmp`;
 *  - it is verified (entry count, new entries non-empty) and only then renamed over the original;
 *  - if the original changed while we worked (a download replaced it) the swap is abandoned;
 *  - free space is checked first; any failure deletes the temp archive and leaves the original alone.
 * A run is therefore idempotent and trivially resumable: archives already done have nothing left to do.
 *
 * Quality: for every chapter group of the archive 1-2 pages from the middle are inspected (covers and
 * credits are often saved differently): the lowest lossy quality found is applied to all output of that
 * group that is lossy. What a page gets is decided by [WebpPolicy] (auto mode: PNG lossy 98, other
 * lossless sources and unknown 95).
 */
object ImageOptimizer {

    const val TMP_SUFFIX = ".opt.tmp"

    private const val INDEX_ENTRY = "index.json"

    private const val HEAD_BYTES = 256 * 1024
    private const val SPACE_MARGIN = 32L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
    private val GROUP_REGEX = Regex("""^(\d{8}_\d{4})\d{4}(_\d{2})?\.[^./]+$""")

    private enum class Kind { NONE, SPLIT, CONVERT }

    private class Planned(val entry: ZipEntry, val info: ImageInfo, val kind: Kind, val group: String)

    /** [items] are the pages to change; [tooLarge] pages are ones the options want to convert but cannot. */
    private class Plan(val items: List<Planned>, val tooLarge: Int)

    /** Counts what [options] would change in [files]. Reads headers only. */
    suspend fun scan(files: List<File>, options: OptimizeOptions): ScanResult = withContext(Dispatchers.IO) {
        var toChange = 0
        var split = 0
        var convert = 0
        var tooLarge = 0
        for (file in files) {
            currentCoroutineContext().ensureActive()
            try {
                ZipFile(file).use { zip ->
                    val plan = plan(zip, options)
                    val s = plan.items.count { it.kind == Kind.SPLIT }
                    val c = plan.items.count { it.kind == Kind.CONVERT }
                    if (s + c > 0) toChange++
                    split += s
                    convert += c
                    tooLarge += plan.tooLarge
                }
            } catch (_: IOException) {
                // unreadable archive: nothing to offer for it
            }
        }
        ScanResult(files.size, toChange, split, convert, tooLarge)
    }

    /**
     * Rewrites [file]. [workDir] holds short-lived scratch files. [pause] is awaited between pages
     * (suspends while the run is paused) and cancellation is checked there too. [onProgress] gets
     * (pages done, pages total).
     *
     * @return the counters for this archive; `changedArchives == 0` when nothing was replaced.
     * @throws IOException when the archive could not be rewritten (the original is untouched).
     */
    suspend fun rewrite(
        file: File,
        options: OptimizeOptions,
        workDir: File,
        pause: suspend () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OptimizeStats = withContext(Dispatchers.IO) {
        if (options.isNoop) return@withContext OptimizeStats(archives = 1)
        val parent = file.parentFile ?: throw IOException("No parent directory")
        val tmp = File(parent, file.name + TMP_SUFFIX)
        workDir.mkdirs()
        tmp.delete()
        val sizeBefore = file.length()
        val modifiedBefore = file.lastModified()
        var stats = OptimizeStats(archives = 1)
        var replace = false
        try {
            ZipFile(file).use { zip ->
                val planned = plan(zip, options).items
                if (planned.isEmpty()) return@withContext stats
                if (parent.usableSpace < sizeBefore / 5 * 6 + SPACE_MARGIN) throw IOException("Not enough free space")
                val byName = planned.associateBy { it.entry.name }
                val entries = zip.entries().toList()
                val taken = HashSet<String>(entries.size * 2).also { set -> entries.forEach { set.add(it.name) } }
                val groupQuality = HashMap<String, SourceQuality>() // chapter group -> what its sampled pages were saved with
                val created = HashSet<String>()
                var written = 0
                var changedEntries = 0
                var split = 0
                var parts = 0
                var converted = 0
                var kept = 0
                var indexEntry: ZipEntry? = null
                ZipOutput(tmp).use { out ->
                    for ((i, e) in entries.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        pause()
                        onProgress(i, entries.size)
                        val p = byName[e.name]
                        if (e.isDirectory) {
                            out.addDirectory(e.name)
                            written++
                            continue
                        }
                        if (e.name == INDEX_ENTRY) {
                            indexEntry = e // written last, once we know whether anything changed
                            continue
                        }
                        when (p?.kind) {
                            Kind.SPLIT -> {
                                val quality = groupQuality.getOrPut(p.group) { sampleQuality(zip, planned, p.group, workDir) }
                                val newParts = splitEntry(zip, p, quality, options, workDir, taken)
                                if (newParts == null) {
                                    kept++
                                    if (!out.copyEntryFrom(zip, e)) throw IOException("Duplicate entry ${e.name}")
                                    written++
                                } else {
                                    try {
                                        for ((name, part) in newParts) {
                                            if (!out.put(name, part)) throw IOException("Duplicate entry $name")
                                            created.add(name)
                                            written++
                                        }
                                    } finally {
                                        newParts.forEach { it.second.delete() }
                                    }
                                    split++
                                    parts += newParts.size
                                    changedEntries++
                                }
                            }

                            Kind.CONVERT -> {
                                val name = webpName(e.name)
                                val bytes = if (name in taken) {
                                    null // would clash with an existing page
                                } else {
                                    val quality = groupQuality.getOrPut(p.group) { sampleQuality(zip, planned, p.group, workDir) }
                                    convertEntry(zip, p, quality, options, workDir)
                                }
                                // AVIF / JXL are far smaller than WebP, but WebP decodes much faster and in regions, so a bigger result is
                                // accepted for them; other formats must shrink.
                                val mayGrow = p.info.format == ImageFormat.AVIF || p.info.format == ImageFormat.JXL
                                if (bytes == null || (!mayGrow && bytes.size.toLong() >= e.size)) {
                                    kept++
                                    if (!out.copyEntryFrom(zip, e)) throw IOException("Duplicate entry ${e.name}")
                                } else {
                                    taken.add(name)
                                    if (!out.put(name, bytes)) throw IOException("Duplicate entry $name")
                                    created.add(name)
                                    converted++
                                    changedEntries++
                                }
                                written++
                            }

                            else -> {
                                if (!out.copyEntryFrom(zip, e)) throw IOException("Duplicate entry ${e.name}")
                                written++
                            }
                        }
                    }
                    if (changedEntries > 0) {
                        indexEntry?.let { ie ->
                            val patched = patchedIndex(zip, ie, options)
                            val ok = if (patched != null) out.put(ie.name, patched) else out.copyEntryFrom(zip, ie)
                            if (!ok) throw IOException("Duplicate entry ${ie.name}")
                            written++
                        }
                        out.finish()
                    }
                }
                if (changedEntries == 0) {
                    return@withContext stats.copy(keptImages = kept)
                }
                ZipFile(tmp).use { check ->
                    if (check.size() != written) throw IOException("Verification failed")
                    for (name in created) {
                        val c = check.getEntry(name)
                        if (c == null || c.size <= 0L) throw IOException("Bad new entry $name")
                    }
                }
                stats = stats.copy(
                    changedArchives = 1,
                    splitImages = split,
                    createdParts = parts,
                    convertedImages = converted,
                    keptImages = kept,
                    bytesBefore = sizeBefore,
                    bytesAfter = tmp.length(),
                )
                replace = true
            }
            if (replace) {
                if (file.length() != sizeBefore || file.lastModified() != modifiedBefore) {
                    throw IOException("Archive changed while optimizing")
                }
                tmp.setLastModified(modifiedBefore)
                if (!tmp.renameTo(file)) throw IOException("Cannot replace ${file.name}")
            }
            stats
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    // ── planning ─────────────────────────────────────────────────────────────

    private fun plan(zip: ZipFile, options: OptimizeOptions): Plan {
        val cover = coverEntryName(zip)
        val canConvert = options.convertToWebp && WebpEncoding.isNativeAvailable
        val items = ArrayList<Planned>()
        var tooLarge = 0
        for (e in zip.entries()) {
            if (e.isDirectory || isTextName(e.name) || e.name == cover) continue
            if (e.size <= 0L || e.size > MAX_ENTRY_BYTES) continue
            val info = probeHead(zip, e)
            if (!info.isKnown) continue
            val kind = when {
                options.splitTall && TallImageSplitter.needsSplit(info) -> Kind.SPLIT
                canConvert && PageConverter.isConvertible(info) -> Kind.CONVERT
                else -> Kind.NONE
            }
            if (kind != Kind.NONE) {
                items.add(Planned(e, info, kind, groupOf(e.name)))
            } else if (canConvert && PageConverter.isConvertibleFormat(info)) {
                tooLarge++
            }
        }
        return Plan(items, tooLarge)
    }

    private fun probeHead(zip: ZipFile, e: ZipEntry): ImageInfo = try {
        val limit = if (e.size in 1 until HEAD_BYTES) e.size.toInt() else HEAD_BYTES
        val buf = ByteArray(limit)
        val n = zip.getInputStream(e).use { readFully(it, buf) }
        if (n < 16) ImageInfo.UNKNOWN else ImageFormatProbe.probe(buf, n, deep = false, truncated = e.size > n)
    } catch (_: IOException) {
        ImageInfo.UNKNOWN
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val k = input.read(buf, n, buf.size - n)
            if (k < 0) break
            n += k
        }
        return n
    }

    /** `00000001_00020003.jpg` and its split parts belong to group `00000001_0002` (one chapter). */
    private fun groupOf(name: String): String = GROUP_REGEX.matchEntire(name.substringAfterLast('/'))?.groupValues?.get(1).orEmpty()

    // ── quality ──────────────────────────────────────────────────────────────

    /**
     * What 1-2 pages from the middle of [group] (at 40% and 60% of its candidates) were saved with: the lowest
     * lossy quality found, else [SourceQuality.Lossless] when every readable sample is lossless, else
     * [SourceQuality.Unknown]. Reads only the head of each sampled page.
     */
    private fun sampleQuality(zip: ZipFile, planned: List<Planned>, group: String, workDir: File): SourceQuality {
        if (!WebpEncoding.isNativeAvailable) return SourceQuality.Unknown
        val candidates = planned.filter {
            it.group == group && (it.info.format == ImageFormat.JPEG || it.info.format == ImageFormat.WEBP || it.info.format == ImageFormat.AVIF)
        }
        if (candidates.isEmpty()) return SourceQuality.Unknown
        val picks = linkedSetOf(
            (candidates.size * 2 / 5).coerceIn(0, candidates.size - 1),
            (candidates.size * 3 / 5).coerceIn(0, candidates.size - 1),
        )
        var lowest: Int? = null
        var lossless = false
        for (index in picks) {
            val sample = File(workDir, "q-" + UUID.randomUUID() + ".bin")
            try {
                val buf = ByteArray(HEAD_BYTES)
                val n = zip.getInputStream(candidates[index].entry).use { readFully(it, buf) }
                FileOutputStream(sample).use { it.write(buf, 0, n) }
                when (val q = WebpEncoding.estimateQuality(sample, candidates[index].info.format)) {
                    is SourceQuality.Lossy -> lowest = minOf(lowest ?: q.quality, q.quality)
                    SourceQuality.Lossless -> lossless = true
                    SourceQuality.Unknown -> Unit
                }
            } catch (_: IOException) {
                // an unreadable sample page just gives no estimate
            } finally {
                sample.delete()
            }
        }
        return lowest?.let { SourceQuality.Lossy(it) } ?: if (lossless) SourceQuality.Lossless else SourceQuality.Unknown
    }

    // ── per-entry work ───────────────────────────────────────────────────────

    /** New `(entry name, part file)` pairs for a tall page, or null to keep the page as it is. */
    private fun splitEntry(
        zip: ZipFile,
        p: Planned,
        group: SourceQuality,
        options: OptimizeOptions,
        workDir: File,
        taken: MutableSet<String>,
    ): List<Pair<String, File>>? {
        val plan = TallImageSplitter.plan(p.info.width, p.info.height) ?: return null
        val base = p.entry.name.substringBeforeLast('.', p.entry.name)
        val names = (1..plan.partCount).map { "${base}_%02d.webp".format(it) }
        if (names.any { it in taken }) return null
        val source = File(workDir, "s-" + UUID.randomUUID() + ".src")
        val parts = ArrayList<File>(plan.partCount)
        try {
            zip.getInputStream(p.entry).use { input -> FileOutputStream(source).use { input.copyTo(it) } }
            val own = if (p.info.format == ImageFormat.JPEG || p.info.format == ImageFormat.WEBP) {
                WebpEncoding.estimateQuality(source)
            } else {
                SourceQuality.Lossless
            }
            val target = targetFor(p.info.format, group, own, options)
            val files = TallImageSplitter.split(source, target) {
                File(workDir, "p-" + UUID.randomUUID() + ".part").also { parts += it }
            } ?: return null
            if (files.size != names.size) {
                files.forEach { it.delete() }
                return null
            }
            taken.addAll(names)
            return names.zip(files)
        } catch (_: IOException) {
            parts.forEach { it.delete() }
            return null
        } finally {
            source.delete()
        }
    }

    /** WebP bytes for a page, or null to keep the page as it is. */
    private fun convertEntry(zip: ZipFile, p: Planned, group: SourceQuality, options: OptimizeOptions, workDir: File): ByteArray? {
        val source = File(workDir, "c-" + UUID.randomUUID() + ".src")
        try {
            zip.getInputStream(p.entry).use { input -> FileOutputStream(source).use { input.copyTo(it) } }
            val target = targetFor(p.info.format, group, SourceQuality.Unknown, options)
            return PageConverter.convert(source, p.info, target)
        } catch (_: IOException) {
            return null
        } finally {
            source.delete()
        }
    }

    /** The group's sampled quality wins; the page's own header estimate fills in when the group has none. */
    private fun targetFor(format: ImageFormat, group: SourceQuality, own: SourceQuality, options: OptimizeOptions): WebpTarget = WebpPolicy.target(options.autoQuality, format, if (group is SourceQuality.Unknown) own else group, options.fallbackQuality)

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun webpName(name: String) = name.substringBeforeLast('.', name) + ".webp"

    /** The index with the "optimized" mark added, or null when it cannot be parsed (it is then copied as is). */
    private fun patchedIndex(zip: ZipFile, entry: ZipEntry, options: OptimizeOptions): String? = try {
        val index = MangaIndex(zip.getInputStream(entry).use { it.reader().readText() })
        index.markOptimized(options.splitTall, options.convertToWebp)
        index.toString()
    } catch (_: Exception) {
        null
    }

    private fun isTextName(name: String): Boolean = name.endsWith(".json", true) || name.endsWith(".txt", true) ||
        name.endsWith(".xml", true) || name.endsWith(".html", true)

    /** The cover is referenced from the index by its exact name, so it is never renamed. */
    private fun coverEntryName(zip: ZipFile): String? {
        val index = zip.getEntry("index.json") ?: return null
        return runCatching {
            MangaIndex(zip.getInputStream(index).use { it.reader().readText() }).getCoverEntry()
        }.getOrNull()
    }
}

package org.koitharu.kotatsu.reader.domain

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.dao.PageStatsDao
import org.koitharu.kotatsu.core.db.entity.PageStatsEntity
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import javax.inject.Provider
import kotlin.math.sqrt

/**
 * What one measuring pass learns about a page (all on a 0..255 scale unless noted):
 * the 0.5 / 5 / 50 / 95 / 99.5 percentiles of luma, the mean and 99th percentile of chroma over the
 * colored pixels (x1000), and the share of colored pixels (permille).
 */
data class PageStats(
    val lo: Int,
    val hi: Int,
    val p5: Int,
    val p95: Int,
    val median: Int,
    val chromaMean: Int,
    val chromaP99: Int,
    val coloredPermille: Int,
)

/**
 * Measures each page once and remembers it: memory LRU in front, Room table `page_stats` behind it, so a
 * page that was ever measured is never decoded again for the auto color filters (toggling any of them only
 * rebuilds a matrix from these numbers). Pages of local archives are keyed by their zip entry's CRC32 + size
 * and kept until the archive is deleted ([forget]); streamed pages are keyed by their cache file and expire
 * after a day ([cleanup]). Measuring runs on its own two low-priority threads, on a pixel-budget
 * thumbnail so even webtoon strips are sampled evenly. Writes are batched.
 */
object PageStatsEngine {

    const val VERSION = 1

    private const val BUDGET_PIXELS = 40_000
    private const val MEMORY_SIZE = 64
    private const val URL_KEYS_SIZE = 256
    private const val FLUSH_DELAY_MS = 1_500L
    private const val STREAMED_KEEP_DAYS = 1L
    private const val STREAMED_KEEP_ROWS = 20_000
    private const val ALPHA_MIN = 128
    private const val MIN_VALUE = 51 // V > 0.2
    private const val MIN_CHROMA = 15 // chroma > 0.06
    private const val FNV_OFFSET = -3750763034362895579L // 0xcbf29ce484222325
    private const val FNV_PRIME = 1099511628211L
    private const val OWNERS_PER_QUERY = 500 // SQLite caps bound variables

    @Volatile
    private var databaseProvider: Provider<MangaDatabase>? = null

    private val memory = LruCache<Long, PageStats>(MEMORY_SIZE)
    private val unreadable = LruCache<Long, Boolean>(MEMORY_SIZE)
    private val urlKeys = LruCache<String, Long>(URL_KEYS_SIZE)
    private val zipKeys = LruCache<String, Pair<Long, Long>>(URL_KEYS_SIZE)
    private val pending = ArrayList<PageStatsEntity>()
    private var flushJob: Job? = null

    private val dispatcher by lazy(LazyThreadSafetyMode.PUBLICATION) {
        Executors.newFixedThreadPool(2) { r ->
            Thread(r, "page-stats").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.asCoroutineDispatcher()
    }
    private val scope by lazy(LazyThreadSafetyMode.PUBLICATION) { CoroutineScope(SupervisorJob() + dispatcher) }

    fun attach(provider: Provider<MangaDatabase>) {
        databaseProvider = provider
    }

    /** Stats of the page at [uri] (stored, cached or freshly measured), or null when it cannot be read. */
    suspend fun get(uri: Uri): PageStats? = withContext(dispatcher) {
        val (key, owner) = identify(uri) ?: return@withContext null
        memory.get(key)?.let { return@withContext it }
        if (unreadable.get(key) != null) return@withContext null
        loadStored(key)?.let {
            memory.put(key, it)
            return@withContext it
        }
        val stats = try {
            measure(uri)
        } catch (_: IOException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        }
        if (stats == null) {
            unreadable.put(key, true)
        } else {
            memory.put(key, stats)
            enqueueWrite(key, owner, stats)
        }
        stats
    }

    /** Stats of [bitmap] (it is only read), measured on a budget-sized copy. Null on any failure. */
    fun fromAnyBitmap(bitmap: Bitmap): PageStats? = try {
        val pixels = bitmap.width.toLong() * bitmap.height
        if (pixels <= BUDGET_PIXELS * 2L) {
            fromBitmap(bitmap)
        } else {
            val factor = sqrt(BUDGET_PIXELS.toDouble() / pixels).toFloat()
            val small = Bitmap.createScaledBitmap(
                bitmap,
                maxOf(1, (bitmap.width * factor).toInt()),
                maxOf(1, (bitmap.height * factor).toInt()),
                true,
            )
            try {
                fromBitmap(small)
            } finally {
                if (small !== bitmap) small.recycle()
            }
        }
    } catch (_: IllegalStateException) {
        null // e.g. a hardware bitmap whose pixels cannot be read
    } catch (_: OutOfMemoryError) {
        null
    }

    /** Housekeeping for streamed pages: drops rows older than a day and keeps at most 20k. */
    suspend fun cleanup(dao: PageStatsDao) {
        dao.deleteStreamedOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(STREAMED_KEEP_DAYS))
        dao.trimStreamed(STREAMED_KEEP_ROWS)
    }

    /**
     * Runs [block], which deletes files under [root] (a manga file or directory), then drops the stats of the
     * archives it removed. Stats never make the deletion fail.
     */
    suspend fun <T> trackDeletion(root: File, block: suspend () -> T): T {
        val before = try {
            ownersUnder(root)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return block()
        }
        val result = block()
        try {
            forget(before - ownersUnder(root))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // derived data: leftovers are removed by the next sweep
        }
        return result
    }

    /** Drops the stats of local archives that no longer exist under any of [roots] (the storage directories). */
    suspend fun sweep(roots: Collection<File>) {
        if (roots.isEmpty()) return
        val dao = databaseProvider?.get()?.getPageStatsDao() ?: return
        val existing = roots.flatMapTo(HashSet()) { ownersUnder(it) }
        forget(dao.owners().filterNot { it in existing })
    }

    /** Remembers which page url a cached file came from, so a re-download of the same page keeps its stats. */
    fun rememberUrl(uri: Uri, url: String) {
        urlKeys.put(uri.toString(), hash(url))
    }

    private suspend fun ownersUnder(root: File): Set<Long> =
        withContext(dispatcher) { root.walkTopDown().filter { it.isFile }.map { hash(it.path) }.toSet() }

    private suspend fun forget(owners: Collection<Long>) {
        val dao = databaseProvider?.get()?.getPageStatsDao() ?: return
        owners.chunked(OWNERS_PER_QUERY).forEach { dao.deleteByOwners(it) }
    }

    /** (page key, owner) of the page at [uri], or null when it has no stable identity. */
    private fun identify(uri: Uri): Pair<Long, Long>? {
        if (!uri.isZipUri()) {
            val stamped = PageEdgeColor.stampedKey(uri) ?: return null
            val url = urlKeys.get(uri.toString())
            return (if (url != null) hash("$url|${File(uri.path.orEmpty()).length()}") else hash(stamped)) to 0L
        }
        val path = uri.schemeSpecificPart
        // Opening the archive parses its whole central directory, so the answer is remembered. The size and
        // modification time in the memo key retire it if the archive is replaced.
        val file = File(path)
        val memoKey = "$uri|${file.length()}|${file.lastModified()}"
        zipKeys.get(memoKey)?.let { return it }
        val entry = try {
            ZipFile(path).use { zip -> uri.fragment?.let(zip::getEntry) }
        } catch (_: IOException) {
            null
        } ?: return null
        val key = if (entry.crc >= 0 && entry.size >= 0) (entry.crc shl 32) or entry.size else hash("$uri|${entry.size}")
        return (key to hash(path)).also { zipKeys.put(memoKey, it) }
    }

    private suspend fun loadStored(key: Long): PageStats? {
        val dao = databaseProvider?.get()?.getPageStatsDao() ?: return null
        return try {
            dao.find(key)?.takeIf { it.version == VERSION }?.let {
                PageStats(it.lo, it.hi, it.p5, it.p95, it.median, it.chromaMean, it.chromaP99, it.colored)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun enqueueWrite(key: Long, owner: Long, s: PageStats) {
        val entity = PageStatsEntity(
            pageKey = key,
            lo = s.lo,
            hi = s.hi,
            p5 = s.p5,
            p95 = s.p95,
            median = s.median,
            chromaMean = s.chromaMean,
            chromaP99 = s.chromaP99,
            colored = s.coloredPermille,
            owner = owner,
            version = VERSION,
            updatedAt = System.currentTimeMillis(),
        )
        synchronized(pending) {
            pending.add(entity)
            if (flushJob?.isActive != true) {
                flushJob = scope.launch { flushLoop() }
            }
        }
    }

    private suspend fun flushLoop() {
        do {
            delay(FLUSH_DELAY_MS)
            val batch = synchronized(pending) {
                val copy = ArrayList(pending)
                pending.clear()
                copy
            }
            if (batch.isNotEmpty()) {
                try {
                    databaseProvider?.get()?.getPageStatsDao()?.upsertAll(batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // derived data: a lost batch is simply measured again
                }
            }
        } while (synchronized(pending) { pending.isNotEmpty() })
    }

    private fun measure(uri: Uri): PageStats? {
        val bitmap = PageEdgeColor.decodePixelBudget(uri, BUDGET_PIXELS) ?: return null
        return try {
            fromBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun fromBitmap(bitmap: Bitmap): PageStats? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luma = IntArray(256)
        val chroma = IntArray(256)
        var count = 0
        var colored = 0
        var chromaSum = 0L
        for (c in pixels) {
            if ((c ushr 24) < ALPHA_MIN) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luma[(r * 54 + g * 183 + b * 19) shr 8]++
            count++
            val mx = maxOf(r, g, b)
            if (mx > MIN_VALUE) {
                val ch = mx - minOf(r, g, b)
                if (ch > MIN_CHROMA) {
                    chroma[ch]++
                    colored++
                    chromaSum += ch
                }
            }
        }
        if (count < 16) return null
        return PageStats(
            lo = percentile(luma, count, 0.005f),
            hi = percentile(luma, count, 0.995f),
            p5 = percentile(luma, count, 0.05f),
            p95 = percentile(luma, count, 0.95f),
            median = percentile(luma, count, 0.5f),
            chromaMean = if (colored > 0) (chromaSum * 1000 / (255L * colored)).toInt() else 0,
            chromaP99 = if (colored > 0) percentile(chroma, colored, 0.99f) * 1000 / 255 else 0,
            coloredPermille = (colored.toLong() * 1000 / count).toInt(),
        )
    }

    /** Smallest bin whose cumulative count reaches [fraction] of [total]. */
    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val need = maxOf(1, (total * fraction).toInt())
        var acc = 0
        for (i in 0..255) {
            acc += histogram[i]
            if (acc >= need) return i
        }
        return 255
    }

    private fun hash(s: String): Long {
        var h = FNV_OFFSET
        for (ch in s) h = (h xor ch.code.toLong()) * FNV_PRIME
        return h
    }
}

package org.koitharu.kotatsu.local.data

import android.content.Context
import android.graphics.Bitmap
import android.os.StatFs
import android.webkit.MimeTypeMap
import com.tomclaw.cache.DiskLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.Source
import okio.buffer
import okio.sink
import okio.use
import org.koitharu.kotatsu.core.exceptions.NoDataReceivedException
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.compressToPNG
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.subdir
import org.koitharu.kotatsu.core.util.ext.takeIfReadable
import org.koitharu.kotatsu.core.util.ext.takeIfWriteable
import org.koitharu.kotatsu.core.util.ext.writeAllCancellable
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.io.File
import java.util.UUID

class LocalStorageCache(
    context: Context,
    private val dir: CacheDir,
    private val defaultSize: Long,
    private val minSize: Long,
) {

    private val cacheDir = suspendLazy {
        val dirs = context.externalCacheDirs + context.cacheDir
        dirs.firstNotNullOf {
            it?.subdir(dir.dir)?.takeIfWriteable()
        }
    }
    private val lruCache = suspendLazy {
        val dir = cacheDir.get()
        val availableSize = (getAvailableSize() * 0.8).toLong()
        val size = defaultSize.coerceAtMost(availableSize).coerceAtLeast(minSize)
        runCatchingCancellable {
            DiskLruCache.create(dir, size)
        }.recoverCatching { error ->
            error.printStackTraceDebug()
            dir.deleteRecursively()
            dir.mkdir()
            DiskLruCache.create(dir, size)
        }.getOrThrow()
    }

    suspend operator fun get(url: String): File? = withContext(Dispatchers.IO) {
        val cache = lruCache.get()
        runInterruptible {
            cache.get(stableKey(url))?.takeIfReadable()
        }
    }

    suspend operator fun set(url: String, source: Source, mimeType: MimeType?): File = withContext(Dispatchers.IO) {
        val file = createBufferFile(url, mimeType)
        try {
            val bytes = file.sink(append = false).buffer().use {
                it.writeAllCancellable(source)
            }
            if (bytes == 0L) {
                throw NoDataReceivedException(url)
            }
            val cache = lruCache.get()
            runInterruptible {
                cache.put(stableKey(url), file)
            }
        } finally {
            file.delete()
        }
    }

    suspend operator fun set(url: String, bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        val file = createBufferFile(url, MimeType("image/png"))
        try {
            bitmap.compressToPNG(file)
            val cache = lruCache.get()
            runInterruptible {
                cache.put(stableKey(url), file)
            }
        } finally {
            file.delete()
        }
    }

    suspend fun clear() {
        val cache = lruCache.get()
        runInterruptible(Dispatchers.IO) {
            cache.clearCache()
        }
    }

    private suspend fun getAvailableSize(): Long = runCatchingCancellable {
        val dir = cacheDir.get()
        runInterruptible(Dispatchers.IO) {
            val statFs = StatFs(dir.absolutePath)
            statFs.availableBytes
        }
    }.onFailure {
        it.printStackTraceDebug()
    }.getOrDefault(defaultSize)

    private suspend fun createBufferFile(url: String, mimeType: MimeType?): File {
        val ext = MimeTypes.getExtension(mimeType) ?: MimeTypeMap.getFileExtensionFromUrl(url).ifNullOrEmpty { "dat" }
        val cacheDir = cacheDir.get()
        val rootDir = checkNotNull(cacheDir.parentFile) { "Cannot get parent for ${cacheDir.absolutePath}" }
        val name = UUID.randomUUID().toString() + "." + ext
        return File(rootDir, name)
    }
}

/** Query parameters that only sign or time-limit a link; the same image comes back whatever their value. */
private val VOLATILE_QUERY_PARAMS = setOf(
    "expires", "signature", "sig", "key-pair-id", "policy",
    "x-amz-signature", "x-amz-credential", "x-amz-date", "x-amz-expires", "x-amz-security-token",
    "x-amz-signedheaders", "x-goog-signature", "x-goog-credential", "x-goog-date", "x-goog-expires",
)

/**
 * The cache key of [url] without its signing parameters, so a signed link (new signature on every
 * request) still finds the file saved from an earlier one. Links without a query are returned as they are.
 */
internal fun stableKey(url: String): String {
    val q = url.indexOf('?')
    if (q < 0) return url
    val hash = url.indexOf('#', q).let { if (it < 0) url.length else it }
    val kept = url.substring(q + 1, hash).split('&').filter { part ->
        part.isNotEmpty() && part.substringBefore('=').lowercase() !in VOLATILE_QUERY_PARAMS
    }
    if (kept.size == url.substring(q + 1, hash).count { it == '&' } + 1) return url // nothing removed
    val base = url.substring(0, q)
    val tail = url.substring(hash)
    return if (kept.isEmpty()) base + tail else base + '?' + kept.joinToString("&") + tail
}

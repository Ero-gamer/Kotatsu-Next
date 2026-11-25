package org.koitharu.kotatsu.reader.domain

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.annotation.AnyThread
import androidx.annotation.CheckResult
import androidx.collection.LongSparseArray
import androidx.collection.set
import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.BitmapImage
import coil3.Image
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.toBitmap
import com.davemorrissey.labs.subscaleview.ImageSource
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.use
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.image.ImageFormatProbe
import org.koitharu.kotatsu.core.image.ImageInfo
import org.koitharu.kotatsu.core.image.ImageInspector
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.image.TrimTransformation
import org.koitharu.kotatsu.core.util.FileSize
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.core.util.ext.cancelChildrenAndJoin
import org.koitharu.kotatsu.core.util.ext.ensureRamAtLeast
import org.koitharu.kotatsu.core.util.ext.ensureSuccess
import org.koitharu.kotatsu.core.util.ext.getCompletionResultOrNull
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isNotEmpty
import org.koitharu.kotatsu.core.util.ext.isPowerSaveMode
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.core.util.ext.lifecycleScope
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.ramAvailable
import org.koitharu.kotatsu.core.util.ext.toMimeType
import org.koitharu.kotatsu.core.util.ext.use
import org.koitharu.kotatsu.core.util.ext.withProgress
import org.koitharu.kotatsu.core.util.progress.ProgressDeferred
import org.koitharu.kotatsu.download.ui.worker.DownloadSlowdownDispatcher
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.requireBody
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.erase.ImageHider
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@ActivityRetainedScoped
class PageLoader
@Inject
constructor(
    @LocalizedAppContext private val context: Context,
    lifecycle: ActivityRetainedLifecycle,
    @MangaHttpClient private val okHttp: OkHttpClient,
    @PageCache private val cache: LocalStorageCache,
    private val coil: ImageLoader,
    private val settings: AppSettings,
    private val mangaRepositoryFactory: MangaRepository.Factory,
    private val imageProxyInterceptor: ImageProxyInterceptor,
    private val downloadSlowdownDispatcher: DownloadSlowdownDispatcher,
    private val networkState: NetworkState,
    private val preloader: PagePreloader,
    val imageHider: ImageHider,
) {
    val loaderScope = lifecycle.lifecycleScope + InternalErrorHandler() + Dispatchers.Default

    private val tasks = LongSparseArray<ProgressDeferred<Uri, Float>>()

    // Resolved full-image urls: a retry-free reload, a save or a re-bind after memory trim must not ask the
    // source again. A forced reload bypasses it (urls may be signed and expire).
    private val pageUrls = LruCache<Long, String>(PAGE_URL_CACHE_SIZE)

    // BUG 1 / PERF FIX: increased from 3 to 5 concurrent page downloads.
    // With 3 permits, rapid chapter switches could saturate all permits with prefetch
    // jobs, causing the user-triggered load to wait. 5 permits maintain throughput
    // while still protecting against runaway parallel requests on slow connections.
    // Dynamic concurrency: fewer parallel transfers (each buffers and decodes) on small devices, and
    // background prefetch can never take every permit, so a page the user is waiting for always has one.
    private val isConstrained = settings.isMemorySaverEnabled
    // The page on screen goes first; what is only ahead never takes every slot (and fewer while one is waited for).
    private val slots = DownloadSlots(
        maxOnScreen = if (isConstrained) PERMITS_CONSTRAINED else PERMITS_DEFAULT,
        maxAhead = if (isConstrained) PREFETCH_PERMITS_CONSTRAINED else PREFETCH_PERMITS_DEFAULT,
    )

    /** How many views each page is in. A download only stops when nothing wants it any more. */
    private val boundPages = LongSparseArray<Int>()

    /** What was started ahead of time, and is no use once it is no longer ahead. */
    private val prefetching = HashSet<Long>()

    // BUG 1 FIX: replaced single shared Mutex with a per-URI deduplication map.
    // The original `convertLock = Mutex()` serialized ALL bitmap conversions globally:
    // if page 1 was converting (WEBP→PNG, could take 2-3 seconds), page 2 would wait
    // even though they're completely independent operations.
    // ConcurrentHashMap<uriKey, Deferred<Uri>> deduplicates only same-URI calls
    // (preventing double-conversion of the same file) while allowing different
    // pages to convert concurrently.
    private val conversionJobs = ConcurrentHashMap<String, Deferred<Uri>>()

    private val prefetchLock = Mutex()

    @Volatile
    private var repository: MangaRepository? = null
    private val prefetchQueue = ArrayDeque<MangaPage>()
    private val counter = AtomicInteger(0)

    // On constrained devices (≤2GB RAM) cap prefetch to 2 pages and require
    // ≥200MB free RAM instead of the default 6 pages / 80MB. This prevents
    // prefetch from filling RAM just before a high-res tile decode burst.
    // The window itself comes from preloadPlan() (reading speed, connection, memory saver); this is a hard stop.
    private val prefetchQueueLimit = PREFETCH_QUEUE_HARD_LIMIT

    private val readingSpeed = ReadingSpeedTracker()

    /** Typical shape of the pages of this strip, for the placeholder of a webtoon page still on its way. */
    val pageRatios = PageRatioTracker()

    /** Prefetched pages that failed, with the time they may be queued again (a failing page is not hammered). */
    private val failedUntil = ConcurrentHashMap<Long, Long>()

    /** The page [pageId] is now the one being read: feeds the reading-speed estimate behind [preloadPlan]. */
    fun onPageShown(pageId: Long) = readingSpeed.onPage(pageId, SystemClock.elapsedRealtime())

    /** How many pages ahead of and behind the current one to prefetch right now. */
    fun preloadPlan(): PreloadPlan = PreloadPlanner.plan(
        avgGapMs = readingSpeed.averageGapMs(),
        metered = networkState.isMetered(),
        constrained = isConstrained,
    )
    private val edgeDetector = EdgeDetector(context) { settings.cropBordersTolerance }

    /** True while an auto color filter is on: prefetched pages are then measured as they arrive. */
    @Volatile
    var measureAutoColor: Boolean = false

    // Under memory pressure stop queueing speculative downloads; they are re-requested on demand.
    private val trimCallbacks =
        object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    loaderScope.launch { prefetchLock.withLock { prefetchQueue.clear() } }
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        }

    init {
        context.registerComponentCallbacks(trimCallbacks)
        lifecycle.addOnClearedListener { context.unregisterComponentCallbacks(trimCallbacks) }
    }

    @Volatile
    private var ramSampleAt = 0L

    @Volatile
    private var ramSample = 0L

    /** ActivityManager.getMemoryInfo is a binder call: sampled at most every [RAM_SAMPLE_MS]. */
    private fun sampledRamAvailable(): Long {
        val now = SystemClock.elapsedRealtime()
        if (now - ramSampleAt > RAM_SAMPLE_MS) {
            ramSample = context.ramAvailable
            ramSampleAt = now
        }
        return ramSample
    }

    fun isPrefetchApplicable(): Boolean {
        val minRamMb = if (isConstrained) PREFETCH_MIN_RAM_MB_CONSTRAINED else PREFETCH_MIN_RAM_MB
        return repository is CachingMangaRepository &&
            settings.isPagesPreloadEnabled &&
            !context.isPowerSaveMode() &&
            !networkState.isRestricted() &&
            sampledRamAvailable() > FileSize.MEGABYTES.convert(minRamMb, FileSize.BYTES)
    }

    /** The page on screen goes first for everything: its download, then its check. */
    fun setOnScreen(pageId: Long, isOnScreen: Boolean) {
        slots.setOnScreen(pageId, isOnScreen)
        imageHider.setOnScreen(pageId, isOnScreen)
    }

    fun onPageBound(pageId: Long) {
        synchronized(tasks) {
            boundPages[pageId] = (boundPages[pageId] ?: 0) + 1
        }
    }

    /**
     * Nothing shows this page now. Still on its way, it is holding up pages someone is on their way to, and the bytes
     * are for nothing unless they come back, when it starts again. What has arrived is kept on disk either way.
     */
    fun onPageUnbound(pageId: Long) {
        synchronized(tasks) {
            val count = (boundPages[pageId] ?: 0) - 1
            if (count > 0) {
                boundPages[pageId] = count
            } else {
                boundPages.remove(pageId)
                cancelIfUnfinished(pageId)
            }
        }
    }

    // Caller holds the tasks lock
    private fun cancelIfUnfinished(pageId: Long) {
        val task = tasks[pageId] ?: return
        if (!task.isCompleted) {
            task.cancel()
            tasks.remove(pageId)
        }
    }

    @AnyThread
    fun prefetch(pages: List<ReaderPage>) = loaderScope.launch {
        prefetchLock.withLock {
            // Only what is ahead from here: whoever jumps elsewhere has no use for what was ahead of where they were
            val wanted = pages.mapTo(HashSet()) { it.id }
            prefetchQueue.removeAll { it.id !in wanted }
            synchronized(tasks) {
                val iterator = prefetching.iterator()
                while (iterator.hasNext()) {
                    val id = iterator.next()
                    val task = tasks[id]
                    if (task == null || task.isCompleted) {
                        iterator.remove()
                    } else if (id !in wanted && boundPages[id] == null) {
                        iterator.remove()
                        cancelIfUnfinished(id)
                    }
                }
            }
            for (page in pages.asReversed()) {
                if (synchronized(tasks) { tasks.containsKey(page.id) } || isRecentlyFailed(page.id)) {
                    continue
                }
                prefetchQueue.offerFirst(page.toMangaPage())
                if (prefetchQueue.size > prefetchQueueLimit) {
                    prefetchQueue.pollLast()
                }
            }
        }
        if (counter.get() == 0) {
            onIdle()
        }
    }

    private fun isRecentlyFailed(pageId: Long): Boolean {
        val until = failedUntil[pageId] ?: return false
        if (SystemClock.elapsedRealtime() < until) return true
        failedUntil.remove(pageId, until)
        return false
    }

    suspend fun loadPreview(page: MangaPage): ImageSource? {
        val preview = page.preview
        if (preview.isNullOrEmpty()) {
            return null
        }
        val request =
            ImageRequest
                .Builder(context)
                .data(preview)
                .mangaSourceExtra(page.source)
                .transformations(TrimTransformation())
                .build()
        return coil.execute(request).image?.toImageSource()
    }

    fun loadPageAsync(
        page: MangaPage,
        force: Boolean,
    ): ProgressDeferred<Uri, Float> {
        failedUntil.remove(page.id) // the user asked for it: always try
        var task = synchronized(tasks) { tasks[page.id] }?.takeIf { it.isValid() }
        if (force) {
            task?.cancel()
        } else if (task?.isCancelled == false) {
            return task
        }
        task = loadPageAsyncImpl(page, skipCache = force, isPrefetch = false)
        synchronized(tasks) {
            tasks[page.id] = task
        }
        return task
    }

    suspend fun loadPage(
        page: MangaPage,
        force: Boolean,
    ): Uri {
        var isForced = force
        while (true) {
            try {
                return loadPageAsync(page, isForced).await()
            } catch (e: CancellationException) {
                // The download was given up on by a view of it going away, not this caller
                currentCoroutineContext().ensureActive()
                isForced = false
            }
        }
    }

    // BUG 1 FIX: renamed from convertBimap (typo) to convertBitmap.
    // Also switched from a global Mutex to a per-URI ConcurrentHashMap deduplication
    // so that different pages can convert concurrently.
    @CheckResult
    suspend fun convertBitmap(uri: Uri): Uri {
        val key = uri.toString()
        // If an identical conversion is already in-flight (e.g., two holders requesting
        // the same page), reuse the existing Deferred instead of starting a second one.
        // The job is created lazily and published atomically (putIfAbsent), so concurrent callers can
        // never start two conversions of the same file; the loser's job is never started.
        val candidate = loaderScope.async(start = CoroutineStart.LAZY) { doConvertBitmap(uri) }
        val existing = conversionJobs.putIfAbsent(key, candidate)
        if (existing != null) {
            candidate.cancel()
            return existing.await()
        }
        candidate.invokeOnCompletion { conversionJobs.remove(key, candidate) }
        return candidate.await()
    }

    // BUG 1 FIX: kept the old name as a deprecated alias so that any callers using the
    // original typo still compile without changes. They should migrate to convertBitmap.
    @Deprecated("Typo in original name — use convertBitmap()", ReplaceWith("convertBitmap(uri)"))
    @CheckResult
    suspend fun convertBimap(uri: Uri): Uri = convertBitmap(uri)

    /** A decode that runs out of memory becomes a normal page error instead of killing the process. */
    private suspend fun doConvertBitmap(uri: Uri): Uri = try {
        convertBitmapUnguarded(uri)
    } catch (e: OutOfMemoryError) {
        throw IOException("Not enough memory to convert the page", e)
    }

    private suspend fun convertBitmapUnguarded(uri: Uri): Uri = if (uri.isZipUri()) {
        runInterruptible(Dispatchers.IO) {
            ZipFile(uri.schemeSpecificPart).use { zip ->
                val entry = zip.getEntry(uri.fragment)
                val head = ByteArray(HEAD_PROBE_BYTES)
                val headRead = zip.getInputStream(entry).use { ImageInspector.readHead(it, head) }
                context.ensureRamAtLeast(
                    decodedBytes(ImageFormatProbe.probe(head, headRead, truncated = true), entry.size),
                )
                zip.getInputStream(entry).use {
                    BitmapDecoderCompat.decode(it, MimeTypes.getMimeTypeFromExtension(entry.name))
                }
            }
        }.use { image ->
            cache.set(uri.toString(), image).toUri()
        }
    } else {
        // The converted copy goes to its own cache entry: the original stays byte-for-byte what the server sent
        // (saving a page must give that file, not a bigger re-encoded PNG) and a downloaded loose file is never
        // rewritten in place.
        val file = uri.toFile()
        val key = CONVERTED_KEY_PREFIX + uri
        cache[key]?.let { return it.toUri() }
        runInterruptible(Dispatchers.IO) {
            context.ensureRamAtLeast(decodedBytes(ImageFormatProbe.probe(file), file.length()))
            BitmapDecoderCompat.decode(file)
        }.use { image ->
            cache.set(key, image).toUri()
        }
    }

    /** Pixels * 4 from the header when known, else twice the compressed size. */
    private fun decodedBytes(info: ImageInfo, compressedSize: Long): Long =
        if (info.width > 0 && info.height > 0) info.width.toLong() * info.height * 4 else compressedSize * 2

    suspend fun getTrimmedBounds(uri: Uri): Rect? = runCatchingCancellable {
        edgeDetector.getBounds(ImageSource.uri(uri))
    }.onFailure { error ->
        error.printStackTraceDebug()
    }.getOrNull()

    suspend fun getPageUrl(page: MangaPage, refresh: Boolean = false): String {
        if (!refresh) pageUrls.get(page.id)?.let { return it }
        val url = getRepository(page.source).getPageUrl(page)
        if (url.isNotBlank()) pageUrls.put(page.id, url)
        return url
    }

    suspend fun invalidate(clearCache: Boolean) {
        synchronized(tasks) {
            tasks.clear()
            prefetching.clear()
        }
        failedUntil.clear()
        pageUrls.evictAll()
        prefetchLock.withLock { prefetchQueue.clear() }
        loaderScope.cancelChildrenAndJoin()
        if (clearCache) {
            cache.clear()
        }
    }

    private fun onIdle() = loaderScope.launch {
        prefetchLock.withLock {
            while (prefetchQueue.isNotEmpty()) {
                val page = prefetchQueue.pollFirst() ?: return@launch
                synchronized(tasks) {
                    // A task (user-initiated or already prefetched) must not be replaced and orphaned.
                    if (tasks[page.id]?.isValid() != true) {
                        tasks[page.id] = loadPageAsyncImpl(page, skipCache = false, isPrefetch = true)
                        prefetching += page.id
                    }
                }
            }
        }
    }

    private fun loadPageAsyncImpl(
        page: MangaPage,
        skipCache: Boolean,
        isPrefetch: Boolean,
    ): ProgressDeferred<Uri, Float> {
        val progress = MutableStateFlow(PROGRESS_UNDEFINED)
        val deferred =
            loaderScope.async {
                counter.incrementAndGet()
                try {
                    val load =
                        suspend {
                            loadPageImpl(
                                page = page,
                                progress = progress,
                                isPrefetch = isPrefetch,
                                skipCache = skipCache,
                            )
                        }
                    val uri = load()
                    // Not part of this task: the reader waits on this task when it reaches the page, and looking pages
                    // over ahead of time goes one at a time. A page already downloaded would sit blank behind the rest.
                    if (isPrefetch) loaderScope.launch { imageHider.checkAhead(page, uri) }
                    if (isPrefetch && measureAutoColor && !isConstrained) {
                        // Measure the page's auto-color stats now (low priority, own threads), so it
                        // is already filtered when it is shown.
                        loaderScope.launch { PageStatsEngine.get(uri) }
                    }
                    failedUntil.remove(page.id)
                    uri
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isPrefetch) failedUntil[page.id] = SystemClock.elapsedRealtime() + FAILURE_MEMO_MS
                    throw e
                } finally {
                    if (counter.decrementAndGet() == 0) {
                        onIdle()
                    }
                }
            }
        return ProgressDeferred(deferred, progress)
    }

    @Synchronized
    private fun getRepository(source: MangaSource): MangaRepository {
        val result = repository
        return if (result != null && result.source == source) {
            result
        } else {
            mangaRepositoryFactory.create(source).also { repository = it }
        }
    }

    private suspend fun loadPageImpl(
        page: MangaPage,
        progress: MutableStateFlow<Float>,
        isPrefetch: Boolean,
        skipCache: Boolean,
    ): Uri {
        // Waiting for the page preloader (up to 10 s) and the cache check must not hold a download slot: with
        // few slots a couple of such waits would starve every other page. A known page url is handled here;
        // only a url that still has to be resolved is checked inside the slot.
        var checkedUrl: String? = null
        if (!skipCache) {
            pageUrls.get(page.id)?.let { known ->
                checkedUrl = known
                preloader.awaitDownload(known)
                cache.get(known)?.let { return it.toUri().also { u -> PageStatsEngine.rememberUrl(u, known) } }
            }
        }
        return slots.withSlot(page.id) {
            val pageUrl = getPageUrl(page, refresh = skipCache)
            check(pageUrl.isNotBlank()) { "Cannot obtain full image url for $page" }
            if (!skipCache && pageUrl != checkedUrl) {
                // An image the page preloader is still fetching is waited for, rather than fetched twice
                preloader.awaitDownload(pageUrl)
                cache.get(pageUrl)?.let { return@withSlot it.toUri().also { u -> PageStatsEngine.rememberUrl(u, pageUrl) } }
            }
            val uri = pageUrl.toUri()
            when {
                uri.isZipUri() -> {
                    if (uri.scheme == URI_SCHEME_ZIP) {
                        uri
                    } else { // legacy uri
                        uri.buildUpon().scheme(URI_SCHEME_ZIP).build()
                    }
                }

                uri.isFileUri() -> {
                    uri
                }

                else -> {
                    if (isPrefetch) {
                        downloadSlowdownDispatcher.delay(page.source)
                    }
                    val request = createPageRequest(pageUrl, page.source)
                    imageProxyInterceptor
                        .interceptPageRequest(request, okHttp)
                        .ensureSuccess()
                        .use { response ->
                            response.requireBody().withProgress(progress).use {
                                cache.set(pageUrl, it.source(), it.contentType()?.toMimeType())
                            }
                        }.toUri().also { PageStatsEngine.rememberUrl(it, pageUrl) }
                }
            }
        }
    }

    private fun Image.toImageSource(): ImageSource = if (this is BitmapImage) {
        ImageSource.cachedBitmap(toBitmap())
    } else {
        ImageSource.bitmap(toBitmap())
    }

    private fun Deferred<Uri>.isValid(): Boolean = getCompletionResultOrNull()
        ?.map { uri ->
            uri.exists() && uri.isTargetNotEmpty()
        }?.getOrDefault(false) != false

    private class InternalErrorHandler :
        AbstractCoroutineContextElement(CoroutineExceptionHandler),
        CoroutineExceptionHandler {
        override fun handleException(
            context: CoroutineContext,
            exception: Throwable,
        ) {
            exception.printStackTraceDebug()
        }
    }

    companion object {
        private const val PROGRESS_UNDEFINED = -1f
        private const val CONVERTED_KEY_PREFIX = "converted:"
        private const val HEAD_PROBE_BYTES = 64 * 1024
        private const val PAGE_URL_CACHE_SIZE = 512
        private const val PERMITS_DEFAULT = 5
        private const val PERMITS_CONSTRAINED = 3
        private const val PREFETCH_PERMITS_DEFAULT = 3
        private const val PREFETCH_PERMITS_CONSTRAINED = 2
        private const val PREFETCH_QUEUE_HARD_LIMIT = 10
        private const val FAILURE_MEMO_MS = 2 * 60 * 1000L
        private const val PREFETCH_MIN_RAM_MB = 80L
        private const val RAM_SAMPLE_MS = 2_000L
        private const val PREFETCH_MIN_RAM_MB_CONSTRAINED = 200L

        fun createPageRequest(
            pageUrl: String,
            mangaSource: MangaSource,
        ) = Request
            .Builder()
            .url(pageUrl)
            .get()
            .header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
            .cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
            .tag(MangaSource::class.java, mangaSource)
            .build()

        @Blocking
        private fun Uri.exists(): Boolean = when {
            isFileUri() -> {
                toFile().exists()
            }

            isZipUri() -> {
                val file = File(requireNotNull(schemeSpecificPart))
                file.exists() && ZipFile(file).use { it.getEntry(fragment) != null }
            }

            else -> {
                false
            }
        }

        @Blocking
        private fun Uri.isTargetNotEmpty(): Boolean = when {
            isFileUri() -> {
                toFile().isNotEmpty()
            }

            isZipUri() -> {
                val file = File(requireNotNull(schemeSpecificPart))
                file.exists() && ZipFile(file).use { (it.getEntry(fragment)?.size ?: 0L) != 0L }
            }

            else -> {
                false
            }
        }
    }
}

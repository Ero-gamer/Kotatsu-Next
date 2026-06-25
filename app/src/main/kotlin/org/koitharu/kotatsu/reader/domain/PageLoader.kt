package org.koitharu.kotatsu.reader.domain

import android.content.Context
import android.graphics.Rect
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.CheckResult
import androidx.collection.LongSparseArray
import androidx.collection.set
import androidx.core.net.toFile
import androidx.core.net.toUri
import coil3.BitmapImage
import coil3.Image
import coil3.ImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.toBitmap
import com.davemorrissey.labs.subscaleview.ImageSource
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.use
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.network.CommonHeaders
import org.koitharu.kotatsu.core.network.MangaHttpClient
import org.koitharu.kotatsu.core.network.imageproxy.ImageProxyInterceptor
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.image.TrimTransformation
import org.koitharu.kotatsu.core.util.FileSize
import android.graphics.Bitmap
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.URI_SCHEME_ZIP
import org.koitharu.kotatsu.core.util.ext.cancelChildrenAndJoin
import org.koitharu.kotatsu.core.util.ext.compressToPNG
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
import org.koitharu.kotatsu.core.ui.image.ImageFiltersTransformation
import org.koitharu.kotatsu.local.data.LocalStorageCache
import org.koitharu.kotatsu.local.data.PageCache
import org.koitharu.kotatsu.local.data.ProcessedPageCache
import org.koitharu.kotatsu.parsers.util.md5
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.requireBody
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import java.io.File
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

@ActivityRetainedScoped
class PageLoader @Inject constructor(
	@LocalizedAppContext private val context: Context,
	lifecycle: ActivityRetainedLifecycle,
	@MangaHttpClient private val okHttp: OkHttpClient,
	@PageCache private val cache: LocalStorageCache,
	@ProcessedPageCache private val processedCache: LocalStorageCache,
	private val coil: ImageLoader,
	private val settings: AppSettings,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val imageProxyInterceptor: ImageProxyInterceptor,
	private val downloadSlowdownDispatcher: DownloadSlowdownDispatcher,
) {

	val loaderScope = lifecycle.lifecycleScope + InternalErrorHandler() + Dispatchers.Default

	private val tasks = LongSparseArray<ProgressDeferred<Uri, Float>>()

	// BUG 1 / PERF FIX: increased from 3 to 5 concurrent page downloads.
	// With 3 permits, rapid chapter switches could saturate all permits with prefetch
	// jobs, causing the user-triggered load to wait. 5 permits maintain throughput
	// while still protecting against runaway parallel requests on slow connections.
	private val semaphore = Semaphore(5)

	// BUG 1 FIX: replaced single shared Mutex with a per-URI deduplication map.
	// The original `convertLock = Mutex()` serialized ALL bitmap conversions globally:
	// if page 1 was converting (WEBP→PNG, could take 2-3 seconds), page 2 would wait
	// even though they're completely independent operations.
	// ConcurrentHashMap<uriKey, Deferred<Uri>> deduplicates only same-URI calls
	// (preventing double-conversion of the same file) while allowing different
	// pages to convert concurrently.
	private val conversionJobs = ConcurrentHashMap<String, Deferred<Uri>>()

	private val processingLocks = ConcurrentHashMap<String, Mutex>()

	private val prefetchLock = Mutex()

	@Volatile
	private var repository: MangaRepository? = null
	private val prefetchQueue = LinkedList<MangaPage>()
	private val counter = AtomicInteger(0)
	private var prefetchQueueLimit = PREFETCH_LIMIT_DEFAULT // TODO adaptive
	private val edgeDetector = EdgeDetector(context)

	fun isPrefetchApplicable(): Boolean {
		return repository is CachingMangaRepository
			&& settings.isPagesPreloadEnabled
			&& !context.isPowerSaveMode()
			&& !isLowRam()
	}

	@AnyThread
	fun prefetch(pages: List<ReaderPage>) = loaderScope.launch {
		prefetchLock.withLock {
			for (page in pages.asReversed()) {
				if (tasks.containsKey(page.id)) {
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

	suspend fun loadPreview(page: MangaPage): ImageSource? {
		val preview = page.preview
		if (preview.isNullOrEmpty()) {
			return null
		}
		val request = ImageRequest.Builder(context)
			.data(preview)
			.mangaSourceExtra(page.source)
			.transformations(TrimTransformation())
			.build()
		return coil.execute(request).image?.toImageSource()
	}

	fun peekPreviewSource(preview: String?): ImageSource? {
		if (preview.isNullOrEmpty()) {
			return null
		}
		coil.memoryCache?.let { cache ->
			val key = MemoryCache.Key(preview)
			cache[key]?.image?.let {
				return if (it is BitmapImage) {
					ImageSource.cachedBitmap(it.toBitmap())
				} else {
					ImageSource.bitmap(it.toBitmap())
				}
			}
		}
		coil.diskCache?.let { cache ->
			cache.openSnapshot(preview)?.use { snapshot ->
				return ImageSource.file(snapshot.data.toFile())
			}
		}
		return null
	}

	fun loadPageAsync(page: MangaPage, force: Boolean): ProgressDeferred<Uri, Float> {
		var task = tasks[page.id]?.takeIf { it.isValid() }
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

	suspend fun loadPage(page: MangaPage, force: Boolean): Uri {
		return loadPageAsync(page, force).await()
	}

	// BUG 1 FIX: renamed from convertBimap (typo) to convertBitmap.
	// Also switched from a global Mutex to a per-URI ConcurrentHashMap deduplication
	// so that different pages can convert concurrently.
	@CheckResult
	suspend fun convertBitmap(uri: Uri): Uri {
		val key = uri.toString()
		// If an identical conversion is already in-flight (e.g., two holders requesting
		// the same page), reuse the existing Deferred instead of starting a second one.
		val existing = conversionJobs[key]
		if (existing != null && !existing.isCompleted && !existing.isCancelled) {
			return existing.await()
		}
		val deferred = loaderScope.async {
			try {
				doConvertBitmap(uri)
			} finally {
				conversionJobs.remove(key)
			}
		}
		conversionJobs[key] = deferred
		return deferred.await()
	}

	// BUG 1 FIX: kept the old name as a deprecated alias so that any callers using the
	// original typo still compile without changes. They should migrate to convertBitmap.
	@Deprecated("Typo in original name — use convertBitmap()", ReplaceWith("convertBitmap(uri)"))
	@CheckResult
	suspend fun convertBimap(uri: Uri): Uri = convertBitmap(uri)

	private suspend fun doConvertBitmap(uri: Uri): Uri {
		return if (uri.isZipUri()) {
			runInterruptible(Dispatchers.IO) {
				ZipFile(uri.schemeSpecificPart).use { zip ->
					val entry = zip.getEntry(uri.fragment)
					context.ensureRamAtLeast(entry.size * 2)
					zip.getInputStream(entry).use {
						BitmapDecoderCompat.decode(it, MimeTypes.getMimeTypeFromExtension(entry.name))
					}
				}
			}.use { image ->
				cache.set(uri.toString(), image).toUri()
			}
		} else {
			val file = uri.toFile()
			runInterruptible(Dispatchers.IO) {
				context.ensureRamAtLeast(file.length() * 2)
				BitmapDecoderCompat.decode(file)
			}.use { image ->
				image.compressToPNG(file)
			}
			uri
		}
	}

	suspend fun getTrimmedBounds(uri: Uri): Rect? = runCatchingCancellable {
		edgeDetector.getBounds(ImageSource.uri(uri))
	}.onFailure { error ->
		error.printStackTraceDebug()
	}.getOrNull()

	/**
	 * Applies sharpening and/or vibrance to a page bitmap and caches the result to [processedCache].
	 * If the processed version is already cached, returns its URI immediately.
	 *
	 * Supports both plain file:// URIs (online/downloaded pages) and
	 * file+zip:// / cbz:// URIs (local archives). Non-file/non-zip URIs (e.g. http://)
	 * are returned unchanged — these filters only apply to already-cached local files.
	 *
	 * RAM guard: if available RAM is less than 10× the compressed file size (a conservative
	 * upper bound covering decode + ARGB_8888 copy + GPU sharpened output + the extra pixel
	 * buffer vibrance's bulk getPixels()/setPixels() pass needs), processing is skipped and
	 * the original URI is returned to prevent OOM on low-end devices.
	 *
	 * Contrast, brightness and saturation remain real-time ColorMatrix operations on the SSIV
	 * paint (see [ReaderColorFilter.toColorFilter]) — no bitmap processing needed there.
	 * Vibrance, unlike those, MUST be a bitmap pass — see VibranceProcessor's doc comment.
	 */
	suspend fun applyImageFilters(uri: Uri, sharpening: Float, vibrance: Float = 0f): Uri {
		if (sharpening <= 0.01f && vibrance == 0f) return uri
		// Only process local files — skip network URIs entirely
		if (!uri.isFileUri() && !uri.isZipUri()) return uri

		val cacheKey = "${uri}_s${sharpening}_v${vibrance}".md5()
		// computeIfAbsent is intentional: concurrent callers for the same key share one Mutex,
		// preventing redundant GPU work. The entry is removed in the finally block below.
		val lock = processingLocks.computeIfAbsent(cacheKey) { Mutex() }

		return try {
			lock.withLock {
				// Validate the cached file is non-empty — a crash mid-write leaves a zero-byte
				// or partial PNG that passes takeIfReadable() but cannot be decoded by SSIV.
				processedCache.get(cacheKey)?.takeIf { it.length() > 0L }?.let {
					return@withLock it.toUri()
				}

				withContext(Dispatchers.IO) {
					val bitmap = runCatchingCancellable {
						if (uri.isZipUri()) {
							ZipFile(uri.schemeSpecificPart).use { zip ->
								val entry = zip.getEntry(uri.fragment)
									?: error("Zip entry not found: ${uri.fragment}")
								// Guard: skip if size unknown/zero to avoid bypass of RAM check.
								// Prevent overflow: coerce before multiplying.
								val entrySize = entry.size
								if (entrySize <= 0L) return@withContext
								context.ensureRamAtLeast(entrySize.coerceAtMost(Long.MAX_VALUE / 10L) * 10L)
								zip.getInputStream(entry).use { stream ->
									BitmapDecoderCompat.decode(
										stream,
										MimeTypes.getMimeTypeFromExtension(entry.name),
										isMutable = true,
									)
								}
							}
						} else {
							val file = uri.toFile()
							// Guard: skip if file size unknown/zero (e.g. special file, not yet written).
							val fileSize = file.length()
							if (fileSize <= 0L) return@withContext
							context.ensureRamAtLeast(fileSize.coerceAtMost(Long.MAX_VALUE / 10L) * 10L)
							BitmapDecoderCompat.decode(file, isMutable = true)
						}
					}.getOrNull() ?: return@withContext

					val filtered = ImageFiltersTransformation(sharpening, vibrance).transform(
						bitmap,
						Size.ORIGINAL,
					)
					if (filtered !== bitmap) bitmap.recycle()
					processedCache.set(cacheKey, filtered)
					filtered.recycle()
				}

				processedCache.get(cacheKey)?.toUri() ?: uri
			}
		} finally {
			// Remove only if this exact Mutex instance is still the one registered,
			// so a concurrent caller that raced in with a new lock is not evicted.
			processingLocks.remove(cacheKey, lock)
		}
	}

	suspend fun getPageUrl(page: MangaPage): String {
		return getRepository(page.source).getPageUrl(page)
	}

	suspend fun invalidate(clearCache: Boolean) {
		tasks.clear()
		processingLocks.clear()
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
					tasks[page.id] = loadPageAsyncImpl(page, skipCache = false, isPrefetch = true)
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
		val deferred = loaderScope.async {
			counter.incrementAndGet()
			try {
				loadPageImpl(
					page = page,
					progress = progress,
					isPrefetch = isPrefetch,
					skipCache = skipCache,
				)
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
	): Uri = semaphore.withPermit {
		val pageUrl = getPageUrl(page)
		check(pageUrl.isNotBlank()) { "Cannot obtain full image url for $page" }
		if (!skipCache) {
			cache.get(pageUrl)?.let { return it.toUri() }
		}
		val uri = pageUrl.toUri()
		return when {
			uri.isZipUri() -> if (uri.scheme == URI_SCHEME_ZIP) {
				uri
			} else { // legacy uri
				uri.buildUpon().scheme(URI_SCHEME_ZIP).build()
			}

			uri.isFileUri() -> uri
			else -> {
				if (isPrefetch) {
					downloadSlowdownDispatcher.delay(page.source)
				}
				val request = createPageRequest(pageUrl, page.source)
				imageProxyInterceptor.interceptPageRequest(request, okHttp).ensureSuccess().use { response ->
					response.requireBody().withProgress(progress).use {
						cache.set(pageUrl, it.source(), it.contentType()?.toMimeType())
					}
				}.toUri()
			}
		}
	}

	private fun isLowRam(): Boolean {
		return context.ramAvailable <= FileSize.MEGABYTES.convert(PREFETCH_MIN_RAM_MB, FileSize.BYTES)
	}

	private fun Image.toImageSource(): ImageSource = if (this is BitmapImage) {
		ImageSource.cachedBitmap(toBitmap())
	} else {
		ImageSource.bitmap(toBitmap())
	}

	private fun Deferred<Uri>.isValid(): Boolean {
		return getCompletionResultOrNull()?.map { uri ->
			uri.exists() && uri.isTargetNotEmpty()
		}?.getOrDefault(false) != false
	}

	private class InternalErrorHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
		CoroutineExceptionHandler {

		override fun handleException(context: CoroutineContext, exception: Throwable) {
			exception.printStackTraceDebug()
		}
	}

	companion object {

		private const val PROGRESS_UNDEFINED = -1f
		private const val PREFETCH_LIMIT_DEFAULT = 6
		private const val PREFETCH_MIN_RAM_MB = 80L

		fun createPageRequest(pageUrl: String, mangaSource: MangaSource) = Request.Builder()
			.url(pageUrl)
			.get()
			.header(CommonHeaders.ACCEPT, "image/webp,image/png;q=0.9,image/jpeg,*/*;q=0.8")
			.cacheControl(CommonHeaders.CACHE_CONTROL_NO_STORE)
			.tag(MangaSource::class.java, mangaSource)
			.build()


		@Blocking
		private fun Uri.exists(): Boolean = when {
			isFileUri() -> toFile().exists()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { it.getEntry(fragment) != null }
			}

			else -> false
		}

		@Blocking
		private fun Uri.isTargetNotEmpty(): Boolean = when {
			isFileUri() -> toFile().isNotEmpty()
			isZipUri() -> {
				val file = File(requireNotNull(schemeSpecificPart))
				file.exists() && ZipFile(file).use { (it.getEntry(fragment)?.size ?: 0L) != 0L }
			}

			else -> false
		}
	}
}

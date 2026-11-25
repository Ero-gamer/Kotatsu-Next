package org.koitharu.kotatsu.reader.ui.pager.vm

import android.graphics.Rect
import android.net.Uri
import androidx.annotation.WorkerThread
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.ImageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import okio.IOException
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.throttle
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.erase.PageMask
import org.koitharu.kotatsu.reader.domain.erase.PageVisibility
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings

class PageViewModel(
    private val loader: PageLoader,
    val settingsProducer: ReaderSettings.Producer,
    private val networkState: NetworkState,
    private val exceptionResolver: ExceptionResolver,
    private val isWebtoon: Boolean,
) : DefaultOnImageEventListener {

    private val scope = loader.loaderScope + Dispatchers.Main.immediate
    private var job: Job? = null
    private var cachedBounds: Rect? = null

    /** Whether the bounds on screen were cropped; a setting change compares against it, see [recheck]. */
    private var appliedCrop: Boolean? = null
    private var boundPage: MangaPage? = null

    /** The page as downloaded. Hidden parts are left out while decoding, so this is always the whole of it. */
    private var rawUri: Uri? = null

    /** The file behind what is on screen, once it is on screen. */
    val shownImage: Uri?
        get() = rawUri.takeIf { state.value is PageState.Shown }

    /** What the view is showing of that file, in the coordinates it was given. */
    val shownRegion: Rect?
        get() = cachedBounds

    val shownMask: PageMask?
        get() = (state.value as? PageState.Shown)?.mask

    val state = MutableStateFlow<PageState>(PageState.Empty)

    fun isLoading() = job?.isActive == true

    fun onBind(page: MangaPage) {
        // Immediately clear stale state so the observer in BasePageHolder doesn't
        // call ssiv.setImage() with the previous page's source while prevJob is
        // still cancelling. Without this, a stale PageState.Loaded triggers a
        // phantom setImage() → ssiv.reset() → decoder init cancelled → "preparing"
        // screen permanently stuck on chapter 2+ high-res images.
        state.value = PageState.Empty
        val prevJob = job
        job = scope.launch(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            doLoad(page, force = false)
        }
    }

    fun retry(page: MangaPage, isFromUser: Boolean) {
        val prevJob = job
        job = scope.launch {
            prevJob?.cancelAndJoin()
            val e = (state.value as? PageState.Error)?.error
            if (e != null && ExceptionResolver.canResolve(e)) {
                if (isFromUser) {
                    exceptionResolver.resolve(e)
                }
            }
            withContext(Dispatchers.Default) {
                doLoad(page, force = true)
            }
        }
    }

    fun showErrorDetails(url: String?) {
        val e = (state.value as? PageState.Error)?.error ?: return
        exceptionResolver.showErrorDetails(e, url)
    }

    fun onRecycle() {
        state.value = PageState.Empty
        cachedBounds = null
        appliedCrop = null
        rawUri = null
        boundPage = null
        job?.cancel()
    }

    /** A reader setting changed: a page already on screen is cropped again if the crop flag no longer matches. */
    fun onSettingsChanged(settings: ReaderSettings) {
        val applied = appliedCrop ?: return
        if (applied != settings.isPagesCropEnabled(isWebtoon)) {
            recheck()
        }
    }

    /**
     * Checks a page that is already loaded against the current remembered images and the crop setting. Only touches
     * the image if the answer changed, so erasing something does not make every page on screen blink.
     */
    fun recheck() {
        val prevJob = job
        job = scope.launch(Dispatchers.Default) {
            prevJob?.join()
            val page = boundPage ?: return@launch
            val uri = rawUri ?: return@launch
            val visibility = loader.imageHider.resolve(page, uri)
            val isCropUnchanged = appliedCrop.let { it == null || it == settingsProducer.value.isPagesCropEnabled(isWebtoon) }
            val isUnchanged = isCropUnchanged && when (val current = state.value) {
                is PageState.Shown -> visibility == PageVisibility.Show(current.mask)
                is PageState.Loaded -> visibility == PageVisibility.Show(current.mask)
                else -> false
            }
            if (!isUnchanged) {
                present(uri, visibility, isConverted = false)
            }
        }
    }

    override fun onImageLoaded() {
        state.update { currentState ->
            if (currentState is PageState.Loaded) {
                PageState.Shown(currentState.source, currentState.isConverted, currentState.mask)
            } else {
                currentState
            }
        }
    }

    override fun onImageLoadError(e: Throwable) {
        e.printStackTraceDebug()

        // Decide outside update{}: its lambda may run more than once under contention and must not launch work.
        val current = state.value as? PageState.Loaded ?: return
        val uri = (current.source as? ImageSource.Uri)?.uri
        if (!current.isConverted && uri != null && e is IOException) {
            if (state.compareAndSet(current, PageState.Converting())) tryConvert(uri, e)
        } else {
            state.compareAndSet(current, PageState.Error(e))
        }
    }

    private fun tryConvert(uri: Uri, e: Exception) {
        val prevJob = job
        job = scope.launch(Dispatchers.Default) {
            prevJob?.join()
            state.value = PageState.Converting()
            try {
                val newUri = loader.convertBimap(uri)
                rawUri = newUri
                // The check before this could not read the image, which is why it needed converting
                val page = boundPage ?: return@launch
                present(newUri, loader.imageHider.resolve(page, newUri), isConverted = true)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e2: Throwable) {
                e2.printStackTrace()
                e.addSuppressed(e2)
                state.value = PageState.Error(e)
            }
        }
    }

    @WorkerThread
    private suspend fun doLoad(data: MangaPage, force: Boolean) = coroutineScope {
        boundPage = data
        state.value = PageState.Loading(null, -1)
        val previewJob = launch {
            // Most pages arrive (or are already on disk) well within this: no preview fetch for them, and
            // none for neighbours that a fast scroll binds and drops again.
            delay(PREVIEW_DELAY_MS)
            if (!loader.imageHider.canShowPreview(data)) return@launch
            val preview = loader.loadPreview(data) ?: return@launch
            state.update {
                if (it is PageState.Loading) it.copy(preview = preview) else it
            }
        }
        try {
            val task = loader.loadPageAsync(data, force)
            val progressObserver = observeProgress(this, task.progressAsFlow())
            val uri = try {
                task.await()
            } catch (e: CancellationException) {
                // Given up on by a view that went away just as this one came for it: no reason to sit here spinning
                ensureActive()
                loader.loadPage(data, force = false)
            }
            progressObserver.cancelAndJoin()
            previewJob.cancel()
            rawUri = uri
            present(uri, loader.imageHider.resolve(data, uri), isConverted = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.printStackTraceDebug()
            state.value = PageState.Error(e)
            if (e is IOException && !networkState.value) {
                networkState.awaitForConnection()
                retry(data, isFromUser = false)
            }
        }
    }

    private fun observeProgress(scope: CoroutineScope, progress: Flow<Float>) = progress
        .throttle(250)
        .onEach {
            val progressValue = (100 * it).toInt()
            state.update { currentState ->
                if (currentState is PageState.Loading) {
                    currentState.copy(progress = progressValue)
                } else {
                    currentState
                }
            }
        }.launchIn(scope)

    private suspend fun present(uri: Uri, visibility: PageVisibility, isConverted: Boolean) {
        if (visibility !is PageVisibility.Show) {
            state.value = PageState.Hidden
            return
        }
        val isCrop = settingsProducer.value.isPagesCropEnabled(isWebtoon)
        appliedCrop = isCrop
        val trim = if (isCrop) loader.getTrimmedBounds(uri) else null
        val mask = visibility.mask
        // A masked page always gets a region, which is what makes the view use the region decoder that
        // knows about the mask, rather than loading the file whole for being small enough.
        cachedBounds = when {
            mask == null -> trim
            trim == null -> Rect(0, 0, mask.sourceWidth, mask.visibleHeight)
            else -> Rect(trim.left, mask.toVisible(trim.top), trim.right, mask.toVisible(trim.bottom))
        }
        state.value = PageState.Loaded(uri.toImageSource(cachedBounds), isConverted, mask)
    }

    private fun Uri.toImageSource(bounds: Rect?): ImageSource {
        val source = ImageSource.uri(this)
        return if (bounds != null) {
            source.region(bounds)
        } else {
            source
        }
    }

    private companion object {

        const val PREVIEW_DELAY_MS = 200L
    }
}

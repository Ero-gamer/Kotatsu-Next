package org.koitharu.kotatsu.reader.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.exceptions.EmptyMangaException
import org.koitharu.kotatsu.core.exceptions.NoDataReceivedException
import org.koitharu.kotatsu.core.model.getPreferredBranch
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.nav.ReaderIntent
import org.koitharu.kotatsu.core.os.AppShortcutManager
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.firstNotNull
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.domain.DetailsLoadUseCase
import org.koitharu.kotatsu.details.ui.pager.ChaptersPagesViewModel
import org.koitharu.kotatsu.details.ui.pager.EmptyMangaReason
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.history.domain.HistoryUpdateUseCase
import org.koitharu.kotatsu.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.parsers.util.sizeOrZero
import org.koitharu.kotatsu.reader.domain.ChaptersLoader
import org.koitharu.kotatsu.reader.domain.DetectReaderModeUseCase
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.neighbourIndex
import org.koitharu.kotatsu.reader.domain.selectDownloadAhead
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.scrobbling.discord.ui.DiscordRpc
import org.koitharu.kotatsu.stats.domain.StatsCollector
import java.time.Instant
import javax.inject.Inject

private const val BOUNDS_PAGE_OFFSET = 2
private const val NEIGHBOUR_WAIT_MS = 1500L

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val dataRepository: MangaDataRepository,
    private val favouritesRepository: FavouritesRepository,
    private val historyRepository: HistoryRepository,
    private val bookmarksRepository: BookmarksRepository,
    settings: AppSettings,
    private val pageLoader: PageLoader,
    private val chaptersLoader: ChaptersLoader,
    private val appShortcutManager: AppShortcutManager,
    private val detailsLoadUseCase: DetailsLoadUseCase,
    private val historyUpdateUseCase: HistoryUpdateUseCase,
    private val detectReaderModeUseCase: DetectReaderModeUseCase,
    private val statsCollector: StatsCollector,
    private val discordRpc: DiscordRpc,
    @LocalStorageChanges localStorageChanges: SharedFlow<LocalManga?>,
    interactor: DetailsInteractor,
    deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
    private val downloadScheduler: DownloadWorker.Scheduler,
    readerSettingsProducerFactory: ReaderSettings.Producer.Factory,
) : ChaptersPagesViewModel(
    settings = settings,
    interactor = interactor,
    bookmarksRepository = bookmarksRepository,
    historyRepository = historyRepository,
    downloadScheduler = downloadScheduler,
    deleteLocalMangaUseCase = deleteLocalMangaUseCase,
    localStorageChanges = localStorageChanges,
) {
    private val intent = MangaIntent(savedStateHandle)

    private var loadingJob: Job? = null
    private var pageSaveJob: Job? = null
    private var bookmarkJob: Job? = null
    private var stateChangeJob: Job? = null
    private var downloadAheadChapterId = 0L
    private val downloadAheadQueued = HashSet<Long>()

    // The last position that was fully handled (state saved, next chapter / prefetch checked). A scroll tick
    // inside the same item list position repeats none of that work.
    @Volatile
    private var handledPages: List<*>? = null

    @Volatile
    private var handledLower = -1

    @Volatile
    private var handledUpper = -1

    init {
        mangaDetails.value = intent.manga?.let { MangaDetails(it) }
    }

    val readerMode = MutableStateFlow<ReaderMode?>(null)
    val onPageSaved = MutableEventFlow<Collection<Uri>>()
    val onLoadingError = MutableEventFlow<Throwable>()
    val onShowToast = MutableEventFlow<Int>()
    val onAskNsfwIncognito = MutableEventFlow<Unit>()
    val uiState = MutableStateFlow<ReaderUiState?>(null)

    val isIncognitoMode = MutableStateFlow(savedStateHandle.get<Boolean>(ReaderIntent.EXTRA_INCOGNITO))

    val content = MutableStateFlow(ReaderContent(emptyList(), null))
    private val contentLock = Mutex()

    val pageAnimation = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_ANIMATION,
        valueProducer = { readerAnimation },
    )

    val isInfoBarEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_BAR,
        valueProducer = { isReaderBarEnabled },
    )

    val isInfoBarTransparent = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_BAR_TRANSPARENT,
        valueProducer = { isReaderBarTransparent },
    )

    val isKeepScreenOnEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_READER_SCREEN_ON,
        valueProducer = { isReaderKeepScreenOn },
    )

    val isWebtoonZooEnabled = observeIsWebtoonZoomEnabled()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val isWebtoonGapsEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_WEBTOON_GAPS,
        valueProducer = { isWebtoonGapsEnabled },
    )

    val isWebtoonPullGestureEnabled = settings.observeAsStateFlow(
        scope = viewModelScope + Dispatchers.Default,
        key = AppSettings.KEY_WEBTOON_PULL_GESTURE,
        valueProducer = { isWebtoonPullGestureEnabled },
    )

    val defaultWebtoonZoomOut = observeIsWebtoonZoomEnabled().flatMapLatest {
        if (it) {
            observeWebtoonZoomOut()
        } else {
            flowOf(0f)
        }
    }.flowOn(Dispatchers.Default)

    val isZoomControlsEnabled = getObserveIsZoomControlEnabled().flatMapLatest { zoom ->
        if (zoom) {
            combine(readerMode, isWebtoonZooEnabled) { mode, ze -> ze || mode != ReaderMode.WEBTOON }
        } else {
            flowOf(false)
        }
    }.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, false)

    val readerSettingsProducer = readerSettingsProducerFactory.create(
        manga.mapNotNull { it?.id },
    )

    /** `true` while all image filters are switched off for the current manga (values stay saved). */
    val isColorFilterDisabled = readerSettingsProducer.map { it.isColorFilterDisabled }
        .distinctUntilChanged()

    fun setColorFilterEnabled(isEnabled: Boolean) {
        val manga = getMangaOrNull() ?: return
        launchJob(Dispatchers.Default) {
            dataRepository.setColorFilterDisabled(manga, !isEnabled)
        }
    }

    /** This manga's border crop for the current reading style: `null` follows the global setting. */
    fun getCropOverride(): Boolean? = readerSettingsProducer.value.cropOverrides.forMode(isWebtoonMode())

    fun setCropOverride(isEnabled: Boolean?) {
        val manga = getMangaOrNull() ?: return
        val isWebtoon = isWebtoonMode()
        launchJob(Dispatchers.Default) {
            dataRepository.setCropOverride(manga, isWebtoon, isEnabled)
        }
    }

    private fun isWebtoonMode() = readerMode.value == ReaderMode.WEBTOON

    val isMangaNsfw = manga.map { it?.contentRating == ContentRating.ADULT }

    // One bookmark observer per manga (not one per page turn); the current page is matched in memory.
    private val mangaBookmarks = mangaDetails
        .distinctUntilChangedBy { it?.id }
        .flatMapLatest { details ->
            if (details == null) flowOf(emptyList()) else bookmarksRepository.observeBookmarks(details.toManga())
        }

    val isBookmarkAdded = combine(readingState, mangaBookmarks) { state, bookmarks ->
        state != null && bookmarks.any { it.chapterId == state.chapterId && it.page == state.page }
    }.distinctUntilChanged()
        .stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, false)

    init {
        initIncognitoMode()
        loadImpl()
        launchJob(Dispatchers.Default) {
            pageLoader.imageHider.cuts.collect { found ->
                // A strip slices images wherever it likes, so the rest of this one is on the page next door
                val neighbour = chaptersLoader.findNeighbour(found.pageId, isBefore = found.cut.isAtTop)
                if (neighbour != null) {
                    runCatchingCancellable {
                        val page = neighbour.toMangaPage()
                        if (!pageLoader.imageHider.hasRemainder(page, found.cut)) {
                            pageLoader.imageHider.hideRemainder(page, pageLoader.loadPage(page, force = false), found.cut)
                        }
                    }.onFailure { it.printStackTraceDebug() }
                }
            }
        }
        launchJob(Dispatchers.Default) {
            chaptersLoader.observeHiddenPages().drop(1).collect {
                contentLock.withLock {
                    val pages = chaptersLoader.snapshot()
                    // Empty means a chapter switch is under way, and it brings its own pages and position
                    content.update { current ->
                        if (current.pages.isEmpty()) current else ReaderContent(pages, null)
                    }
                }
            }
        }
        launchJob(Dispatchers.Default) {
            val mangaId = manga.filterNotNull().first().id
            if (!isIncognitoMode.firstNotNull()) {
                appShortcutManager.notifyMangaOpened(mangaId)
            }
        }
    }

    fun reload() {
        loadingJob?.cancel()
        loadImpl()
    }

    fun onPause() {
        getMangaOrNull()?.let {
            statsCollector.onPause(it.id)
        }
    }

    fun onStop() {
        discordRpc.clearRpc()
    }

    fun onIdle() {
        discordRpc.setIdle()
    }

    fun switchMode(newMode: ReaderMode) {
        launchJob {
            val manga = checkNotNull(getMangaOrNull())
            dataRepository.saveReaderMode(
                manga = manga,
                mode = newMode,
            )
            readerMode.value = newMode
            content.update {
                it.copy(state = getCurrentState())
            }
        }
    }

    fun saveCurrentState(state: ReaderState? = null) {
        if (state != null) {
            readingState.value = state
            savedStateHandle[ReaderIntent.EXTRA_STATE] = state
        }
        if (isIncognitoMode.value != false) {
            return
        }
        val readerState = state ?: readingState.value ?: return
        // onPause + onDestroyView and friends call this back to back: persist an unchanged position once.
        if (readerState == lastSavedState) return
        lastSavedState = readerState
        historyUpdateUseCase.invokeAsync(
            manga = getMangaOrNull() ?: return,
            readerState = readerState,
            percent = computePercent(readerState.chapterId, readerState.page),
        )
    }

    @Volatile
    private var lastSavedState: ReaderState? = null

    fun getCurrentState() = readingState.value

    /**
     * The files of the pages either side of [page]. Usually already preloaded; one that is not gets a moment
     * and is otherwise left out, since this is only to compare against and someone is waiting on it.
     */
    suspend fun loadNeighbourImages(page: ReaderPage): List<Uri> {
        val pages = content.value.pages
        val index = pages.indexOfFirst { it.id == page.id && it.chapterId == page.chapterId }
        if (index < 0) {
            return emptyList()
        }
        return listOfNotNull(pages.getOrNull(index - 1), pages.getOrNull(index + 1)).mapNotNull { neighbour ->
            withTimeoutOrNull(NEIGHBOUR_WAIT_MS) {
                runCatchingCancellable { pageLoader.loadPage(neighbour.toMangaPage(), force = false) }.getOrNull()
            }
        }
    }

    fun getCurrentChapterPages(): List<MangaPage>? {
        val chapterId = readingState.value?.chapterId ?: return null
        return chaptersLoader.getPages(chapterId)
    }

    fun saveCurrentPage(
        pageSaveHelper: PageSaveHelper,
    ) {
        val prevJob = pageSaveJob
        pageSaveJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val dest = pageSaveHelper.save(setOf(buildSaveTask()))
            onPageSaved.call(dest)
        }
    }

    /** Saves the animation frame on screen ([frame] is owned and recycled by the helper). */
    fun saveCurrentFrame(pageSaveHelper: PageSaveHelper, frame: Bitmap) {
        val prevJob = pageSaveJob
        pageSaveJob = launchLoadingJob(Dispatchers.Default) {
            try {
                prevJob?.cancelAndJoin()
                val task = buildSaveTask()
                onPageSaved.call(setOf(pageSaveHelper.saveFrame(task, frame)))
            } finally {
                if (!frame.isRecycled) frame.recycle()
            }
        }
    }

    private fun buildSaveTask(): PageSaveHelper.Task {
        val state = checkNotNull(getCurrentState())
        return PageSaveHelper.Task(
            manga = manga.requireValue(),
            chapterId = state.chapterId,
            pageNumber = state.page + 1,
            page = checkNotNull(getCurrentPage()) { "Cannot find current page" },
        )
    }

    fun getCurrentPage(): MangaPage? {
        val state = readingState.value ?: return null
        return content.value.pages.find {
            it.chapterId == state.chapterId && it.index == state.page
        }?.toMangaPage() ?: chaptersLoader.getPages(state.chapterId).getOrNull(state.page)
    }

    // BUG 3 FIX: Don't emit ReaderContent(emptyList(), null) before loading new chapter.
    //
    // The old code did:
    //   content.value = ReaderContent(emptyList(), null)   ← causes pager to jump to pos 0
    //   chaptersLoader.loadSingleChapter(id)
    //   content.value = ReaderContent(snapshot, newState)
    //
    // Emitting empty content first caused ViewPager2 / the RecyclerView to scroll back to
    // position 0 — which, with a previous chapter's adapter still bound, displayed the first
    // page of the PREVIOUS chapter momentarily, creating the "chapter 3 shows chapter 2 page 1"
    // symptom. The fix is to load first, validate, then emit atomically.
    fun switchChapter(id: Long, page: Int) {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()

            // Load pages first — do NOT emit empty content.
            val loaded = chaptersLoader.loadSingleChapter(id)
            if (!loaded) {
                content.value = ReaderContent(emptyList(), null)
                return@launchLoadingJob
            }

            // BUG 3 FIX: validate the snapshot actually contains pages for the
            // requested chapter before publishing. Guards against stale repository
            // caches returning pages from a different chapter.
            val snapshot = chaptersLoader.snapshot()
            if (snapshot.none { it.chapterId == id }) {
                // Retry once — in case of a transient cache issue.
                val retried = chaptersLoader.loadSingleChapter(id)
                if (!retried || chaptersLoader.snapshot().none { it.chapterId == id }) {
                    content.value = ReaderContent(emptyList(), null)
                    return@launchLoadingJob
                }
            }

            val newState = ReaderState(id, page, 0)
            publishPages(newState)
            saveCurrentState(newState)
        }
    }

    // BUG 3 FIX: same fix as switchChapter.
    fun switchChapterBy(delta: Int) {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val prevState = readingState.requireValue()
            val newChapterId = if (delta != 0) {
                val allChapters = mangaDetails.requireValue().allChapters
                var index = allChapters.indexOfFirst { x -> x.id == prevState.chapterId }
                if (index < 0) {
                    return@launchLoadingJob
                }
                index = if (delta == 1 || delta == -1) {
                    allChapters.neighbourIndex(index, delta, settings.isSkipDuplicateChapters)
                } else {
                    index + delta
                }
                (allChapters.getOrNull(index) ?: return@launchLoadingJob).id
            } else {
                prevState.chapterId
            }

            // Load first, validate, then emit atomically.
            val loaded = chaptersLoader.loadSingleChapter(newChapterId)
            if (!loaded) {
                content.value = ReaderContent(emptyList(), null)
                return@launchLoadingJob
            }

            val snapshot = chaptersLoader.snapshot()
            if (snapshot.none { it.chapterId == newChapterId }) {
                val retried = chaptersLoader.loadSingleChapter(newChapterId)
                if (!retried || chaptersLoader.snapshot().none { it.chapterId == newChapterId }) {
                    content.value = ReaderContent(emptyList(), null)
                    return@launchLoadingJob
                }
            }

            val newState = ReaderState(
                chapterId = newChapterId,
                page = if (delta == 0) prevState.page else 0,
                scroll = if (delta == 0) prevState.scroll else 0,
            )
            publishPages(newState)
            saveCurrentState(newState)
        }
    }

    @MainThread
    fun onCurrentPageChanged(lowerPos: Int, upperPos: Int) {
        val pages = content.value.pages // capture immediately
        if (pages === handledPages && lowerPos == handledLower && upperPos == handledUpper) return
        val prevJob = stateChangeJob
        stateChangeJob = launchJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            loadingJob?.join()
            if (pages.size != content.value.pages.size) {
                return@launchJob // TODO
            }
            val centerPos = (lowerPos + upperPos) / 2
            pages.getOrNull(centerPos)?.let { page ->
                pageLoader.onPageShown(page.id)
                readingState.update { cs ->
                    cs?.copy(chapterId = page.chapterId, page = page.index)
                }
                runCatchingCancellable { downloadAhead(page.chapterId) }.onFailure { it.printStackTraceDebug() }
            }
            notifyStateChanged()
            if (pages.isEmpty() || loadingJob?.isActive == true) {
                return@launchJob
            }
            ensureActive()
            val autoLoadAllowed = readerMode.value != ReaderMode.WEBTOON || !isWebtoonPullGestureEnabled.value
            if (autoLoadAllowed) {
                if (upperPos >= pages.lastIndex - BOUNDS_PAGE_OFFSET) {
                    loadPrevNextChapter(pages.last().chapterId, isNext = true)
                }
                if (lowerPos <= BOUNDS_PAGE_OFFSET) {
                    loadPrevNextChapter(pages.first().chapterId, isNext = false)
                }
            }
            pageLoader.measureAutoColor = readerSettingsProducer.value.effectiveColorFilter?.hasAuto == true
            if (pageLoader.isPrefetchApplicable()) {
                // Ahead first (nearest first), then a few pages behind, which a back-swipe needs next.
                val plan = pageLoader.preloadPlan()
                val behind = pages.trySublist((lowerPos - plan.behind).coerceAtLeast(0), lowerPos).asReversed()
                pageLoader.prefetch(pages.trySublist(upperPos + 1, upperPos + 1 + plan.ahead) + behind)
            }
            // Only a run that got all the way here counts as handled; early exits are retried by the next tick.
            handledLower = lowerPos
            handledUpper = upperPos
            handledPages = pages
        }
    }

    /** Saves the next chapters of a favorite series while a saved chapter of it is read (setting, off by default). */
    private suspend fun downloadAhead(chapterId: Long) {
        val count = settings.downloadAheadCount
        if (count <= 0 || chapterId == downloadAheadChapterId) return
        downloadAheadChapterId = chapterId
        val details = mangaDetails.value ?: return
        if (details.isLocal || !favouritesRepository.isFavorite(details.id)) return
        val downloaded = details.local?.manga?.chapters?.mapTo(HashSet()) { it.id } ?: return
        val chapters = details.chapters[selectedBranch.value] ?: return
        val ids = selectDownloadAhead(chapters.map { it.id }, chapterId, downloaded, count)
            .filter { downloadAheadQueued.add(it) }
        if (ids.isEmpty()) return
        val task = DownloadTask(
            mangaId = details.id,
            isPaused = false,
            isSilent = true,
            chaptersIds = ids.toLongArray(),
            destination = null,
            format = null,
            allowMeteredNetwork = settings.allowDownloadOnMeteredNetwork == TriStateOption.ENABLED,
        )
        downloadScheduler.schedule(setOf(details.toManga() to task))
    }

    fun toggleBookmark() {
        if (bookmarkJob?.isActive == true) {
            return
        }
        bookmarkJob = launchJob(Dispatchers.Default) {
            loadingJob?.join()
            val state = checkNotNull(getCurrentState())
            if (isBookmarkAdded.value) {
                val manga = requireManga()
                bookmarksRepository.removeBookmark(manga.id, state.chapterId, state.page)
                onShowToast.call(R.string.bookmark_removed)
            } else {
                val page = checkNotNull(getCurrentPage()) { "Page not found" }
                val bookmark = Bookmark(
                    manga = requireManga(),
                    pageId = page.id,
                    chapterId = state.chapterId,
                    page = state.page,
                    scroll = state.scroll,
                    imageUrl = page.preview.ifNullOrEmpty { page.url },
                    createdAt = Instant.now(),
                    percent = computePercent(state.chapterId, state.page),
                )
                bookmarksRepository.addBookmark(bookmark)
                onShowToast.call(R.string.bookmark_added)
            }
        }
    }

    fun setIncognitoMode(value: Boolean, dontAskAgain: Boolean) {
        isIncognitoMode.value = value
        if (dontAskAgain) {
            settings.incognitoModeForNsfw = if (value) TriStateOption.ENABLED else TriStateOption.DISABLED
        }
    }

    private fun loadImpl() {
        loadingJob = launchLoadingJob(Dispatchers.Default + EventExceptionHandler(onLoadingError)) {
            var exception: Exception? = null
            var loadedDetails: MangaDetails? = null
            // Later details emissions (refresh) must not re-publish a stale reading state (it would jump the reader
            // back) nor rewrite history unless the chapter list changed.
            var published = false
            var historyChapters = -1
            try {
                detailsLoadUseCase(intent, force = false)
                    .collect { details ->
                        loadedDetails = details
                        if (mangaDetails.value == null) {
                            mangaDetails.value = details
                        }
                        chaptersLoader.init(details)
                        val manga = details.toManga()
                        // obtain state
                        if (readingState.value == null) {
                            val newState = getStateFromIntent(manga)
                            if (newState == null) {
                                return@collect // manga not loaded yet if cannot get state
                            }
                            readingState.value = newState
                            val mode = runCatchingCancellable {
                                detectReaderModeUseCase(manga, newState)
                            }.getOrDefault(settings.defaultReaderMode)
                            val branch = chaptersLoader.peekChapter(newState.chapterId)?.branch
                            selectedBranch.value = branch
                            readerMode.value = mode
                            try {
                                if (!chaptersLoader.loadSingleChapter(newState.chapterId)) {
                                    // a chapter without pages is a failure (error + retry), not an empty reader
                                    throw NoDataReceivedException(chaptersLoader.peekChapter(newState.chapterId)?.url.orEmpty())
                                }
                            } catch (e: Exception) {
                                readingState.value = null // try next time
                                exception = e.mergeWith(exception)
                                return@collect
                            }
                        }
                        mangaDetails.value = details.filterChapters(selectedBranch.value)
                        notifyStateChanged()
                        publishPages(if (published) null else readingState.value)
                        published = true

                        // save state, once the pages are on their way: this rewrites the whole chapter list
                        val chaptersCount = details.allChapters.size
                        if (chaptersCount != historyChapters && !isIncognitoMode.firstNotNull()) {
                            historyChapters = chaptersCount
                            readingState.value?.let {
                                val percent = computePercent(it.chapterId, it.page)
                                historyUpdateUseCase(manga, it, percent)
                                lastSavedState = it
                            }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                exception = e.mergeWith(exception)
            }
            if (readingState.value == null) {
                val loadedManga = loadedDetails // for smart cast
                if (loadedManga != null) {
                    mangaDetails.value = loadedManga.filterChapters(selectedBranch.value)
                }
                val loadingError = when {
                    exception != null -> exception

                    loadedManga == null || !loadedManga.isLoaded -> null

                    loadedManga.isRestricted -> EmptyMangaException(
                        EmptyMangaReason.RESTRICTED,
                        loadedManga.toManga(),
                        null,
                    )

                    loadedManga.allChapters.isEmpty() -> EmptyMangaException(
                        EmptyMangaReason.NO_CHAPTERS,
                        loadedManga.toManga(),
                        null,
                    )

                    else -> null
                } ?: IllegalStateException("Unable to load manga. This should never happen. Please report")
                onLoadingError.call(loadingError)
            } else {
                exception?.let { e ->
                    // manga has been loaded but error occurred
                    errorEvent.call(e)
                }
            }
        }
    }

    @AnyThread
    private fun loadPrevNextChapter(currentId: Long, isNext: Boolean) {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            prevJob?.join()
            chaptersLoader.loadPrevNextChapter(
                mangaDetails.requireValue(),
                currentId,
                isNext,
                settings.isSkipDuplicateChapters,
                readingChapterId = readingState.value?.chapterId,
            )
            publishPages(null)
        }
    }

    /**
     * Puts the current pages on screen. Reading them and publishing them go together, one writer at a time: a
     * list read just before a page was found to be hidden, and published just after, would bring that page back
     * for good, as nothing is going to change again to correct it.
     */
    private suspend fun publishPages(state: ReaderState?) = contentLock.withLock {
        content.value = ReaderContent(chaptersLoader.snapshot(), state)
    }

    private fun <T> List<T>.trySublist(fromIndex: Int, toIndex: Int): List<T> {
        val fromIndexBounded = fromIndex.coerceAtMost(lastIndex)
        val toIndexBounded = toIndex.coerceIn(fromIndexBounded, lastIndex)
        return if (fromIndexBounded == toIndexBounded) {
            emptyList()
        } else {
            subList(fromIndexBounded, toIndexBounded)
        }
    }

    @WorkerThread
    private fun notifyStateChanged() {
        val state = getCurrentState() ?: return
        val chapter = chaptersLoader.peekChapter(state.chapterId) ?: return
        val m = mangaDetails.value ?: return
        val chapterIndex = m.chapters[chapter.branch]?.indexOfFirst { it.id == chapter.id } ?: -1
        val newState = ReaderUiState(
            mangaName = m.toManga().title,
            chapter = chapter,
            chapterIndex = chapterIndex,
            chaptersTotal = m.chapters[chapter.branch].sizeOrZero(),
            totalPages = chaptersLoader.getPagesCount(chapter.id),
            currentPage = state.page,
            percent = computePercent(state.chapterId, state.page),
            incognito = isIncognitoMode.value == true,
        )
        uiState.value = newState
        if (isIncognitoMode.value == false) {
            statsCollector.onStateChanged(m.id, state)
            discordRpc.updateRpc(m.toManga(), newState)
        }
    }

    private fun computePercent(chapterId: Long, pageIndex: Int): Float {
        val branch = chaptersLoader.peekChapter(chapterId)?.branch
        val chapters = mangaDetails.value?.chapters?.get(branch) ?: return PROGRESS_NONE
        val chaptersCount = chapters.size
        val chapterIndex = chapters.indexOfFirst { x -> x.id == chapterId }
        val pagesCount = chaptersLoader.getPagesCount(chapterId)
        if (chaptersCount == 0 || pagesCount == 0) {
            return PROGRESS_NONE
        }
        // With a hidden credit page at the end, the last page that can be reached still finishes the chapter
        val lastVisibleIndex = content.value.pages.lastOrNull { it.chapterId == chapterId }?.index
        val effectiveIndex = if (pageIndex == lastVisibleIndex) pagesCount - 1 else pageIndex
        val pagePercent = (effectiveIndex + 1) / pagesCount.toFloat()
        val ppc = 1f / chaptersCount
        return ppc * chapterIndex + ppc * pagePercent
    }

    private fun observeIsWebtoonZoomEnabled() = settings.observeAsFlow(
        key = AppSettings.KEY_WEBTOON_ZOOM,
        valueProducer = { isWebtoonZoomEnabled },
    )

    private fun observeWebtoonZoomOut() = settings.observeAsFlow(
        key = AppSettings.KEY_WEBTOON_ZOOM_OUT,
        valueProducer = { defaultWebtoonZoomOut },
    )

    private fun getObserveIsZoomControlEnabled() = settings.observeAsFlow(
        key = AppSettings.KEY_READER_ZOOM_BUTTONS,
        valueProducer = { isReaderZoomButtonsEnabled },
    )

    private fun initIncognitoMode() {
        if (isIncognitoMode.value != null) {
            return
        }
        launchJob(Dispatchers.Default) {
            interactor.observeIncognitoMode(manga)
                .collect {
                    when (it) {
                        TriStateOption.ENABLED -> isIncognitoMode.value = true

                        TriStateOption.ASK -> {
                            onAskNsfwIncognito.call(Unit)
                            return@collect
                        }

                        TriStateOption.DISABLED -> isIncognitoMode.value = false
                    }
                }
        }
    }

    private suspend fun getStateFromIntent(manga: Manga): ReaderState? {
        // check if we have at least some chapters loaded
        if (manga.chapters.isNullOrEmpty()) {
            return null
        }
        // specific state is requested
        val requestedState: ReaderState? = savedStateHandle[ReaderIntent.EXTRA_STATE]
        if (requestedState != null) {
            return if (manga.findChapterById(requestedState.chapterId) != null) {
                requestedState
            } else {
                null
            }
        }

        val requestedBranch: String? = savedStateHandle[ReaderIntent.EXTRA_BRANCH]
        // continue reading
        val history = historyRepository.getOne(manga)
        if (history != null) {
            val chapter = manga.findChapterById(history.chapterId) ?: return null
            // specified branch is requested
            return if (ReaderIntent.EXTRA_BRANCH in savedStateHandle) {
                if (chapter.branch == requestedBranch) {
                    ReaderState(history)
                } else {
                    ReaderState(manga, requestedBranch)
                }
            } else {
                ReaderState(history)
            }
        }

        // start from beginning
        val preferredBranch = requestedBranch ?: manga.getPreferredBranch(null)
        return ReaderState(manga, preferredBranch)
    }

    private fun Exception.mergeWith(other: Exception?): Exception = if (other == null) {
        this
    } else {
        other.addSuppressed(this)
        other
    }
}

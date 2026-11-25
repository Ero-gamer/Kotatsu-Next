package org.koitharu.kotatsu.discover.domain

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.discover.data.DiscoverCache
import org.koitharu.kotatsu.discover.data.DiscoverDismissals
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.ParserMangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.domain.TagsBlacklist
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

data class DiscoverFilter(val type: ContentType? = null, val genre: String? = null)

/** A list as far as it has got. [isFinal] false means more sources are still answering. */
data class DiscoverSection(val manga: List<Manga>, val isFinal: Boolean)

/**
 * Where everything on the Discover screen comes from, none of it adult whatever the other settings say.
 * Charts are what the reader's enabled sources say is popular; finds are what those sources have only just
 * got, picked for this reader on the device. What the reader likes stays on the phone, and nothing here
 * talks to any server but the sources themselves.
 */
class DiscoverRepository @Inject constructor(
    private val settings: AppSettings,
    private val sourcesRepository: MangaSourcesRepository,
    private val repositoryFactory: MangaRepository.Factory,
    private val suggestions: SuggestionRepository,
    private val history: HistoryRepository,
    private val cache: DiscoverCache,
    private val profiles: ReaderProfiles,
    private val dismissals: DiscoverDismissals,
) {

    /** Every row asks the same few sites, which should not see that as a burst. */
    private val sourceLocks = ConcurrentHashMap<String, Mutex>()

    private val sourcesLock = Mutex()

    @Volatile
    private var sources: Timed<List<MangaSource>>? = null

    private val blacklist: TagsBlacklist
        get() = TagsBlacklist(settings.suggestionsTagsBlacklist, TAG_MATCH_THRESHOLD)

    suspend fun hasSources(): Boolean = sourcesRepository.getEnabledSources().isNotEmpty()

    /** What the reader reads most first, then the genres everyone looks for. */
    suspend fun getGenres(): List<String> {
        val own = runCatchingCancellable { profiles.get().taste }.getOrDefault(emptyMap())
            .entries.sortedByDescending { it.value }
            .take(MAX_OWN_GENRES)
            .map { (tag, _) -> DiscoverRanking.genreTitle(tag) }
        return (own + COMMON_GENRES).distinctBy { DiscoverRanking.titleKey(it) }
    }

    /** What the reader keeps coming back to: last time's answer at once if there is one, then the current one. */
    fun observeCategories(): Flow<List<ReaderCategory>> = flow {
        runCatchingCancellable { profiles.lastCategories() }.getOrNull()?.let { emit(it) }
        emit(runCatchingCancellable { profiles.get().categories }.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList()))
    }.distinctUntilChanged()

    /** What the library adds up to, so far as that is known: nothing on screen has to wait to find out. */
    fun observeProfile(): Flow<ReaderProfile?> = flow {
        emit(profiles.peek())
        emit(runCatchingCancellable { profiles.get() }.onFailure { it.printStackTraceDebug() }.getOrNull())
    }.distinctUntilChanged { old, new -> old === new }

    /** Asks for everything again, however recently it was fetched. */
    fun invalidate() {
        profiles.invalidate()
        sources = null
    }

    /**
     * What is popular in something the reader keeps coming back to, that they do not have yet.
     *
     * Only asks the sources they read on, and only a few: this is one row of several, and those sites are
     * where they would rather read it anyway. Someone with a big library has already read the top of any list,
     * so this goes further down it until there is enough left.
     */
    fun observeCategory(category: ReaderCategory, type: ContentType?, force: Boolean): Flow<DiscoverSection> = cachedSection("category:${category.key}:$type", CATEGORY_MAX_AGE_MS, force) { emit ->
        val taste = currentTaste()
        val known = knownTitles()
        val lists = ArrayList<RankedList>()
        val mutex = Mutex()
        coroutineScope {
            for (source in chartSources().take(MAX_CATEGORY_SOURCES)) {
                launch {
                    val list = fetchCategory(source, category, type, known)
                    if (list.isEmpty()) return@launch
                    val partial = mutex.withLock {
                        lists += RankedList(list, 1.0)
                        DiscoverRanking.fuse(lists, taste)
                    }
                    emit(partial)
                }
            }
        }
        mutex.withLock { DiscoverRanking.fuse(lists, taste).take(MAX_TOP) }
    }

    fun observeTop(window: DiscoverWindow, filter: DiscoverFilter, force: Boolean): Flow<DiscoverSection> = // The name changes with the size of the list, so a shorter one cached earlier is not shown for the rest of its day
        cachedSection("top$MAX_TOP:${window.wireName}:${filter.type}:${filter.genre}", TOP_MAX_AGE_MS, force) { emit ->
            val lists = ArrayList<RankedList>()
            val sourceCharts = ArrayList<SourceChart>()
            val mutex = Mutex()
            suspend fun add(list: RankedList) {
                if (list.manga.isEmpty()) return
                val partial = mutex.withLock {
                    lists += list
                    DiscoverRanking.fuse(lists)
                }
                emit(partial)
            }
            coroutineScope {
                val semaphore = Semaphore(MAX_PARALLELISM)
                chartSources().map { source ->
                    launch {
                        semaphore.withPermit {
                            fetchSourceChart(source, window, filter)?.let { sourceChart ->
                                mutex.withLock { sourceCharts += sourceChart }
                                add(sourceChart.shown)
                            }
                        }
                    }
                }.joinAll()
                // Too few sites answered, or they all list the same titles: further down those lists, one site at a
                // time and no further than it takes, as this is requests a fuller answer would not have needed
                for (sourceChart in sourceCharts) {
                    var pages = 0
                    while (mutex.withLock { DiscoverRanking.fuse(lists).size } < MAX_TOP && pages <= MAX_TOP_EXTRA_PAGES) {
                        val previous = sourceChart.shown
                        val hasMore = sourceChart.goFurther(filter, isFirstStep = pages == 0)
                        if (sourceChart.shown !== previous) {
                            val partial = mutex.withLock {
                                lists[lists.indexOf(previous)] = sourceChart.shown
                                DiscoverRanking.fuse(lists)
                            }
                            emit(partial)
                        }
                        if (!hasMore) break
                        pages++
                    }
                }
            }
            mutex.withLock { DiscoverRanking.fuse(lists).take(MAX_TOP) }
        }

    fun observeNewFinds(filter: DiscoverFilter, force: Boolean): Flow<DiscoverSection> = cachedSection("new:${filter.type}:${filter.genre}", NEW_MAX_AGE_MS, force) { emit ->
        val taste = currentTaste()
        val known = knownTitles()
        val lists = ArrayList<List<Manga>>()
        val mutex = Mutex()
        coroutineScope {
            val semaphore = Semaphore(MAX_PARALLELISM)
            for (source in chartSources()) {
                launch {
                    semaphore.withPermit {
                        val list = fetchNewest(source, filter) ?: return@withPermit
                        val partial = mutex.withLock {
                            lists += list
                            DiscoverRanking.newFinds(lists, taste, known, MAX_NEW)
                        }
                        emit(partial)
                    }
                }
            }
        }
        mutex.withLock { DiscoverRanking.newFinds(lists, taste, known, MAX_NEW) }
    }

    /**
     * What the suggestions worker has already worked out for this reader. Never asked to do it again here:
     * it is a slow job with its own schedule.
     */
    suspend fun getForYou(filter: DiscoverFilter): List<Manga> {
        // Turning them off stops the worker, which leaves whatever it last stored
        if (!settings.isSuggestionsEnabled) {
            return emptyList()
        }
        val list = runCatchingCancellable { suggestions.getTopList(MAX_FOR_YOU * 4) }
            .onFailure { it.printStackTraceDebug() }
            .getOrDefault(emptyList())
        return list.applyReaderFilters().asSequence()
            .filter { DiscoverRanking.matchesGenre(it, filter.genre) }
            .filter { DiscoverRanking.matchesType(it, filter.type, (it.source as? ParserMangaSource)?.contentType) }
            .take(MAX_FOR_YOU)
            .toList()
    }

    /**
     * Shows what is cached straight away; if that is missing, out of date or a refresh was asked for, then
     * fetches. A section with nothing cached fills in as sources answer, while one replacing an older list
     * does it in one go, so the list does not shrink to one source's answer and grow back. A fetch that
     * finds nothing at all keeps the older list: a bad connection should not empty the screen.
     */
    private fun cachedSection(
        key: String,
        maxAgeMs: Long,
        force: Boolean,
        fetch: suspend (emit: suspend (List<Manga>) -> Unit) -> List<Manga>,
    ): Flow<DiscoverSection> = flow {
        val cached = cache.get(key)
        val isFresh = cached != null && System.currentTimeMillis() - cached.savedAt < maxAgeMs
        if (cached != null) {
            emit(DiscoverSection(cached.manga.applyReaderFilters(), isFinal = isFresh && !force))
        }
        if (isFresh && !force) {
            return@flow
        }
        val showPartial = cached == null
        val partials = Channel<List<Manga>>(Channel.CONFLATED)
        val result = coroutineScope {
            val job = async {
                try {
                    fetch { partials.trySend(it) }
                } finally {
                    partials.close()
                }
            }
            for (partial in partials) {
                if (showPartial) {
                    emit(DiscoverSection(partial.applyReaderFilters().take(MAX_TOP), isFinal = false))
                }
            }
            job.await()
        }
        if (result.isNotEmpty()) {
            cache.put(key, result)
            emit(DiscoverSection(result.applyReaderFilters(), isFinal = true))
        } else {
            emit(DiscoverSection(cached?.manga?.applyReaderFilters().orEmpty(), isFinal = true))
        }
    }

    /** Applied on the way out, not when saved: changing these settings takes effect without a refetch. */
    private fun List<Manga>.applyReaderFilters(): List<Manga> {
        val tagsBlacklist = blacklist
        return filter { manga ->
            !DiscoverRanking.isAdult(manga) && !manga.source.isNsfw() && manga !in tagsBlacklist
        }
    }

    private suspend fun fetchSourceChart(
        source: MangaSource,
        window: DiscoverWindow,
        filter: DiscoverFilter,
    ): SourceChart? = fetchFrom(source) { repository ->
        val order = DiscoverRanking.orderFor(window, repository.sortOrders) ?: return@fetchFrom null
        val list = repository.getFiltered(source, order.order, filter) ?: return@fetchFrom null
        SourceChart(source, order.order, order.weight, list)
    }

    /** One site's chart. Only the top of what it answered counts at first, so no site outweighs the others. */
    private inner class SourceChart(
        private val source: MangaSource,
        private val order: SortOrder,
        private val weight: Double,
        private var fetched: List<Manga>,
    ) {

        var shown = RankedList(fetched.take(MAX_PER_SOURCE), weight)
            private set

        /**
         * Adds what was already fetched if some was left out, as that costs nothing, and the next page otherwise.
         *
         * @return whether there may be more
         */
        suspend fun goFurther(filter: DiscoverFilter, isFirstStep: Boolean): Boolean {
            if (isFirstStep && fetched.size > shown.manga.size) {
                shown = RankedList(fetched, weight)
                return true
            }
            val next = fetchFrom(source) { repository ->
                repository.getFiltered(source, order, filter, offset = fetched.size)
            }.orEmpty()
            if (next.isEmpty()) {
                return false
            }
            fetched = fetched + next
            shown = RankedList(fetched, weight)
            return true
        }
    }

    private suspend fun fetchNewest(source: MangaSource, filter: DiscoverFilter): List<Manga>? = fetchFrom(source) { repository ->
        val order = DiscoverRanking.newestOrder(repository.sortOrders) ?: return@fetchFrom null
        repository.getFiltered(source, order, filter)?.take(MAX_PER_SOURCE)
    }

    /** Whatever a site got through before it failed or ran out of time still counts. */
    private suspend fun fetchCategory(
        source: MangaSource,
        category: ReaderCategory,
        type: ContentType?,
        known: Set<String>,
    ): List<Manga> {
        val fresh = ArrayList<Manga>(MAX_PER_SOURCE)
        sourceLocks.computeIfAbsent(source.name) { Mutex() }.withLock {
            runCatchingCancellable {
                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                    fillCategory(repositoryFactory.create(source), source, category, type, known, fresh)
                }
            }.onFailure { it.printStackTraceDebug() }
        }
        return fresh.take(MAX_PER_SOURCE)
    }

    private suspend fun fillCategory(
        repository: MangaRepository,
        source: MangaSource,
        category: ReaderCategory,
        type: ContentType?,
        known: Set<String>,
        into: MutableList<Manga>,
    ) {
        val order = DiscoverRanking.orderFor(DiscoverWindow.MONTH, repository.sortOrders)?.order
            ?: SortOrder.RATING.takeIf { it in repository.sortOrders }
            ?: return
        val options = repository.getFilterOptions()
        val matched = category.tags.mapNotNull { DiscoverRanking.findTag(options.availableTags, it, TAG_MATCH_THRESHOLD) }
        val first = matched.firstOrNull() ?: return
        val tags = if (repository.filterCapabilities.isMultipleTagsSupported) matched.toSet() else setOf(first)
        val types = HashSet<ContentType>()
        if (type != null) {
            if (type in options.availableContentTypes) {
                types += type
            } else if (!source.servesOnly(type)) {
                return
            }
        }
        val filter = MangaListFilter(tags = tags, types = types)
        var offset = 0
        repeat(MAX_CATEGORY_PAGES) {
            val list = repository.getList(offset = offset, order = order, filter = filter)
            if (list.isEmpty()) return
            offset += list.size
            list.filterTo(into) { manga ->
                !DiscoverRanking.isKnown(manga, known) &&
                    // Asked for only one of them, so what the site lists is the only check of the other
                    (tags.size == matched.size || manga.tags.isEmpty() || matched.all { tag -> DiscoverRanking.findTag(manga.tags, tag.title, TAG_MATCH_THRESHOLD) != null })
            }
            if (into.size >= MAX_PER_SOURCE) return
        }
    }

    /** One slow or blocked site must only cost the list its own entries. */
    private suspend fun <T : Any> fetchFrom(source: MangaSource, block: suspend (MangaRepository) -> T?): T? = runCatchingCancellable {
        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            block(repositoryFactory.create(source))
        }
    }.onFailure {
        it.printStackTraceDebug()
    }.getOrNull()

    /** @return null if this source has no way of answering the filter, rather than an unfiltered list */
    private suspend fun MangaRepository.getFiltered(
        source: MangaSource,
        order: SortOrder,
        filter: DiscoverFilter,
        offset: Int = 0,
    ): List<Manga>? {
        val tags = HashSet<MangaTag>()
        val types = HashSet<ContentType>()
        if (filter.genre != null || filter.type != null) {
            val options = getFilterOptions()
            if (filter.genre != null) {
                tags += DiscoverRanking.findTag(options.availableTags, filter.genre, TAG_MATCH_THRESHOLD)
                    ?: return null
            }
            if (filter.type != null) {
                if (filter.type in options.availableContentTypes) {
                    types += filter.type
                } else if (!source.servesOnly(filter.type)) {
                    return null
                }
            }
        }
        return getList(offset = offset, order = order, filter = MangaListFilter(tags = tags, types = types))
    }

    /**
     * A source that cannot filter by type still answers when it only carries that type. "Manga" is the
     * parsers' default, which also covers mixed catalogues, so that alone counts as an answer for manga.
     */
    private fun MangaSource.servesOnly(type: ContentType): Boolean {
        val own = (this as? ParserMangaSource)?.contentType ?: return false
        return own == type
    }

    /** The sources the reader actually reads on first: what is there is what they would open anyway. */
    private suspend fun chartSources(): List<MangaSource> = sourcesLock.withLock {
        sources?.takeIf { it.isYoungerThan(SOURCES_MAX_AGE_MS) }?.value?.let { return it }
        val enabled = sourcesRepository.getEnabledSources().filterNot { it.isNsfw() }
        val used = runCatchingCancellable { history.getPopularSources(MAX_SOURCES) }.getOrDefault(emptyList())
        // By name: enabled sources come wrapped with their settings, so they never equal the plain ones from history
        val enabledByName = enabled.associateBy { it.name }
        (used.mapNotNull { enabledByName[it.name] } + enabled).distinctBy { it.name }.take(MAX_SOURCES)
            .also { sources = if (it.isEmpty()) null else Timed(it) }
    }

    /**
     * The reader's taste with what they said they are not interested in counted against it. Only the taste: the lists
     * themselves are kept as they came, and what was passed on is left out on the way to the screen, so that taking a
     * pass back brings the title back at once.
     */
    private suspend fun currentTaste(): Map<String, Double> {
        val taste = runCatchingCancellable { profiles.get().taste }.getOrDefault(emptyMap())
        return DiscoverRanking.applyDismissals(taste, dismissals.get().tagLists)
    }

    private suspend fun knownTitles(): Set<String> = runCatchingCancellable { profiles.get().knownTitles }.getOrDefault(emptySet())

    private class Timed<T>(val value: T) {
        private val takenAt = System.currentTimeMillis()

        fun isYoungerThan(ms: Long) = System.currentTimeMillis() - takenAt < ms
    }

    private companion object {

        const val TOP_MAX_AGE_MS = 3 * 60 * 60 * 1000L
        const val NEW_MAX_AGE_MS = 6 * 60 * 60 * 1000L
        const val CATEGORY_MAX_AGE_MS = 12 * 60 * 60 * 1000L

        const val SOURCES_MAX_AGE_MS = 5 * 60 * 1000L

        /** Rows of what the reader keeps coming back to: how many sites and pages each may ask. */
        const val MAX_CATEGORY_SOURCES = 4
        const val MAX_CATEGORY_PAGES = 3

        /** Long enough for a site that is merely slow. A blocked one would otherwise hold up the finished list. */
        const val SOURCE_TIMEOUT_MS = 8_000L

        /** Each is a different website, so this burdens none of them, and the first answers show up sooner. */
        const val MAX_PARALLELISM = 6

        /** Every one costs a request per chart, so this is what the first open of the screen costs. */
        const val MAX_SOURCES = 8
        const val MAX_PER_SOURCE = 24
        const val MAX_TOP = 100

        /** How far down one site's chart to go for a list that is still short. */
        const val MAX_TOP_EXTRA_PAGES = 6
        const val MAX_NEW = 36
        const val MAX_FOR_YOU = 24

        const val TAG_MATCH_THRESHOLD = 0.4f
        const val MAX_OWN_GENRES = 6

        val COMMON_GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Romance", "Isekai", "Reincarnation",
            "Martial arts", "Slice of life", "School life", "Mystery", "Horror", "Psychological",
            "Supernatural", "Sci-fi", "Sports", "Historical", "Thriller", "Villainess",
        )
    }
}

@file:OptIn(org.koitharu.kotatsu.parsers.InternalParsersApi::class)

package org.koitharu.kotatsu.core.parser.external

import android.content.ContentResolver
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.cache.MemoryContentCache
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.suspendlazy.suspendLazy
import java.util.EnumSet

class ExternalMangaRepository(
    contentResolver: ContentResolver,
    override val source: ExternalMangaSource,
    cache: MemoryContentCache,
) : CachingMangaRepository(cache) {

    private val contentSource = ExternalPluginContentSource(contentResolver, source)

    @Volatile
    private var capsValue: ExternalPluginContentSource.MangaSourceCapabilities? = null

    @Volatile
    private var capsFailedAt = 0L

    /** Loaded once; a failed load (plugin briefly unavailable) is retried after [CAPS_RETRY_MS], not cached forever. */
    private val capabilities: ExternalPluginContentSource.MangaSourceCapabilities?
        get() {
            capsValue?.let { return it }
            val failedAt = capsFailedAt
            if (failedAt != 0L && SystemClock.elapsedRealtime() - failedAt < CAPS_RETRY_MS) return null
            return runCatching {
                contentSource.getCapabilities()
            }.onFailure {
                it.printStackTraceDebug()
            }.getOrNull().also {
                capsValue = it
                capsFailedAt = if (it == null) SystemClock.elapsedRealtime() else 0L
            }
        }

    private val filterOptions = suspendLazy(initializer = contentSource::getListFilterOptions)

    override val sortOrders: Set<SortOrder>
        get() = capabilities?.availableSortOrders ?: EnumSet.of(SortOrder.POPULARITY)

    override val filterCapabilities: MangaListFilterCapabilities
        get() = capabilities?.listFilterCapabilities ?: MangaListFilterCapabilities()

    override var defaultSortOrder: SortOrder
        get() = capabilities?.availableSortOrders?.firstOrNull() ?: SortOrder.ALPHABETICAL
        set(value) = Unit

    override suspend fun getFilterOptions(): MangaListFilterOptions = filterOptions.get()

    override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> = runInterruptible(Dispatchers.IO) {
        capabilities // warm the cache here on IO so UI-thread getters usually find it loaded
        contentSource.getList(offset, order ?: defaultSortOrder, filter ?: MangaListFilter.EMPTY)
    }

    override suspend fun getDetailsImpl(manga: Manga): Manga = runInterruptible(Dispatchers.IO) {
        contentSource.getDetails(manga)
    }

    override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> = runInterruptible(Dispatchers.IO) {
        contentSource.getPages(chapter)
    }

    override suspend fun getPageUrl(page: MangaPage): String = runInterruptible(Dispatchers.IO) {
        contentSource.getPageUrl(page.url)
    }

    override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList() // TODO

    private companion object {
        const val CAPS_RETRY_MS = 60_000L
    }
}

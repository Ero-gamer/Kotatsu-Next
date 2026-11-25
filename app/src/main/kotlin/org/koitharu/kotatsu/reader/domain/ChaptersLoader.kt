package org.koitharu.kotatsu.reader.domain

import android.util.LongSparseArray
import androidx.annotation.CheckResult
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.data.erase.HiddenImagesRepository
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import javax.inject.Inject
import kotlin.math.abs

private const val PAGES_TRIM_THRESHOLD = 120

/**
 * Leaves out [hidden] pages, except that a chapter always keeps one: the reader has no notion of a
 * chapter with no pages, and would report it as not found rather than move on.
 */
internal fun withoutHidden(pages: List<ReaderPage>, hidden: Set<Long>): List<ReaderPage> {
    if (hidden.isEmpty()) {
        return pages
    }
    val visibleChapters = pages.mapNotNullTo(HashSet()) { page -> page.chapterId.takeIf { page.id !in hidden } }
    val keptChapters = HashSet<Long>()
    return pages.filter { page ->
        page.id !in hidden || (page.chapterId !in visibleChapters && keptChapters.add(page.chapterId))
    }
}

/**
 * Where to go for a saved position in [pages]: the page itself or, if it has since been hidden, the next
 * one still there, or else the last one before it. Null if the chapter is not in the list at all.
 *
 * Goes by the pages' own numbers and not by where they are in the list: right-to-left readers hand over the
 * list back to front, where the first page numbered at least [page] is the last page of the chapter.
 */
internal fun List<ReaderPage>.indexOfPosition(chapterId: Long, page: Int): Int? {
    var next: Int? = null
    var previous: Int? = null
    for (i in indices) {
        val item = this[i]
        if (item.chapterId != chapterId) {
            continue
        }
        when {
            item.index == page -> return i
            item.index > page -> if (next == null || item.index < this[next].index) next = i
            else -> if (previous == null || item.index > this[previous].index) previous = i
        }
    }
    return next ?: previous
}

@ViewModelScoped
class ChaptersLoader @Inject constructor(
    private val mangaRepositoryFactory: MangaRepository.Factory,
    private val hiddenImages: HiddenImagesRepository,
) {

    private val chapters = LongSparseArray<MangaChapter>()
    private val chapterPages = ChapterPages()
    private val mutex = Mutex()

    val size: Int
        get() = chapters.size()

    suspend fun init(manga: MangaDetails) = mutex.withLock {
        chapters.clear()
        manga.allChapters.forEach {
            chapters.put(it.id, it)
        }
    }

    /**
     * Adds the chapter after or before [currentId], dropping the one at the far end once there are many pages.
     *
     * The chapter at the far end is kept while it is [readingChapterId] or right next to it. Inside a chapter of a page or two
     * both ends are in reach at once: loading the one before dropped the one just left, which was then loaded again, dropping
     * the one before, for as long as the reader kept scrolling - neither end could ever be reached.
     */
    suspend fun loadPrevNextChapter(
        manga: MangaDetails,
        currentId: Long,
        isNext: Boolean,
        skipDuplicates: Boolean = false,
        readingChapterId: Long? = null,
    ): Boolean {
        val chapters = manga.allChapters
        val predicate: (MangaChapter) -> Boolean = { it.id == currentId }
        val index = if (isNext) chapters.indexOfFirst(predicate) else chapters.indexOfLast(predicate)
        if (index == -1) return false
        val newChapter = chapters.getOrNull(chapters.neighbourIndex(index, if (isNext) 1 else -1, skipDuplicates))
            ?: return false
        val newPages = loadChapter(newChapter.id)
        mutex.withLock {
            if (chapterPages.chaptersSize > 1 && chapterPages.size > PAGES_TRIM_THRESHOLD) {
                val farChapterId = if (isNext) chapterPages.first().chapterId else chapterPages.last().chapterId
                if (!isNextToReading(chapters, farChapterId, readingChapterId)) {
                    if (isNext) {
                        chapterPages.removeFirst()
                    } else {
                        chapterPages.removeLast()
                    }
                }
            }
            if (isNext) {
                chapterPages.addLast(newChapter.id, newPages)
            } else {
                chapterPages.addFirst(newChapter.id, newPages)
            }
        }
        return true
    }

    @CheckResult
    suspend fun loadSingleChapter(chapterId: Long): Boolean {
        val pages = loadChapter(chapterId)
        return mutex.withLock {
            chapterPages.clear()
            chapterPages.addLast(chapterId, pages)
            pages.isNotEmpty()
        }
    }

    fun peekChapter(chapterId: Long): MangaChapter? = chapters[chapterId]

    fun hasPages(chapterId: Long): Boolean = synchronized(chapterPages) { chapterId in chapterPages }

    fun getPages(chapterId: Long): List<MangaPage> = synchronized(chapterPages) {
        return chapterPages.subList(chapterId).map { it.toMangaPage() }
    }

    fun getPagesCount(chapterId: Long): Int = synchronized(chapterPages) { chapterPages.size(chapterId) }

    fun last() = synchronized(chapterPages) { chapterPages.last() }

    fun first() = synchronized(chapterPages) { chapterPages.first() }

    /**
     * The pages to show, without those known to be nothing but a hidden image. They are only left out here
     * and not when loading: `ReaderPage.index` and [getPagesCount] are what saved progress is made of, and
     * must go on describing the chapter as the source serves it.
     */
    suspend fun snapshot(): List<ReaderPage> = withoutHidden(synchronized(chapterPages) { chapterPages.toList() }, hiddenImages.getHiddenPages())

    fun observeHiddenPages() = hiddenImages.observeHiddenPages()

    /** The page right before or after another, hidden or not, as long as it is in the same chapter. */
    fun findNeighbour(pageId: Long, isBefore: Boolean): ReaderPage? {
        val pages = synchronized(chapterPages) { chapterPages.toList() }
        val index = pages.indexOfFirst { it.id == pageId }
        if (index < 0) {
            return null
        }
        return pages.getOrNull(if (isBefore) index - 1 else index + 1)?.takeIf { it.chapterId == pages[index].chapterId }
    }

    private fun isNextToReading(chapters: List<MangaChapter>, chapterId: Long, readingChapterId: Long?): Boolean {
        if (readingChapterId == null) {
            return false
        }
        val index = chapters.indexOfFirst { it.id == chapterId }
        val readingIndex = chapters.indexOfFirst { it.id == readingChapterId }
        return index >= 0 && readingIndex >= 0 && abs(index - readingIndex) <= 1
    }

    private suspend fun loadChapter(chapterId: Long): List<ReaderPage> {
        val chapter = checkNotNull(chapters[chapterId]) { "Requested chapter not found" }
        val repo = mangaRepositoryFactory.create(chapter.source)
        return repo.getPages(chapter).mapIndexed { index, page ->
            ReaderPage(page, index, chapterId)
        }
    }
}

/**
 * Index of the neighbouring chapter in direction [step] (+1 / -1). With [skipDuplicates] it jumps over copies of the
 * chapter at [from] (same volume and number, e.g. other scanlators), so the next real chapter is reached.
 * Chapters without a number are never treated as copies. The result may be out of range (= no neighbour).
 */
fun List<MangaChapter>.neighbourIndex(from: Int, step: Int, skipDuplicates: Boolean): Int {
    var i = from + step
    val current = getOrNull(from)
    if (!skipDuplicates || current == null || current.number <= 0f) return i
    while (true) {
        val c = getOrNull(i) ?: return i
        if (c.number != current.number || c.volume != current.volume) return i
        i += step
    }
}

/**
 * Whole chapters absent between two chapters, judged by numbers only: floor(hi) - floor(lo) - 1.
 * Returns 0 when unknown (number <= 0), when the branch or volume differs (numbering may restart), or when adjacent.
 */
fun missingChaptersBetween(a: MangaChapter, b: MangaChapter): Int {
    if (a.branch != b.branch || a.volume != b.volume) return 0
    val lo = minOf(a.number, b.number)
    val hi = maxOf(a.number, b.number)
    if (lo <= 0f) return 0
    return (kotlin.math.floor(hi) - kotlin.math.floor(lo) - 1f).toInt().coerceAtLeast(0)
}

/** Chapter number without a trailing ".0". */
fun formatChapterNumber(number: Float): String = if (number % 1f == 0f) number.toInt().toString() else number.toString()

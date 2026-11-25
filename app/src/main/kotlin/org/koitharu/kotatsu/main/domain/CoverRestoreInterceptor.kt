package org.koitharu.kotatsu.main.domain

import androidx.collection.ArraySet
import coil3.intercept.Interceptor
import coil3.network.HttpException
import coil3.request.ErrorResult
import coil3.request.ImageResult
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.exceptions.CloudFlareBlockedException
import org.koitharu.kotatsu.core.exceptions.CloudFlareProtectedException
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.bookmarkKey
import org.koitharu.kotatsu.core.util.ext.mangaKey
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.io.EOFException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import javax.inject.Inject
import javax.net.ssl.SSLException

class CoverRestoreInterceptor @Inject constructor(
    private val dataRepository: MangaDataRepository,
    private val bookmarksRepository: BookmarksRepository,
    private val repositoryFactory: MangaRepository.Factory,
) : Interceptor {

    private val blacklist = Collections.synchronizedSet(ArraySet<String>())

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val result = chain.proceed()
        if (result is ErrorResult && result.throwable.shouldRestore()) {
            request.extras[bookmarkKey]?.let {
                return if (restoreBookmark(it)) {
                    chain.withRequest(request.newBuilder().build()).proceed()
                } else {
                    result
                }
            }
            request.extras[mangaKey]?.let {
                return if (restoreManga(it)) {
                    chain.withRequest(request.newBuilder().build()).proceed()
                } else {
                    result
                }
            }
        }
        return result
    }

    private suspend fun restoreManga(manga: Manga): Boolean {
        val key = manga.publicUrl
        if (!blacklist.add(key)) {
            return false
        }
        val restored = runCatchingCancellable {
            restoreMangaImpl(manga)
        }.onFailure { e ->
            e.printStackTraceDebug()
        }.getOrDefault(false)
        if (restored) {
            blacklist.remove(key)
        }
        return restored
    }

    private suspend fun restoreMangaImpl(manga: Manga): Boolean {
        if (dataRepository.findMangaById(manga.id, withChapters = false) == null || manga.isLocal) {
            return false
        }
        val repo = repositoryFactory.create(manga.source)
        val list = repo.getList(0, SortOrder.RELEVANCE, MangaListFilter(query = manga.title))
        val fixed = list.find { it.id == manga.id }
        if (fixed == null) {
            // Ids come from urls, so a site that moved lists the same manga under another id. Stored is the one to keep.
            val moved = list.singleOrNull { it.title == manga.title } ?: return false
            if (moved.coverUrl.isNullOrEmpty() || moved.coverUrl == manga.coverUrl) {
                return false
            }
            dataRepository.updateCover(manga.copy(coverUrl = moved.coverUrl, largeCoverUrl = moved.largeCoverUrl))
            return true
        }
        return if (fixed != manga) {
            dataRepository.storeManga(fixed, replaceExisting = true)
            fixed.coverUrl != manga.coverUrl
        } else {
            false
        }
    }

    private suspend fun restoreBookmark(bookmark: Bookmark): Boolean {
        val key = bookmark.imageUrl
        if (!blacklist.add(key)) {
            return false
        }
        val restored = runCatchingCancellable {
            restoreBookmarkImpl(bookmark)
        }.onFailure { e ->
            e.printStackTraceDebug()
        }.getOrDefault(false)
        if (restored) {
            blacklist.remove(key)
        }
        return restored
    }

    private suspend fun restoreBookmarkImpl(bookmark: Bookmark): Boolean {
        if (bookmark.manga.isLocal) {
            return false
        }
        val repo = repositoryFactory.create(bookmark.manga.source)
        val chapter = repo.getDetails(bookmark.manga).chapters?.findById(bookmark.chapterId) ?: return false
        val page = repo.getPages(chapter)[bookmark.page]
        val imageUrl = page.preview.ifNullOrEmpty { page.url }
        return if (imageUrl != bookmark.imageUrl) {
            bookmarksRepository.updateBookmark(bookmark, imageUrl)
            true
        } else {
            false
        }
    }

    /**
     * Restoring means searching the source for the title: only worth it when the cover itself is gone (404/410) or
     * unusable. Offline, timeouts, a challenge page or a rate limit say nothing about the cover.
     */
    private fun Throwable.shouldRestore(): Boolean = when (this) {
        is HttpException -> response.code == HttpURLConnection.HTTP_NOT_FOUND || response.code == HttpURLConnection.HTTP_GONE

        is SocketTimeoutException, is SocketException, is UnknownHostException, is SSLException, is EOFException,
        is CloudFlareProtectedException, is CloudFlareBlockedException,
        -> false

        else -> this is Exception
    }
}

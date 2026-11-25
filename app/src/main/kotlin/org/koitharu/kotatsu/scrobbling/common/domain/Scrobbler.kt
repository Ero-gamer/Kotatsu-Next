package org.koitharu.kotatsu.scrobbling.common.domain

import android.os.SystemClock
import androidx.annotation.FloatRange
import androidx.core.text.parseAsHtml
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.util.ext.findKeyByValue
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.findById
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblerRepository
import org.koitharu.kotatsu.scrobbling.common.data.ScrobblingEntity
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerManga
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerMangaInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerService
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblerUser
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingStatus
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

private const val INFO_RETRY_MS = 5 * 60 * 1000L

abstract class Scrobbler(
    protected val db: MangaDatabase,
    val scrobblerService: ScrobblerService,
    private val repository: ScrobblerRepository,
    private val mangaRepositoryFactory: MangaRepository.Factory,
) {

    // Entries are read from parallel coroutines: a plain sparse array is not safe here.
    private val infoCache = ConcurrentHashMap<Long, ScrobblerMangaInfo>()
    private val infoFailedAt = ConcurrentHashMap<Long, Long>()
    protected val statuses = EnumMap<ScrobblingStatus, String>(ScrobblingStatus::class.java)

    val user: Flow<ScrobblerUser> = flow {
        repository.cachedUser?.let {
            emit(it)
        }
        runCatchingCancellable {
            repository.loadUser()
        }.onSuccess {
            emit(it)
        }.onFailure {
            it.printStackTraceDebug()
        }
    }

    val isEnabled: Boolean
        get() = repository.isAuthorized

    suspend fun authorize(authCode: String): ScrobblerUser {
        repository.authorize(authCode)
        return repository.loadUser()
    }

    fun logout() {
        repository.logout()
    }

    suspend fun findManga(query: String, offset: Int): List<ScrobblerManga> = repository.findManga(query, offset)

    suspend fun linkManga(mangaId: Long, targetId: Long) {
        repository.createRate(mangaId, targetId)
    }

    suspend fun scrobble(manga: Manga, chapterId: Long) {
        var chapters = manga.chapters
        if (chapters.isNullOrEmpty()) {
            chapters = mangaRepositoryFactory.create(manga.source).getDetails(manga).chapters
        }
        requireNotNull(chapters)
        val chapter = checkNotNull(chapters.findById(chapterId)) {
            "Chapter $chapterId not found in this manga"
        }
        val number = if (chapter.number > 0f) {
            chapter.number.toInt()
        } else {
            chapters = chapters.filter { x -> x.branch == chapter.branch }
            chapters.indexOf(chapter) + 1
        }
        val entity = db.getScrobblingDao().find(scrobblerService.id, manga.id) ?: return
        repository.updateRate(entity.id, entity.mangaId, number)
    }

    suspend fun getScrobblingInfoOrNull(mangaId: Long): ScrobblingInfo? {
        val entity = db.getScrobblingDao().find(scrobblerService.id, mangaId) ?: return null
        return entity.toScrobblingInfo()
    }

    abstract suspend fun updateScrobblingInfo(
        mangaId: Long,
        @FloatRange(from = 0.0, to = 1.0) rating: Float,
        status: ScrobblingStatus?,
        comment: String?,
    )

    fun observeScrobblingInfo(mangaId: Long): Flow<ScrobblingInfo?> = db.getScrobblingDao().observe(scrobblerService.id, mangaId)
        .map { it?.toScrobblingInfo() }

    fun observeAllScrobblingInfo(): Flow<List<ScrobblingInfo>> = db.getScrobblingDao().observe(scrobblerService.id)
        .map { entities ->
            coroutineScope {
                entities.map {
                    async {
                        it.toScrobblingInfo()
                    }
                }.awaitAll()
            }.filterNotNull()
        }

    suspend fun unregisterScrobbling(mangaId: Long) {
        repository.unregister(mangaId)
    }

    protected suspend fun getMangaInfo(id: Long): ScrobblerMangaInfo = repository.getMangaInfo(id)

    private suspend fun ScrobblingEntity.toScrobblingInfo(): ScrobblingInfo? {
        val mangaInfo = infoCache[targetId] ?: run {
            // A title the service could not give a moment ago is not asked for again on every list refresh.
            val failedAt = infoFailedAt[targetId]
            if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < INFO_RETRY_MS) {
                return null
            }
            runCatchingCancellable {
                getMangaInfo(targetId)
            }.onFailure {
                it.printStackTraceDebug()
                infoFailedAt[targetId] = SystemClock.elapsedRealtime()
            }.onSuccess {
                infoCache[targetId] = it
                infoFailedAt.remove(targetId)
            }.getOrNull() ?: return null
        }
        return ScrobblingInfo(
            scrobbler = scrobblerService,
            mangaId = mangaId,
            targetId = targetId,
            status = statuses.findKeyByValue(status),
            chapter = chapter,
            comment = comment,
            rating = rating,
            title = mangaInfo.name,
            coverUrl = mangaInfo.cover,
            description = mangaInfo.descriptionHtml.parseAsHtml().sanitize(),
            externalUrl = mangaInfo.url,
        )
    }
}

suspend fun Scrobbler.tryScrobble(manga: Manga, chapterId: Long): Boolean = runCatchingCancellable {
    scrobble(manga, chapterId)
}.onFailure {
    it.printStackTraceDebug()
}.isSuccess

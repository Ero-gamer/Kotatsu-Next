package org.koitharu.kotatsu.alternatives.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.model.chaptersCount
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class AutoFixUseCase @Inject constructor(
    private val mangaRepositoryFactory: MangaRepository.Factory,
    private val alternativesUseCase: AlternativesUseCase,
    private val migrateUseCase: MigrateUseCase,
    private val mangaDataRepository: MangaDataRepository,
) {

    suspend operator fun invoke(mangaId: Long): Pair<Manga, Manga?> {
        val seed = checkNotNull(
            mangaDataRepository.findMangaById(mangaId, withChapters = true),
        ) { "Manga $mangaId not found" }.getDetailsSafe()
        if (seed.isHealthy()) {
            return seed to null // no fix required
        }
        val sourceScope = alternativesUseCase.getSourceScopeOptions(
            ref = seed.source,
            sameLanguageOnly = true,
        ).defaultScope
        val replacement = alternativesUseCase(seed, sourceScope = sourceScope)
            .filter { it.isHealthy() }
            .runningFold<Manga, Manga?>(null) { best, candidate ->
                if (best == null || best < candidate) {
                    candidate
                } else {
                    best
                }
            }.selectLastWithTimeout(4, 40, TimeUnit.SECONDS)
        migrateUseCase(seed, replacement ?: throw NoAlternativesException(ParcelableManga(seed)))
        return seed to replacement
    }

    private suspend fun Manga.isHealthy(): Boolean = runCatchingCancellable {
        val repo = mangaRepositoryFactory.create(source)
        val details = if (this.chapters != null) this else repo.getDetails(this)
        val firstChapter = details.chapters?.firstOrNull() ?: return@runCatchingCancellable false
        val pageUrl = repo.getPageUrl(repo.getPages(firstChapter).first())
        pageUrl.toHttpUrlOrNull() != null
    }.getOrDefault(false)

    private suspend fun Manga.getDetailsSafe() = runCatchingCancellable {
        mangaRepositoryFactory.create(source).getDetails(this)
    }.getOrDefault(this)

    private operator fun Manga.compareTo(other: Manga) = chaptersCount().compareTo(other.chaptersCount())

    /** The last value seen once [minCount] + 1 values arrived, or after [timeout] with whatever arrived by then. */
    private suspend fun <T> Flow<T>.selectLastWithTimeout(
        minCount: Int,
        timeout: Long,
        timeUnit: TimeUnit,
    ): T? {
        var last: T? = null
        withTimeoutOrNull(timeUnit.toMillis(timeout)) {
            withIndex().firstOrNull { (index, value) ->
                last = value
                index >= minCount
            }
        }
        return last
    }

    class NoAlternativesException(val seed: ParcelableManga) : NoSuchElementException()
}

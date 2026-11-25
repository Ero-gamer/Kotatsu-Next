package org.koitharu.kotatsu.download.ui.worker

import android.os.SystemClock
import androidx.collection.MutableObjectLongMap
import kotlinx.coroutines.delay
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.ParserMangaRepository
import org.koitharu.kotatsu.parsers.model.MangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadSlowdownDispatcher @Inject constructor(
    private val mangaRepositoryFactory: MangaRepository.Factory,
) {
    private val timeMap = MutableObjectLongMap<MangaSource>()
    private val defaultDelay = 1_600L

    suspend fun delay(source: MangaSource) {
        val repo = mangaRepositoryFactory.create(source) as? ParserMangaRepository ?: return
        if (!repo.isSlowdownEnabled()) {
            return
        }
        // Reserve the slot under the lock: callers arriving together get consecutive slots instead of all
        // seeing the same "last request" and firing at once.
        val now = SystemClock.elapsedRealtime()
        val slot = synchronized(timeMap) {
            val next = maxOf(now, timeMap.getOrDefault(source, -defaultDelay) + defaultDelay)
            timeMap[source] = next
            next
        }
        if (slot > now) {
            delay(slot - now)
        }
    }
}

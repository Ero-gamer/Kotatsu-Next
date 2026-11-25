package org.koitharu.kotatsu.stats.domain

import androidx.collection.LongSparseArray
import androidx.collection.set
import dagger.hilt.android.ViewModelLifecycle
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.RetainedLifecycleCoroutineScope
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.stats.data.StatsEntity
import javax.inject.Inject

@ViewModelScoped
class StatsCollector @Inject constructor(
    private val db: MangaDatabase,
    private val settings: AppSettings,
    lifecycle: ViewModelLifecycle,
) {

    private val viewModelScope = RetainedLifecycleCoroutineScope(lifecycle)
    private val stats = LongSparseArray<Entry>(1)

    @Synchronized
    fun onStateChanged(mangaId: Long, state: ReaderState) {
        if (!settings.isStatsEnabled) {
            return
        }
        val now = System.currentTimeMillis()
        val entry = stats[mangaId]
        if (entry == null) {
            stats[mangaId] = Entry(
                state = state,
                stats = StatsEntity(
                    mangaId = mangaId,
                    startedAt = now,
                    duration = 0,
                    pages = 0,
                ),
                committedAt = now,
            )
            return
        }
        val pagesDelta = if (entry.state.page != state.page || entry.state.chapterId != state.chapterId) 1 else 0
        // The stored state must follow the reader, or every call after the first page turn counts as a new page.
        val newEntry = entry.copy(
            state = state,
            stats = StatsEntity(
                mangaId = mangaId,
                startedAt = entry.stats.startedAt,
                duration = now - entry.stats.startedAt,
                pages = entry.stats.pages + pagesDelta,
            ),
        )
        // One write per page turn is wasteful: write at most every COMMIT_INTERVAL_MS, the rest on pause.
        if (now - entry.committedAt >= COMMIT_INTERVAL_MS) {
            stats[mangaId] = newEntry.copy(committedAt = now, isDirty = false)
            commit(newEntry.stats)
        } else {
            stats[mangaId] = newEntry.copy(isDirty = true)
        }
    }

    @Synchronized
    fun onPause(mangaId: Long) {
        val entry = stats[mangaId]
        stats.remove(mangaId)
        if (entry != null && entry.isDirty) {
            commit(entry.stats)
        }
    }

    private fun commit(entity: StatsEntity) {
        viewModelScope.launch(Dispatchers.Default) {
            runCatchingCancellable {
                db.getStatsDao().upsert(entity)
            }.onFailure { e ->
                e.printStackTraceDebug()
            }
        }
    }

    private data class Entry(
        val state: ReaderState,
        val stats: StatsEntity,
        val committedAt: Long,
        val isDirty: Boolean = false,
    )

    private companion object {

        const val COMMIT_INTERVAL_MS = 15_000L
    }
}

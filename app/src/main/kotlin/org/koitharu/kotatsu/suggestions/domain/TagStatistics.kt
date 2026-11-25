package org.koitharu.kotatsu.suggestions.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.db.MangaDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How common each tag is across everything the app has stored (every list browsed, every suggestion, every
 * manga opened, from every source). That is the background a reader's taste is measured against: reading a
 * lot of `action` says little when nearly everything is tagged `action`, while reading a lot of `villainess`
 * says a great deal. Counted on the device from what is already in its database.
 */
@Singleton
class TagStatistics @Inject constructor(
    private val db: MangaDatabase,
) {

    private val mutex = Mutex()
    private var cached: Snapshot? = null

    /** Cached for a few minutes: this is a full scan of the tag tables. */
    suspend fun snapshot(): Snapshot = mutex.withLock {
        cached?.takeIf { System.currentTimeMillis() - it.takenAt < MAX_AGE_MS }?.let { return it }
        val dao = db.getTagsDao()
        Snapshot(
            documentFrequency = dao.countMangaPerTag().associate { it.title to it.count },
            total = dao.countTaggedManga(),
            takenAt = System.currentTimeMillis(),
        ).also { cached = it }
    }

    class Snapshot(
        private val documentFrequency: Map<String, Int>,
        private val total: Int,
        val takenAt: Long,
    ) {

        /** How many stored manga carry [title]. */
        fun frequency(title: String): Int = documentFrequency[SuggestionRanking.normalizeTag(title)] ?: 0

        fun rarity(title: String): Double = SuggestionRanking.idf(total, frequency(title))
    }

    private companion object {
        const val MAX_AGE_MS = 10 * 60 * 1000L
    }
}

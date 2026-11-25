package org.koitharu.kotatsu.core.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import org.koitharu.kotatsu.core.db.entity.PageStatsEntity

@Dao
interface PageStatsDao {

    @Query("SELECT * FROM page_stats WHERE page_key = :key")
    suspend fun find(key: Long): PageStatsEntity?

    @Upsert
    suspend fun upsertAll(entities: List<PageStatsEntity>)

    /** Streamed pages only (owner 0): local archives' rows are permanent. */
    @Query("DELETE FROM page_stats WHERE owner = 0 AND updated_at < :before")
    suspend fun deleteStreamedOlderThan(before: Long)

    /** Keeps only the [keep] most recently measured streamed rows. */
    @Query(
        "DELETE FROM page_stats WHERE owner = 0 AND page_key IN (SELECT page_key FROM page_stats " +
            "WHERE owner = 0 ORDER BY updated_at DESC LIMIT -1 OFFSET :keep)",
    )
    suspend fun trimStreamed(keep: Int)

    @Query("SELECT DISTINCT owner FROM page_stats WHERE owner != 0")
    suspend fun owners(): List<Long>

    @Query("DELETE FROM page_stats WHERE owner IN (:owners)")
    suspend fun deleteByOwners(owners: List<Long>)
}

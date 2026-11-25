package org.koitharu.kotatsu.local.data.optimize

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OptimizeTaskDao {

    @Insert
    suspend fun insert(entity: OptimizeTaskEntity): Long

    @Query("SELECT * FROM optimize_tasks ORDER BY created_at DESC")
    fun observeAll(): Flow<List<OptimizeTaskEntity>>

    @Query("SELECT * FROM optimize_tasks WHERE id = :id")
    suspend fun find(id: Long): OptimizeTaskEntity?

    /** Active (queued / running / paused) runs of the same manga with the same options: a repeat is refused. */
    @Query(
        "SELECT COUNT(*) FROM optimize_tasks WHERE manga_id = :mangaId AND split = :split AND convert = :convert " +
            "AND state IN (0, 1, 2)",
    )
    suspend fun countActive(mangaId: Long, split: Boolean, convert: Boolean): Int

    /** Any active run over all downloads blocks another run over everything. */
    @Query("SELECT COUNT(*) FROM optimize_tasks WHERE state IN (0, 1, 2)")
    suspend fun countAllActive(): Int

    @Query("UPDATE optimize_tasks SET state = :state WHERE id = :id AND state IN (0, 1, 2)")
    suspend fun setActiveState(id: Long, state: Int)

    /** A stopped worker (not cancelled by the user) goes back to the queue only if it was still active. */
    @Query("UPDATE optimize_tasks SET state = 0 WHERE id = :id AND state IN (1, 2)")
    suspend fun requeue(id: Long)

    @Query("UPDATE optimize_tasks SET state = 1, total = :total WHERE id = :id AND state IN (0, 1, 2)")
    suspend fun markRunning(id: Long, total: Int)

    @Query(
        "UPDATE optimize_tasks SET done = :done, changed = :changed, failed = :failed, saved_bytes = :saved " +
            "WHERE id = :id AND state IN (1, 2)",
    )
    suspend fun updateProgress(id: Long, done: Int, changed: Int, failed: Int, saved: Long)

    @Query(
        "UPDATE optimize_tasks SET state = :state, done = :done, changed = :changed, failed = :failed, " +
            "saved_bytes = :saved, finished_at = :now WHERE id = :id AND state IN (0, 1, 2)",
    )
    suspend fun finish(id: Long, state: Int, done: Int, changed: Int, failed: Int, saved: Long, now: Long)

    @Query("UPDATE optimize_tasks SET state = 5, finished_at = :now WHERE id = :id AND state IN (0, 1, 2)")
    suspend fun cancel(id: Long, now: Long)

    /** Re-run of a finished task: same job file, fresh counters, new work id. */
    @Query(
        "UPDATE optimize_tasks SET state = 0, work_id = :workId, done = 0, changed = 0, failed = 0, " +
            "saved_bytes = 0, created_at = :now, finished_at = 0 WHERE id = :id AND state IN (3, 4, 5)",
    )
    suspend fun reset(id: Long, workId: String, now: Long)

    @Query("DELETE FROM optimize_tasks WHERE id = :id AND state IN (3, 4, 5)")
    suspend fun delete(id: Long)

    @Query("DELETE FROM optimize_tasks WHERE state IN (3, 4, 5)")
    suspend fun clearFinished()
}

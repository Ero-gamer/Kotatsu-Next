package org.koitharu.kotatsu.local.data.optimize

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One queued / running / finished optimize run, shown on the "Image optimizer" screen. Terminal rows
 * stay as history until the user clears them. [mangaId] is 0 for a run over all downloads.
 */
@Entity(tableName = "optimize_tasks")
class OptimizeTaskEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,
    /** Name of the job file in `filesDir/optimize_jobs` (without extension). */
    @ColumnInfo(name = "job_id") val jobId: String,
    /** WorkManager id of the current attempt, used for pause / cancel. */
    @ColumnInfo(name = "work_id") val workId: String,
    @ColumnInfo(name = "manga_id") val mangaId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "split") val split: Boolean,
    @ColumnInfo(name = "convert") val convert: Boolean,
    @ColumnInfo(name = "state") val state: Int,
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "done") val done: Int = 0,
    @ColumnInfo(name = "changed") val changed: Int = 0,
    @ColumnInfo(name = "failed") val failed: Int = 0,
    @ColumnInfo(name = "saved_bytes") val savedBytes: Long = 0L,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "finished_at") val finishedAt: Long = 0L,
) {

    val isActive: Boolean
        get() = state == STATE_QUEUED || state == STATE_RUNNING || state == STATE_PAUSED

    companion object {

        const val STATE_QUEUED = 0
        const val STATE_RUNNING = 1
        const val STATE_PAUSED = 2
        const val STATE_DONE = 3
        const val STATE_FAILED = 4
        const val STATE_CANCELLED = 5
    }
}

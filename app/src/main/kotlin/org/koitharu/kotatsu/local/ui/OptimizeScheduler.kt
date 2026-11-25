package org.koitharu.kotatsu.local.ui

import android.content.Context
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.await
import androidx.work.workDataOf
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.download.ui.worker.PausingReceiver
import org.koitharu.kotatsu.local.data.CbzMaintenance
import org.koitharu.kotatsu.local.data.isZipArchive
import org.koitharu.kotatsu.local.data.optimize.OptimizeJob
import org.koitharu.kotatsu.local.data.optimize.OptimizeOptions
import org.koitharu.kotatsu.local.data.optimize.OptimizeTaskDao
import org.koitharu.kotatsu.local.data.optimize.OptimizeTaskEntity
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Finds the archives of downloaded manga and queues [OptimizeWorker] runs (one at a time). */
@Reusable
class OptimizeScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val database: MangaDatabase,
) {

    private val dao: OptimizeTaskDao
        get() = database.getOptimizeTaskDao()

    fun observeTasks(): Flow<List<OptimizeTaskEntity>> = dao.observeAll()

    /**
     * Archives holding the pages of [local]. With [chapterIds] only those chapters' archives are
     * returned (a single-file CBZ holding several chapters is always processed as a whole).
     */
    suspend fun archivesOf(local: LocalManga, chapterIds: Set<Long>? = null): List<File> = withContext(Dispatchers.IO) {
        val root = local.file
        when {
            root.isZipArchive -> listOf(root)

            !root.isDirectory -> emptyList()

            chapterIds == null -> CbzMaintenance.listArchives(listOf(root))

            else -> local.manga.chapters.orEmpty()
                .filter { it.id in chapterIds }
                .mapNotNull { chapter -> runCatching { chapter.url.toUri().toFile() }.getOrNull() }
                .filter { it.isZipArchive }
                .distinct()
        }
    }

    /**
     * Queues a run. [EnqueueResult.DUPLICATE] when the same manga (or "all downloads") with the same options is
     * already queued or running, so a double tap or a repeated request cannot stack identical runs.
     * [mangaId] is 0 for a run over all downloads.
     */
    suspend fun enqueue(files: List<File>, options: OptimizeOptions, title: String, mangaId: Long): EnqueueResult {
        if (files.isEmpty() || options.isNoop) return EnqueueResult.EMPTY
        val duplicate = if (mangaId == 0L) {
            dao.countAllActive() > 0
        } else {
            dao.countActive(mangaId, options.splitTall, options.convertToWebp) > 0 || dao.countActive(
                0L,
                options.splitTall,
                options.convertToWebp,
            ) > 0
        }
        if (duplicate) return EnqueueResult.DUPLICATE
        val jobId = UUID.randomUUID().toString()
        val distinct = files.distinct()
        withContext(Dispatchers.IO) {
            sweepOldJobs(context)
            OptimizeJob(options, distinct).write(jobFile(context, jobId))
        }
        val workId = UUID.randomUUID()
        val taskId = dao.insert(
            OptimizeTaskEntity(
                jobId = jobId,
                workId = workId.toString(),
                mangaId = mangaId,
                title = title,
                split = options.splitTall,
                convert = options.convertToWebp,
                state = OptimizeTaskEntity.STATE_QUEUED,
                total = distinct.size,
                createdAt = System.currentTimeMillis(),
            ),
        )
        workManager.enqueue(buildRequest(jobId, taskId, workId)).await()
        return EnqueueResult.STARTED
    }

    /** Re-runs a failed or cancelled task from its saved job file; false when the file is gone. */
    suspend fun retry(task: OptimizeTaskEntity): Boolean {
        if (task.isActive) return false
        val exists = withContext(Dispatchers.IO) { OptimizeJob.read(jobFile(context, task.jobId)) != null }
        if (!exists) return false
        val workId = UUID.randomUUID()
        dao.reset(task.id, workId.toString(), System.currentTimeMillis())
        workManager.enqueue(buildRequest(task.jobId, task.id, workId)).await()
        return true
    }

    fun pause(task: OptimizeTaskEntity) {
        UUID.fromString(task.workId).let { context.sendBroadcast(PausingReceiver.getPauseIntent(context, it)) }
    }

    fun resume(task: OptimizeTaskEntity) {
        UUID.fromString(task.workId).let { context.sendBroadcast(PausingReceiver.getResumeIntent(context, it)) }
    }

    /** Marks the row cancelled first (so the stopping worker does not re-queue it), then stops the work. */
    suspend fun cancel(task: OptimizeTaskEntity) {
        dao.cancel(task.id, System.currentTimeMillis())
        workManager.cancelWorkById(UUID.fromString(task.workId)).await()
    }

    /** Removes one finished row (its job file goes too). */
    suspend fun remove(task: OptimizeTaskEntity) {
        dao.delete(task.id)
        withContext(Dispatchers.IO) { jobFile(context, task.jobId).delete() }
    }

    /** Clears the whole history; queued / running tasks stay. */
    suspend fun clearFinished(finished: List<OptimizeTaskEntity>) {
        dao.clearFinished()
        withContext(Dispatchers.IO) { finished.forEach { jobFile(context, it.jobId).delete() } }
    }

    private fun buildRequest(jobId: String, taskId: Long, id: UUID): OneTimeWorkRequest {
        val builder = OneTimeWorkRequestBuilder<OptimizeWorker>()
            .setInputData(workDataOf(OptimizeWorker.KEY_JOB to jobId, OptimizeWorker.KEY_TASK to taskId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .addTag(TAG)
            .setId(id)
        return builder.build()
    }

    enum class EnqueueResult { STARTED, EMPTY, DUPLICATE }

    companion object {

        const val TAG = "optimize_images"
        private const val JOBS_DIR = "optimize_jobs"
        private const val JOB_MAX_AGE_MS = 3L * 24 * 60 * 60 * 1000

        fun jobFile(context: Context, id: String) = File(File(context.filesDir, JOBS_DIR), "$id.json")

        /** Job files of cancelled runs stay behind on purpose (a stopped run may be resumed); drop old ones. */
        private fun sweepOldJobs(context: Context) {
            val now = System.currentTimeMillis()
            File(context.filesDir, JOBS_DIR).listFiles()?.forEach {
                if (now - it.lastModified() > JOB_MAX_AGE_MS) it.delete()
            }
        }
    }
}

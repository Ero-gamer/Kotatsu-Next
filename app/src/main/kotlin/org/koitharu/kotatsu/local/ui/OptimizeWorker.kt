package org.koitharu.kotatsu.local.ui

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.util.Throttler
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.download.ui.worker.PausingHandle
import org.koitharu.kotatsu.download.ui.worker.PausingReceiver
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.optimize.ImageOptimizer
import org.koitharu.kotatsu.local.data.optimize.OptimizeJob
import org.koitharu.kotatsu.local.data.optimize.OptimizeStats
import org.koitharu.kotatsu.local.data.optimize.OptimizeTaskEntity
import org.koitharu.kotatsu.local.domain.model.LocalManga
import java.io.File

/**
 * Runs an [OptimizeJob]: archives are processed one after another by [ImageOptimizer] (each swapped in
 * atomically, so cancelling, pausing, a crash or a kill never leaves a half-written archive). Pause /
 * resume / cancel come from the notification; after a process kill WorkManager restarts the run and the
 * archives already done simply have nothing left to change.
 */
@HiltWorker
class OptimizeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    @LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
    private val database: MangaDatabase,
) : CoroutineWorker(appContext, params) {

    private val notifier = OptimizeNotifier(appContext, params.id)
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val throttler = Throttler(500)

    private val dao = database.getOptimizeTaskDao()
    private val taskId: Long
        get() = inputData.getLong(KEY_TASK, 0L)

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB) ?: return Result.failure()
        val jobFile = OptimizeScheduler.jobFile(applicationContext, jobId)
        val job = OptimizeJob.read(jobFile)
        if (job == null || job.files.isEmpty()) {
            jobFile.delete()
            dao.finish(taskId, OptimizeTaskEntity.STATE_FAILED, 0, 0, 0, 0L, System.currentTimeMillis())
            return Result.failure()
        }
        // One run at a time (a 2 GB phone cannot decode two archives' worth of pages in parallel); the others
        // wait here, still "queued" and without a notification, until their turn comes.
        return runLock.withLock {
            val task = dao.find(taskId)
            if (task != null && !task.isActive) {
                return@withLock Result.success() // cancelled while waiting
            }
            try {
                execute(job, jobFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unexpected (e.g. database) error: do not leave the row queued forever.
                e.printStackTraceDebug()
                dao.finish(taskId, OptimizeTaskEntity.STATE_FAILED, 0, 0, 0, 0L, System.currentTimeMillis())
                Result.failure()
            }
        }
    }

    private suspend fun execute(job: OptimizeJob, jobFile: File): Result {
        val count = job.files.size
        dao.markRunning(taskId, count)
        setForeground(foregroundInfo(0, count, 0f, paused = false))
        val handle = PausingHandle()
        val receiver = PausingReceiver(id, handle)
        ContextCompat.registerReceiver(
            applicationContext,
            receiver,
            PausingReceiver.createIntentFilter(id),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val workDir = File(applicationContext.cacheDir, "optimize")
        workDir.deleteRecursively()
        workDir.mkdirs()
        var total = OptimizeStats()
        var finished = false
        try {
            for ((index, file) in job.files.withIndex()) {
                currentCoroutineContext().ensureActive()
                gate(handle, index, count)
                publish(index, count, 0f, handle.isPaused, force = true)
                val stats = try {
                    ImageOptimizer.rewrite(
                        file = file,
                        options = job.options,
                        workDir = workDir,
                        pause = { gate(handle, index, count) },
                        onProgress = { done, n ->
                            publish(index, count, if (n > 0) done.toFloat() / n else 0f, false)
                        },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTraceDebug() // one bad archive must not stop the run
                    OptimizeStats(archives = 1, failedArchives = 1)
                }
                total += stats
                dao.updateProgress(taskId, index + 1, total.changedArchives, total.failedArchives, total.bytesSaved)
            }
            finished = true
            val state = if (total.failedArchives > 0 && total.changedArchives == 0) {
                OptimizeTaskEntity.STATE_FAILED
            } else {
                OptimizeTaskEntity.STATE_DONE
            }
            dao.finish(
                taskId,
                state,
                count,
                total.changedArchives,
                total.failedArchives,
                total.bytesSaved,
                System.currentTimeMillis(),
            )
            if (total.changedArchives > 0) {
                localStorageChanges.emit(null)
            }
            // A different id: the foreground notification above is removed when this worker ends.
            notificationManager.notify(id.hashCode() + 1, notifier.result(total))
            return Result.success(
                workDataOf(
                    DATA_CHANGED to total.changedArchives,
                    DATA_FAILED to total.failedArchives,
                    DATA_SAVED to total.bytesSaved,
                ),
            )
        } finally {
            applicationContext.unregisterReceiverSafe(receiver)
            withContext(NonCancellable) {
                workDir.deleteRecursively()
                // Kept after a failure or a cancel so "Retry" can run it again (old ones are swept).
                if (finished && total.failedArchives == 0) jobFile.delete()
                if (!finished) {
                    // Cancelled by the user: the row is already CANCELLED, this is a no-op. Stopped by the
                    // system: the row goes back to the queue and WorkManager restarts the worker.
                    dao.requeue(taskId)
                    notificationManager.cancel(id.hashCode())
                }
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0, 0f, paused = false)

    /** Suspends while the run is paused and keeps the notification in sync. */
    private suspend fun gate(handle: PausingHandle, index: Int, count: Int) {
        if (!handle.isPaused) return
        dao.setActiveState(taskId, OptimizeTaskEntity.STATE_PAUSED)
        publish(index, count, 0f, paused = true, force = true)
        handle.awaitResumed()
        dao.setActiveState(taskId, OptimizeTaskEntity.STATE_RUNNING)
        publish(index, count, 0f, paused = false, force = true)
    }

    private fun publish(index: Int, count: Int, fraction: Float, paused: Boolean, force: Boolean = false) {
        if (!force && !throttler.throttle()) return
        notificationManager.notify(id.hashCode(), notifier.progress(index, count, fraction, paused))
    }

    private fun foregroundInfo(done: Int, total: Int, fraction: Float, paused: Boolean): ForegroundInfo {
        val notification = notifier.progress(done, total, fraction, paused)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    private fun Context.unregisterReceiverSafe(receiver: PausingReceiver) {
        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            e.printStackTraceDebug()
        }
    }

    companion object {

        const val KEY_JOB = "job"
        const val KEY_TASK = "task"
        private val runLock = Mutex()
        const val DATA_CHANGED = "changed"
        const val DATA_FAILED = "failed"
        const val DATA_SAVED = "saved"
    }
}

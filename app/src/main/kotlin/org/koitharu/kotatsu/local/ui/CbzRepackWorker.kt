package org.koitharu.kotatsu.local.ui

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.local.data.CbzMaintenance
import org.koitharu.kotatsu.local.data.LocalStorageManager
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Background pass that finds old CBZ files with compressed image entries and rewrites them as STORED
 * (see [CbzMaintenance.repackStored]). Opt-in preference (default off); runs rarely, only while idle, charging and with
 * enough battery/storage. Each archive is rewritten to a temp file, verified and swapped atomically, and
 * archives that are already stored are only scanned, never touched.
 */
@HiltWorker
class CbzRepackWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val settings: AppSettings,
    private val storageManager: LocalStorageManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!settings.isCbzAutoRepackEnabled) return Result.success()
        return withContext(Dispatchers.IO) {
            val dirs = storageManager.getReadableDirs()
            CbzMaintenance.cleanupTemp(dirs)
            for (file in CbzMaintenance.listArchives(dirs)) {
                ensureActive()
                if (isStopped) return@withContext Result.retry()
                try {
                    CbzMaintenance.repackStored(file)
                } catch (e: IOException) {
                    e.printStackTraceDebug() // one bad archive must not stop the pass
                }
            }
            Result.success()
        }
    }

    companion object {

        private const val TAG = "cbz_repack"

        suspend fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) {
                manager.cancelUniqueWork(TAG).await()
                return
            }
            val constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<CbzRepackWorker>(7, TimeUnit.DAYS)
                .addTag(TAG)
                .setConstraints(constraints)
                .build()
            manager.enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request).await()
        }
    }
}

package org.koitharu.kotatsu.local.ui

import android.app.Notification
import android.content.Context
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.download.ui.worker.PausingReceiver
import org.koitharu.kotatsu.local.data.optimize.OptimizeStats
import java.util.UUID
import androidx.appcompat.R as appcompatR

/** Progress / result notifications of [OptimizeWorker], with pause, resume and cancel actions. */
class OptimizeNotifier(
    private val context: Context,
    private val workId: UUID,
) {

    init {
        NotificationManagerCompat.from(context).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.optimize_channel))
                .setVibrationEnabled(false)
                .setLightsEnabled(false)
                .setSound(null, null)
                .build(),
        )
    }

    /** [done] / [total] are archives; [fraction] (0..1) is the progress inside the current archive. */
    fun progress(done: Int, total: Int, fraction: Float, paused: Boolean): Notification {
        val max = 1000
        val value = if (total > 0) (((done + fraction.coerceIn(0f, 1f)) / total) * max).toInt().coerceIn(0, max) else 0
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.optimize_title))
            .setContentText(
                if (paused) {
                    context.getString(R.string.optimize_paused)
                } else {
                    context.getString(R.string.optimize_progress, (done + 1).coerceAtMost(total), total)
                },
            )
            .setSmallIcon(if (paused) R.drawable.ic_stat_paused else android.R.drawable.stat_sys_download)
            .setProgress(max, value, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                appcompatR.drawable.abc_ic_clear_material,
                context.getString(android.R.string.cancel),
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
        if (paused) {
            builder.addAction(
                R.drawable.ic_action_resume,
                context.getString(R.string.resume),
                PausingReceiver.createResumePendingIntent(context, workId),
            )
        } else {
            builder.addAction(
                R.drawable.ic_action_pause,
                context.getString(R.string.pause),
                PausingReceiver.createPausePendingIntent(context, workId),
            )
        }
        return builder.build()
    }

    fun result(stats: OptimizeStats): Notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setContentTitle(context.getString(R.string.optimize_done_title))
        .setContentText(
            context.getString(
                R.string.optimize_done_text,
                stats.changedArchives,
                stats.failedArchives,
                Formatter.formatFileSize(context, stats.bytesSaved.coerceAtLeast(0L)),
            ),
        )
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setAutoCancel(true)
        .setSilent(true)
        .build()

    private companion object {

        const val CHANNEL_ID = "optimize_images"
    }
}

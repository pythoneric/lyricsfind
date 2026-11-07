package com.biteric.songfixer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.biteric.songfixer.ui.MainActivity

/**
 * Runs one batch (fix sure ones, normalize, trim…) in the background with a notification, so it
 * finishes while the app is closed or the phone is locked: Android freezes or stops an app in the
 * background, which stopped batches that ran inside the screen.
 */
class BatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    // From its tag, which is its own: a queued batch's input data gets the outputs of the batches
    // before it merged in (WorkManager chains them), so a kind kept there could be another batch's.
    private val kind: BatchKind? = tags.firstNotNullOfOrNull { t -> t.removePrefix(TAG_PREFIX).takeIf { t.startsWith(TAG_PREFIX) } }
        ?.let { k -> BatchKind.entries.firstOrNull { it.name == k } }

    override suspend fun doWork(): Result {
        val kind = kind ?: return Result.failure()
        val app = applicationContext as SongFixerApp
        setForeground(foregroundInfo(kind, 0, 0))
        runCatching { app.repository.pruneOriginals() }
        var notified = 0L
        val message = Batches(app.repository).run(kind, app.settings.current) { done, total ->
            // The screen's progress after every song; the notification at most once a second, or
            // Android drops updates past 5 a second, and with them the "done" notification after.
            setProgress(workDataOf(KEY_KIND to kind.name, KEY_DONE to done, KEY_TOTAL to total))
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - notified >= 1000 && done < total) {
                notified = now
                setForeground(foregroundInfo(kind, done, total))
            }
        }
        // Said in the app if it's open, and here in case it isn't.
        notifyDone(message)
        return Result.success(workDataOf(KEY_MESSAGE to message))
    }

    override suspend fun getForegroundInfo() = foregroundInfo(kind ?: BatchKind.SURE, 0, 0)

    private fun channel(): String {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fixing songs", NotificationManager.IMPORTANCE_LOW))
        }
        return CHANNEL
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0,
        Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun foregroundInfo(kind: BatchKind, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, channel())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(kind.doing)
            .setContentText(if (total > 0) "$done of $total" else "Starting…")
            .setProgress(total, done, total == 0)
            .setContentIntent(openApp())
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_ID, n)
        }
    }

    private fun notifyDone(message: String) {
        val n = NotificationCompat.Builder(applicationContext, channel())
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Song Fixer")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        // Without the notification permission this is simply not shown.
        runCatching { applicationContext.getSystemService(NotificationManager::class.java).notify(DONE_ID, n) }
    }

    companion object {
        const val WORK_NAME = "batch"
        const val KEY_KIND = "kind"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_MESSAGE = "message"
        /** Tagged with its kind, so the screen knows which batches are queued. */
        const val TAG_PREFIX = "batch:"
        private const val CHANNEL = "batch"
        // The check's notification is 1: both can show at once.
        private const val PROGRESS_ID = 2
        private const val DONE_ID = 3

        /** Queues [kind] after any batch already running (pressing Trim, then Normalize, runs both in turn). */
        fun start(context: Context, kind: BatchKind) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<BatchWorker>().addTag(TAG_PREFIX + kind.name).build(),
            )
        }

        /** Stops the running batch and any queued after it. */
        fun stop(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

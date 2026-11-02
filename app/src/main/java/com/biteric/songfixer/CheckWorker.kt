package com.biteric.songfixer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
import com.biteric.songfixer.online.AcoustIdKeyException

/**
 * Reads the folder and checks every unchecked song, in the background with a notification so a
 * library of thousands of songs can finish while the phone is in a pocket.
 */
class CheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SongFixerApp
        val tree = app.settings.current.folderUri ?: return Result.failure()
        setForeground(foregroundInfo(0, 0))
        try {
            app.repository.sync(tree)
        } catch (e: Exception) {
            return Result.failure(workDataOf(KEY_ERROR to "Can't read the folder: ${e.message}"))
        }
        val total = app.repository.uncheckedCount()
        var done = 0
        setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to total))
        try {
            while (app.repository.checkNext()) {
                done++
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                if (done % 5 == 0 || done == total) setForeground(foregroundInfo(done, total))
            }
        } catch (e: AcoustIdKeyException) {
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "AcoustID key not accepted")))
        }
        // Then loudness, one whole song at a time (for volume normalization).
        if (app.settings.current.normalize) {
            val toMeasure = app.repository.unmeasuredCount()
            var measured = 0
            setProgress(workDataOf(KEY_DONE to 0, KEY_TOTAL to toMeasure, KEY_PHASE to PHASE_LOUDNESS))
            setForeground(foregroundInfo(0, toMeasure, PHASE_LOUDNESS))
            while (app.repository.measureNext()) {
                measured++
                setProgress(workDataOf(KEY_DONE to measured, KEY_TOTAL to toMeasure, KEY_PHASE to PHASE_LOUDNESS))
                setForeground(foregroundInfo(measured, toMeasure, PHASE_LOUDNESS))
            }
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo() = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int, phase: String = PHASE_NAMES): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Checking songs", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (phase == PHASE_LOUDNESS) "Measuring loudness" else "Checking song names")
            .setContentText(if (total > 0) "$done of $total" else "Reading the folder…")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    companion object {
        const val WORK_NAME = "check"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val KEY_PHASE = "phase"
        const val PHASE_NAMES = "names"
        const val PHASE_LOUDNESS = "loudness"
        private const val CHANNEL = "check"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<CheckWorker>().build())
        }

        fun stop(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}

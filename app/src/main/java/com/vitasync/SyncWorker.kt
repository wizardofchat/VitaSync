package com.vitasync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * End-of-day send: once a day at about 22:00 (Android may run it later to
 * save battery), read the v1 export from Health Connect and send it to
 * VitaClaw. Needs the "read data in the background" Health Connect
 * permission; without it the job records why and stops (no retry), and the
 * "Send to VitaClaw now" button still works while the app is open.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manager = HealthConnectManager(applicationContext)
        if (!manager.isAvailable()) {
            VitaClawSender.remember(applicationContext,
                VitaClawSender.Outcome(-3, "Health Connect not available"), "daily")
            return Result.failure()
        }
        if (!manager.canReadInBackground()) {
            VitaClawSender.remember(applicationContext,
                VitaClawSender.Outcome(-3, "background read permission not granted"), "daily")
            return Result.failure()
        }
        val export = try {
            ExportBuilder.build(manager)
        } catch (e: Exception) {
            VitaClawSender.remember(applicationContext,
                VitaClawSender.Outcome(-1, "read failed: ${e.javaClass.simpleName}"), "daily")
            return if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
        val outcome = VitaClawSender.send(export)
        VitaClawSender.remember(applicationContext, outcome, "daily")
        return when {
            outcome.ok -> Result.success()
            outcome.retryable && runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure()   // 400, 401, 413: retrying would not help
        }
    }

    companion object {
        private const val NAME = "vitaclaw-daily-send"
        private const val MAX_ATTEMPTS = 5
        private const val SEND_HOUR = 22

        /** Safe to call on every app start: an existing schedule is kept. */
        fun schedule(context: Context) {
            val now = ZonedDateTime.now()
            var next = now.withHour(SEND_HOUR).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = PeriodicWorkRequestBuilder<SyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

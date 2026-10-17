package com.vitasync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Morning send: once a day at about 08:00 (Android may run it a little
 * later to save battery), read the v1 export from Health Connect and send it
 * to VitaClaw, so last night's sleep arrives the same morning.
 *
 * Anchored, not periodic: each run books the NEXT run for 08:00 the
 * following day. A plain 24-hour periodic job drifts later every day,
 * because Android counts the 24 hours from when a run actually happened
 * (seen 13-15 Oct 2026: 22:13, 22:24, 22:29).
 *
 * Each day's run has its own unique name (vitaclaw-send-YYYYMMDD), so
 * booking the same day twice (app start plus the previous run) is harmless.
 *
 * Needs the "read data in the background" Health Connect permission;
 * without it the run records why and stops (no retry), and the
 * "Send to VitaClaw now" button still works while the app is open.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manager = HealthConnectManager(applicationContext)
        if (!manager.isAvailable()) {
            return finish(VitaClawSender.Outcome(-3, "Health Connect not available"))
        }
        if (!manager.canReadInBackground()) {
            return finish(VitaClawSender.Outcome(-3, "background read permission not granted"))
        }
        val export = try {
            ExportBuilder.build(manager)
        } catch (e: Exception) {
            val outcome = VitaClawSender.Outcome(-1, "read failed: ${e.javaClass.simpleName}")
            if (runAttemptCount < MAX_ATTEMPTS) {
                VitaClawSender.remember(applicationContext, outcome, "daily")
                return Result.retry()
            }
            return finish(outcome)
        }
        val outcome = VitaClawSender.send(export)
        if (!outcome.ok && outcome.retryable && runAttemptCount < MAX_ATTEMPTS) {
            VitaClawSender.remember(applicationContext, outcome, "daily")
            return Result.retry()   // the retry books tomorrow when it finishes
        }
        return finish(outcome)
    }

    /** Record the outcome, book tomorrow's run, and end this one. */
    private fun finish(outcome: VitaClawSender.Outcome): Result {
        VitaClawSender.remember(applicationContext, outcome, "daily")
        schedule(applicationContext)
        return if (outcome.ok) Result.success() else Result.failure()
    }

    companion object {
        private const val OLD_PERIODIC_NAME = "vitaclaw-daily-send"   // the 22:00 job, replaced 16 Oct 2026
        private const val NAME_PREFIX = "vitaclaw-send-"
        private const val TAG = "vitaclaw-send"
        private const val MAX_ATTEMPTS = 5
        const val SEND_HOUR = 8
        private val DAY: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        /**
         * Book the next 08:00 run. Safe to call on every app start and at the
         * end of every run: a day already booked is kept as it is.
         */
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(OLD_PERIODIC_NAME)   // no-op once the old job is gone

            val now = ZonedDateTime.now()
            var next = now.withHour(SEND_HOUR).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(Duration.between(now, next).toMinutes(), TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            wm.enqueueUniqueWork(NAME_PREFIX + next.format(DAY), ExistingWorkPolicy.KEEP, request)
        }
    }
}

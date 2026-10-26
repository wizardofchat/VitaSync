package com.vitasync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Daily send at ~08:00 that re-books itself. One pending send at a time
 * under a single unique work name (vitaclaw-send); the policy picked at
 * each enqueue site decides whether to keep or replace what's already
 * pending.
 *
 * Failure (no connection — common when Proton VPN steals the single VPN
 * slot from Tailscale — timeout, non-2xx, HC unavailable, no bg
 * permission, read exception) books a 60-minute retry, replacing any
 * tomorrow-08:00 booking that was pending.
 *
 * Success advances the per-type cursor to the chunk's actual end (not to
 * now — a chunk is capped at 7 days). If any type is still more than a day
 * behind now, the next send is booked 15 minutes out so a 30-day catch-up
 * clears in roughly five sends.
 *
 * App start uses KEEP (don't nudge a pending retry just because the user
 * opened the app); every other site uses REPLACE.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val manager = HealthConnectManager(applicationContext)
        // HC-unavailable and no-bg-permission won't fix themselves within an
        // hour (user must install HC or grant a permission), so book the
        // next daily instead of a 60-min retry loop.
        if (!manager.isAvailable()) {
            VitaClawSender.recordAttempt(
                applicationContext, VitaClawSender.Outcome(-3, "Health Connect not available"), "daily"
            )
            scheduleNextDaily(applicationContext, ExistingWorkPolicy.REPLACE)
            return Result.failure()
        }
        if (!manager.canReadInBackground()) {
            VitaClawSender.recordAttempt(
                applicationContext,
                VitaClawSender.Outcome(-3, "background read permission not granted"), "daily"
            )
            scheduleNextDaily(applicationContext, ExistingWorkPolicy.REPLACE)
            return Result.failure()
        }

        val build = try {
            ExportBuilder.build(manager) { type -> VitaClawSender.cursorFor(applicationContext, type) }
        } catch (e: Exception) {
            VitaClawSender.recordAttempt(
                applicationContext,
                VitaClawSender.Outcome(-1, "read failed: ${e.javaClass.simpleName}"), "daily"
            )
            scheduleRetry(applicationContext)
            return Result.failure()
        }

        val outcome = VitaClawSender.send(build.json)
        VitaClawSender.recordAttempt(applicationContext, outcome, "daily")
        return if (outcome.ok) {
            VitaClawSender.advanceCursors(applicationContext, build.windowEnds)
            scheduleAfterSuccess(applicationContext, build.windowEnds)
            Result.success()
        } else {
            scheduleRetry(applicationContext)
            Result.failure()
        }
    }

    companion object {
        private const val OLD_PERIODIC_NAME = "vitaclaw-daily-send"
        const val UNIQUE_NAME = "vitaclaw-send"
        private const val TAG = "vitaclaw-send"
        const val SEND_HOUR = 8
        const val RETRY_MINUTES = 60L
        const val CATCH_UP_MINUTES = 15L
        const val CAUGHT_UP_HOURS = 24L

        /** Book the next 08:00 run. App start passes KEEP (preserves a pending retry). */
        fun scheduleNextDaily(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(OLD_PERIODIC_NAME)   // one-shot cleanup of the Oct-16-retired job
            val now = ZonedDateTime.now()
            var next = now.withHour(SEND_HOUR).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            wm.enqueueUniqueWork(UNIQUE_NAME, policy, request(Duration.between(now, next).toMinutes()))
        }

        /** 60-minute retry on any failure. Replaces whatever was pending. */
        fun scheduleRetry(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request(RETRY_MINUTES)
            )
        }

        /**
         * After a successful send: if any type's chunk ended more than
         * CAUGHT_UP_HOURS before now, book another send in 15 min to keep
         * eating the backlog; otherwise book tomorrow's 08:00.
         */
        fun scheduleAfterSuccess(context: Context, windowEnds: Map<String, Instant>) {
            val now = Instant.now()
            val stillBehind = windowEnds.values.any {
                Duration.between(it, now).toHours() > CAUGHT_UP_HOURS
            }
            if (stillBehind) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request(CATCH_UP_MINUTES)
                )
            } else {
                scheduleNextDaily(context, ExistingWorkPolicy.REPLACE)
            }
        }

        private fun request(delayMinutes: Long) =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .addTag(TAG)
                .build()
    }
}

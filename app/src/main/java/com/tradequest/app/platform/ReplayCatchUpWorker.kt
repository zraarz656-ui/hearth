package com.tradequest.app.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tradequest.app.TradeQuestApplication
import java.util.concurrent.TimeUnit

/**
 * Best-effort periodic replay catch-up (Step 7.8).
 *
 * # Why best-effort is the correct model
 *
 * The plan is explicit: do not rely on exact 15-minute scheduling. WorkManager
 * is free to defer, coalesce or skip a periodic run — it does not fire while the
 * device is dozing, and a run that starts late is not rescheduled to make up the
 * lost cadence. That is fine here, because the catch-up is *derived*: the worker
 * asks the clock where historical time now stands and replays whatever is owed.
 * A missed run therefore loses nothing; the next run simply owes more, and the
 * engine's cursor makes the extra interval ordinary work rather than a special
 * case. Nothing in this class has to track how many runs were missed.
 *
 * # Why there is no foreground-service path
 *
 * Challenge Mode advances at 1x real time while the app is closed, so the owed
 * interval after a long closure can be large. Running it as a foreground service
 * would need a persistent notification and a service type, which is a heavier
 * commitment than this phase calls for. The worker does bounded work and, if the
 * catch-up cannot finish within the system's budget, stops cleanly; the app's own
 * resume path finishes the job when the user returns.
 */
class ReplayCatchUpWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val application = applicationContext as? TradeQuestApplication ?: return Result.success()
        val container = application.container

        return try {
            container.catchUpActiveSeason()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The system is stopping us. Whatever batch was committed is durable
            // and the next run resumes from it, so this is a retry rather than a
            // failure.
            throw e
        } catch (e: Exception) {
            // A transient failure (a locked database, a device under pressure) is
            // worth retrying; a permanent one will simply fail again and be
            // dropped by WorkManager's own backoff. Reporting `retry` keeps the
            // distinction in the system's hands rather than guessing here.
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "tradequest.replay.catchup"

        /**
         * The periodic interval.
         *
         * WorkManager's floor for a periodic request is 15 minutes, and the plan
         * names that figure, so it is used as-is. The value is a *minimum*: the
         * system may run the worker less often, which the catch-up model absorbs.
         */
        private const val INTERVAL_MINUTES: Long = 15

        /**
         * Enqueues the periodic catch-up, replacing any previous schedule.
         *
         * [ExistingPeriodicWorkPolicy.UPDATE] rather than `KEEP`: if the interval
         * or constraints ever change, the installed schedule should follow, and
         * UPDATE does that without dropping the existing work.
         *
         * No network constraint, because the app is offline by design and a
         * network requirement would silently disable the worker forever.
         *
         * # Why the call is wrapped
         *
         * In production, `WorkManager.getInstance` performs on-demand
         * initialization through the default `androidx.startup` provider, so the
         * first call here is what creates the singleton and this always succeeds.
         * A Robolectric test process has no such provider, and there the call
         * throws `IllegalStateException`. Catching it keeps the Application
         * constructible under test without pretending the schedule exists: the
         * only consequence is that a test process has no periodic catch-up, which
         * is exactly what a test wants.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReplayCatchUpWorker>(
                INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()

            try {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } catch (e: IllegalStateException) {
                // No WorkManager in this process. See the note above.
            }
        }
    }
}

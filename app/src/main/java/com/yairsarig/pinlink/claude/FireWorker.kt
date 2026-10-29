package com.yairsarig.pinlink.claude

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yairsarig.pinlink.PinConfig
import java.util.concurrent.TimeUnit

/**
 * Background fire after uploads. The first upload starts a [PinConfig.FIRE_DEBOUNCE_SECONDS] wait;
 * uploads during it ride along (APPEND: later links in the chain find nothing left and no-op).
 */
class FireWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        ClaudeTrigger.setScheduled(true)
        val fired = if (ClaudeTrigger.state.value.auto) ClaudeTrigger.firePending() else null
        val retry = fired?.result is FireResult.Failed && runAttemptCount + 1 < PinConfig.FIRE_MAX_ATTEMPTS
        if (!retry) ClaudeTrigger.setScheduled(false)
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "claude-fire"

        fun schedule(context: Context) {
            val req = OneTimeWorkRequestBuilder<FireWorker>()
                .setInitialDelay(PinConfig.FIRE_DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PinConfig.WORK_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
            ClaudeTrigger.setScheduled(true)
        }
    }
}

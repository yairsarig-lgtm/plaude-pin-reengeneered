package com.yairsarig.pinlink.housekeeping

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import com.yairsarig.pinlink.Pipeline
import java.io.File
import java.util.concurrent.TimeUnit

/** Daily: deletes local files [Retention] says are done with. */
class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val doomed = Retention.select(
            Pipeline.transcriptsDir(ctx), Pipeline.recordingsDir(ctx),
            File(ctx.getExternalFilesDir(null), PinConfig.DIR_LOGS),
            System.currentTimeMillis(), TimeUnit.DAYS.toMillis(PinConfig.RETENTION_DAYS),
        )
        val deleted = doomed.count { it.delete() }
        PinLog.i("Cleanup: deleted $deleted of ${doomed.size} file(s) older than ${PinConfig.RETENTION_DAYS} days")
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("cleanup", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

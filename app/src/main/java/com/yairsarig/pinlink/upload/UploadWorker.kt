package com.yairsarig.pinlink.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yairsarig.pinlink.Notifications
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import com.yairsarig.pinlink.Pipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * transcripts/<stamp>.txt -> Drive "Pin Inbox"/<stamp>.txt, then an `.uploaded` marker beside it.
 * Idempotent: skips if the marker exists or Drive already has a file of that name in the folder.
 * Only transcripts leave the phone — never audio.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val sessionId = inputData.getLong(Pipeline.KEY_SESSION_ID, -1).takeIf { it > 0 } ?: return@withContext Result.failure()
        val transcript = Pipeline.transcriptFile(applicationContext, sessionId)
        val marker = Pipeline.uploadedMarker(transcript)
        // Once uploaded, a transcript belongs to the downstream processor, which moves it out of the
        // inbox (e.g. to "Pin Done"). Never touch it again.
        if (marker.exists()) return@withContext Result.success()
        if (!transcript.exists()) return@withContext Result.failure().also { PinLog.e("Upload: ${transcript.name} missing") }

        try {
            val prefs = applicationContext.getSharedPreferences(PinConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val outcome = DriveAuth.session(applicationContext).run { token ->
                val drive = DriveClient(token)
                val folderId = drive.findOrCreateFolder(PinConfig.DRIVE_FOLDER, prefs.getString(PinConfig.PREF_DRIVE_FOLDER_ID, null))
                prefs.edit().putString(PinConfig.PREF_DRIVE_FOLDER_ID, folderId).apply()
                val existing = drive.findFile(folderId, transcript.name)
                val id = existing ?: drive.uploadText(folderId, transcript.name, transcript.readText())
                drive.ensureInFolder(id, folderId) // the file we just created only
                id to (existing != null)
            }
            when (outcome) {
                is DriveSession.Result.NeedsConsent -> {
                    // Retrying can't help; reconnecting re-queues everything pending.
                    PinLog.w("Upload ${transcript.name}: Drive access revoked or missing — notifying, not retrying")
                    DriveAuth.setConnected(false)
                    Notifications.driveReconnect(applicationContext)
                    Result.failure()
                }
                is DriveSession.Result.Ok -> {
                    val (id, existed) = outcome.value
                    marker.writeText(id)
                    DriveAuth.setConnected(true)
                    Notifications.clearDriveReconnect(applicationContext)
                    PinLog.i(if (existed) "Upload ${transcript.name}: already in ${PinConfig.DRIVE_FOLDER} ($id)" else "Uploaded ${transcript.name} -> ${PinConfig.DRIVE_FOLDER} ($id)")
                    Result.success()
                }
            }
        } catch (t: Throwable) {
            PinLog.e("Upload ${transcript.name} failed (attempt ${runAttemptCount + 1})", t)
            Result.retry()
        }
    }
}

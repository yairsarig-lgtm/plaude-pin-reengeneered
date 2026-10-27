package com.yairsarig.pinlink.upload

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
        if (marker.exists()) return@withContext Result.success()
        if (!transcript.exists()) return@withContext Result.failure().also { PinLog.e("Upload: ${transcript.name} missing") }

        try {
            val token = when (val auth = DriveAuth.authorize(applicationContext)) {
                is DriveAuth.Outcome.Token -> auth.accessToken
                is DriveAuth.Outcome.NeedsConsent -> {
                    PinLog.w("Upload ${transcript.name}: Drive not connected — open PinLink and tap Connect Drive")
                    return@withContext Result.retry()
                }
            }
            val drive = DriveClient(token)
            val prefs = applicationContext.getSharedPreferences(PinConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val folderId = drive.findOrCreateFolder(PinConfig.DRIVE_FOLDER, prefs.getString(PinConfig.PREF_DRIVE_FOLDER_ID, null))
            prefs.edit().putString(PinConfig.PREF_DRIVE_FOLDER_ID, folderId).apply()

            val existing = drive.findFile(folderId, transcript.name)
            val id = existing ?: drive.uploadText(folderId, transcript.name, transcript.readText())
            marker.writeText(id)
            PinLog.i(if (existing != null) "Upload ${transcript.name}: already in Drive ($id)" else "Uploaded ${transcript.name} -> ${PinConfig.DRIVE_FOLDER} ($id)")
            Result.success()
        } catch (t: Throwable) {
            PinLog.e("Upload ${transcript.name} failed (attempt ${runAttemptCount + 1})", t)
            Result.retry()
        }
    }
}

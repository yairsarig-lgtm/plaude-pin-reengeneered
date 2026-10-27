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
        if (!transcript.exists()) return@withContext Result.failure().also { PinLog.e("Upload: ${transcript.name} missing") }

        try {
            val token = when (val auth = DriveAuth.authorize(applicationContext)) {
                is DriveAuth.Outcome.Token -> auth.accessToken
                is DriveAuth.Outcome.NeedsConsent -> {
                    // Retrying can't help; Connect Drive re-queues everything pending.
                    PinLog.w("Upload ${transcript.name}: Drive not connected — open PinLink and tap Connect Drive")
                    return@withContext Result.failure()
                }
            }
            val drive = DriveClient(token)
            val prefs = applicationContext.getSharedPreferences(PinConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val folderId = drive.findOrCreateFolder(PinConfig.DRIVE_FOLDER, prefs.getString(PinConfig.PREF_DRIVE_FOLDER_ID, null))
            prefs.edit().putString(PinConfig.PREF_DRIVE_FOLDER_ID, folderId).apply()

            if (marker.exists()) {
                // Already uploaded: just make sure it sits in the inbox folder.
                val id = marker.readText().trim()
                val moved = drive.ensureInFolder(id, folderId)
                PinLog.i("Upload ${transcript.name}: already uploaded ($id)" + if (moved) ", moved into ${PinConfig.DRIVE_FOLDER}" else ", in ${PinConfig.DRIVE_FOLDER}")
                return@withContext Result.success()
            }
            val existing = drive.findFile(folderId, transcript.name)
            val id = existing ?: drive.uploadText(folderId, transcript.name, transcript.readText())
            drive.ensureInFolder(id, folderId)
            marker.writeText(id)
            PinLog.i(if (existing != null) "Upload ${transcript.name}: already in ${PinConfig.DRIVE_FOLDER} ($id)" else "Uploaded ${transcript.name} -> ${PinConfig.DRIVE_FOLDER} ($id)")
            Result.success()
        } catch (t: Throwable) {
            PinLog.e("Upload ${transcript.name} failed (attempt ${runAttemptCount + 1})", t)
            Result.retry()
        }
    }
}

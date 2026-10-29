package com.yairsarig.pinlink

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.yairsarig.pinlink.transcribe.ModelManager
import com.yairsarig.pinlink.transcribe.TranscribeWorker
import com.yairsarig.pinlink.upload.UploadWorker
import com.yairsarig.pinlink.transcribe.Transcript
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The on-disk stages after a recording is pulled off the pin, and the WorkManager jobs between
 * them. Every stage writes its output before the next is enqueued, and every job is unique per
 * recording, so crashes and re-enqueues are harmless.
 *
 *   recordings/<stamp>_sync.opuspk --TranscribeWorker--> transcripts/<stamp>.txt --UploadWorker--> Drive
 */
object Pipeline {
    const val KEY_CAPTURE = "capture"
    const val KEY_SESSION_ID = "sessionId"

    fun recordingsDir(context: Context) = File(context.getExternalFilesDir(null), PinConfig.DIR_RECORDINGS).apply { mkdirs() }
    fun transcriptsDir(context: Context) = File(context.getExternalFilesDir(null), PinConfig.DIR_TRANSCRIPTS).apply { mkdirs() }
    fun transcriptFile(context: Context, sessionId: Long) = File(transcriptsDir(context), Transcript.fileName(sessionId))
    fun uploadedMarker(transcript: File) = File(transcript.path + PinConfig.UPLOADED_MARKER_EXT)
    fun skippedMarker(transcript: File) = File(transcript.path + PinConfig.SKIPPED_MARKER_EXT)
    /** Uploaded, or deliberately not (empty/looping transcript). Either way nothing left to do. */
    fun isFinished(transcript: File) = uploadedMarker(transcript).exists() || skippedMarker(transcript).exists()

    fun enqueueTranscription(context: Context, capture: File, sessionId: Long) {
        // The first run downloads the 148 MB model: wait for Wi-Fi so it never burns mobile data.
        val network = if (ModelManager(context).isReady) NetworkType.NOT_REQUIRED else NetworkType.UNMETERED
        val req = OneTimeWorkRequestBuilder<TranscribeWorker>()
            .setInputData(workDataOf(KEY_CAPTURE to capture.absolutePath, KEY_SESSION_ID to sessionId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PinConfig.WORK_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("transcribe-$sessionId", ExistingWorkPolicy.KEEP, req)
        PinLog.i("Queued transcription of ${capture.name}")
    }

    /** [policy] REPLACE restarts a job stuck in backoff; safe because uploads are idempotent. */
    fun enqueueUpload(context: Context, sessionId: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val req = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(KEY_SESSION_ID to sessionId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PinConfig.WORK_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("upload-$sessionId", policy, req)
    }

    /** Memos on the phone not yet through the pipeline (for the status dot and Settings). */
    data class Status(val toTranscribe: Int, val toUpload: Int) {
        val pending: Int get() = toTranscribe + toUpload
    }

    fun status(context: Context): Status {
        val captures = recordingsDir(context).listFiles { f -> f.name.endsWith(PinConfig.SYNC_SUFFIX + PinConfig.RAW_PACKET_EXT) }.orEmpty()
        var transcribe = 0
        var upload = 0
        for (c in captures) {
            val sid = sessionIdOf(c) ?: continue
            val t = transcriptFile(context, sid)
            if (!t.exists()) transcribe++ else if (!isFinished(t)) upload++
        }
        return Status(transcribe, upload)
    }

    /** When the newest transcript reached Drive (its marker's time), null if none on the phone. */
    fun lastUploadAt(context: Context): Long? =
        transcriptsDir(context).listFiles { f -> f.name.endsWith(PinConfig.UPLOADED_MARKER_EXT) }
            ?.maxOfOrNull { it.lastModified() }

    /** Re-queues anything a crash or reboot left half-way. Safe to call any time (app start, after Connect Drive). */
    fun resumePending(context: Context) {
        val captures = recordingsDir(context).listFiles { f -> f.name.endsWith(PinConfig.SYNC_SUFFIX + PinConfig.RAW_PACKET_EXT) }.orEmpty()
        for (c in captures) {
            val sid = sessionIdOf(c) ?: continue
            val transcript = transcriptFile(context, sid)
            if (!transcript.exists()) enqueueTranscription(context, c, sid)
            else if (!isFinished(transcript)) enqueueUpload(context, sid, ExistingWorkPolicy.REPLACE)
        }
    }

    /**
     * Captures are named `<local start time>_<kind>.opuspk`, and a sessionId is the start time, so
     * parse it back. Ambiguous only in the repeated hour when clocks go back.
     */
    fun sessionIdOf(capture: File): Long? = runCatching {
        LocalDateTime.parse(capture.name.take(PinConfig.FILE_NAME_PATTERN.length), stampFmt)
            .atZone(ZoneId.systemDefault()).toEpochSecond()
    }.getOrNull()

    private val stampFmt = DateTimeFormatter.ofPattern(PinConfig.FILE_NAME_PATTERN)
}

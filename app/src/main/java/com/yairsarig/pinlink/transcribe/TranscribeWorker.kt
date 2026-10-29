package com.yairsarig.pinlink.transcribe

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import com.yairsarig.pinlink.Pipeline
import com.yairsarig.pinlink.audio.OpusDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * recordings/<stamp>_sync.opuspk -> transcripts/<stamp>.txt, then hands off to upload.
 * Idempotent: an existing transcript is never redone.
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val capture = File(inputData.getString(Pipeline.KEY_CAPTURE) ?: return@withContext Result.failure())
        val sessionId = inputData.getLong(Pipeline.KEY_SESSION_ID, -1).takeIf { it > 0 } ?: return@withContext Result.failure()
        val out = Pipeline.transcriptFile(applicationContext, sessionId)
        if (!out.exists()) {
            if (!capture.exists()) return@withContext Result.failure().also { PinLog.e("Transcribe: ${capture.name} missing") }
            try {
                val model = ModelManager(applicationContext).ensureModel()
                val pcm = OpusDecoder.decodeToPcm(capture)
                val samples = Resampler(pcm.sampleRate / PinConfig.WHISPER_SAMPLE_RATE).downsample(pcm.samples)
                val raw = WhisperEngine.transcribe(model, samples)
                val guarded = TranscriptGuard.clean(raw)
                // Skipped memos keep the raw text locally for inspection; the marker goes first so
                // the transcript never exists without it.
                if (!guarded.upload) Pipeline.skippedMarker(out).writeText(raw)
                val tmp = File(out.path + ".tmp")
                tmp.writeText(Transcript.render(sessionId, guarded.text))
                check(tmp.renameTo(out)) { "rename failed" }
                if (guarded.upload) {
                    PinLog.i("Transcript ${out.name}: ${guarded.text.take(PinConfig.LOG_TRANSCRIPT_CHARS)}" +
                        if (guarded.repeats > 0) " (dropped ${guarded.repeats} repeats)" else "")
                } else {
                    PinLog.w("Transcript ${out.name} empty or looping (${guarded.repeats}/${guarded.sentences} repeats) — not uploading: " +
                        raw.trim().take(PinConfig.LOG_TRANSCRIPT_CHARS))
                }
            } catch (t: Throwable) {
                PinLog.e("Transcribe ${capture.name} failed (attempt ${runAttemptCount + 1})", t)
                return@withContext if (runAttemptCount < PinConfig.WORK_MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        }
        if (!Pipeline.skippedMarker(out).exists()) Pipeline.enqueueUpload(applicationContext, sessionId)
        Result.success()
    }
}

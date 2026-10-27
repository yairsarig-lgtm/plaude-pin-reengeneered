package com.yairsarig.pinlink.housekeeping

import com.yairsarig.pinlink.PinConfig
import java.io.File

/**
 * What local files may be deleted. Pure (file listing + clock in, list out) so it's unit-testable.
 *
 * - A memo's files (transcript, upload marker, captures, WAVs) go once its transcript has been in
 *   Drive for [maxAgeMs], measured from the upload marker's time.
 * - Live previews and WAVs go after [maxAgeMs] regardless (the `_sync` capture is the real copy).
 * - Logs go after [maxAgeMs].
 * - Never: a `_sync` or `_partial` capture whose transcript isn't uploaded yet.
 */
object Retention {

    fun select(transcriptsDir: File, recordingsDir: File, logsDir: File, nowMs: Long, maxAgeMs: Long): List<File> {
        val old = { f: File -> nowMs - f.lastModified() > maxAgeMs }
        val out = mutableListOf<File>()
        val recordings = recordingsDir.listFiles().orEmpty()

        for (marker in transcriptsDir.listFiles { f -> f.name.endsWith(PinConfig.UPLOADED_MARKER_EXT) }.orEmpty()) {
            if (!old(marker)) continue
            val transcript = File(marker.path.removeSuffix(PinConfig.UPLOADED_MARKER_EXT))
            val stamp = transcript.name.removeSuffix(PinConfig.TRANSCRIPT_EXT)
            out += marker
            if (transcript.exists()) out += transcript
            out += recordings.filter { it.name.startsWith(stamp + "_") || it.name == stamp + PinConfig.WAV_EXT }
        }
        out += recordings.filter { f ->
            old(f) && (f.name.endsWith(PinConfig.LIVE_SUFFIX + PinConfig.RAW_PACKET_EXT) || f.name.endsWith(PinConfig.WAV_EXT))
        }
        out += logsDir.listFiles().orEmpty().filter(old)
        return out.distinct()
    }
}

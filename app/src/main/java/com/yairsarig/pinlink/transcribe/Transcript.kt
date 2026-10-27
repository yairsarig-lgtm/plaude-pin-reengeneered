package com.yairsarig.pinlink.transcribe

import com.yairsarig.pinlink.PinConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The transcript file that gets uploaded: `YYYY-MM-DD_HH-MM-SS.txt`, first line the recording
 * time in ISO 8601 with the phone's UTC offset, then the transcript.
 */
object Transcript {
    private val nameFmt = DateTimeFormatter.ofPattern(PinConfig.FILE_NAME_PATTERN)

    /** [sessionId] is the recording's start time in Unix seconds. */
    fun fileName(sessionId: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        nameFmt.format(Instant.ofEpochSecond(sessionId).atZone(zone)) + PinConfig.TRANSCRIPT_EXT

    fun render(sessionId: Long, text: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val recorded = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochSecond(sessionId).atZone(zone))
        val body = text.lines().joinToString("\n") { it.trim() }.trim()
        return "${PinConfig.TRANSCRIPT_HEADER_PREFIX}$recorded\n\n$body\n"
    }
}

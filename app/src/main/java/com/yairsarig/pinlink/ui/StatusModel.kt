package com.yairsarig.pinlink.ui

import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.Pipeline
import com.yairsarig.pinlink.SendNow
import com.yairsarig.pinlink.ble.LinkState
import com.yairsarig.pinlink.claude.ClaudeTrigger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The pin button's dot. [legend] is the Settings › Status colors line. Colors live in [Pocket]. */
enum class PinStatus(val legend: String) {
    GREEN("Connected to the pin, nothing waiting"),
    AMBER("Working: pulling from the pin, transcribing or uploading"),
    BLUE("Sent to Claude, waiting for it to process (about 2 minutes)"),
    GREY("Pin not connected: out of range, Bluetooth off, or reconnecting"),
    RED("Needs attention: Drive disconnected, or the Claude token is missing or rejected"),
}

/** Everything the dot and the status line depend on. */
data class StatusInputs(
    val bluetoothOn: Boolean,
    val link: LinkState,
    val pinRecording: Boolean,
    val transferring: Boolean,
    val filesOnPin: Int,
    val pipeline: Pipeline.Status,
    val sendPhase: SendNow.Phase?,
    val driveConnected: Boolean?,
    val claude: ClaudeTrigger.State,
    val now: Long,
)

data class StatusView(val dot: PinStatus, val line: String) {
    /** The dot pulses gently while working. */
    val pulsing: Boolean get() = dot == PinStatus.AMBER
}

/** Pure: inputs -> dot + one plain-words line. Work in progress wins, then problems, then idle states. */
object StatusModel {
    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun of(s: StatusInputs, zone: ZoneId = ZoneId.systemDefault()): StatusView {
        fun amber(line: String) = StatusView(PinStatus.AMBER, line)
        fun red(line: String) = StatusView(PinStatus.RED, line)
        val linked = s.link == LinkState.READY
        return when {
            s.sendPhase != null -> amber(s.sendPhase.line)
            s.pinRecording -> amber("Recording on pin…")
            s.transferring || (linked && s.filesOnPin > 0) -> amber("Pulling memo from pin…")
            s.pipeline.toTranscribe > 0 -> amber("Transcribing…")
            s.driveConnected == false -> red("Drive needs reconnecting")
            s.pipeline.toUpload > 0 -> amber("Uploading…")
            s.claude.rejected -> red("Claude rejected the routine — check Settings")
            !s.claude.configured -> red("Add your Claude routine in Settings")
            s.claude.firing || (s.claude.scheduled && s.claude.auto && s.claude.uploadsSinceFire > 0) -> amber("Sending to Claude…")
            s.claude.lastFireAt > 0 && s.now - s.claude.lastFireAt < PinConfig.CLAUDE_PROCESSING_MS ->
                StatusView(PinStatus.BLUE, "Sent to Claude ✓ " + clock.format(Instant.ofEpochMilli(s.claude.lastFireAt).atZone(zone)))
            !s.bluetoothOn -> StatusView(PinStatus.GREY, "Bluetooth is off")
            s.link == LinkState.CONNECTING -> StatusView(PinStatus.GREY, "Reconnecting to pin…")
            !linked -> StatusView(PinStatus.GREY, "Pin not in range")
            s.claude.uploadsSinceFire > 0 -> StatusView(PinStatus.GREEN, "Connected · uploaded, Claude checks hourly")
            else -> StatusView(PinStatus.GREEN, "Connected · nothing waiting")
        }
    }
}

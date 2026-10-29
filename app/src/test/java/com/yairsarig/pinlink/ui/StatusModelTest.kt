package com.yairsarig.pinlink.ui

import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.Pipeline
import com.yairsarig.pinlink.SendNow
import com.yairsarig.pinlink.ble.LinkState
import com.yairsarig.pinlink.claude.ClaudeTrigger
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StatusModelTest {
    private val zone = ZoneId.of("Asia/Jerusalem")
    private val now = ZonedDateTime.of(2026, 10, 29, 21, 5, 0, 0, zone).toInstant().toEpochMilli()
    private val ready = ClaudeTrigger.State(configured = true)
    private val idle = StatusInputs(true, LinkState.READY, false, false, 0, Pipeline.Status(0, 0), null, true, ready, now)

    private fun of(s: StatusInputs) = StatusModel.of(s, zone).let { it.dot to it.line }

    @Test fun idleConnected() = assertEquals(PinStatus.GREEN to "Connected · nothing waiting", of(idle))

    @Test fun workingIsAmberAndPulses() {
        assertEquals(PinStatus.AMBER to "Pulling memo from pin…", of(idle.copy(filesOnPin = 1)))
        assertEquals(PinStatus.AMBER to "Transcribing…", of(idle.copy(pipeline = Pipeline.Status(1, 0))))
        assertEquals(PinStatus.AMBER to "Uploading…", of(idle.copy(pipeline = Pipeline.Status(0, 1))))
        assertEquals(PinStatus.AMBER to "Connecting to pin…", of(idle.copy(link = LinkState.DISCONNECTED, sendPhase = SendNow.Phase.CONNECTING)))
        assertEquals(true, StatusModel.of(idle.copy(transferring = true), zone).pulsing)
        assertEquals(false, StatusModel.of(idle, zone).pulsing)
    }

    @Test fun sentIsBlueForTwoMinutes() {
        val fired = idle.copy(claude = ready.copy(lastFireAt = now - 60_000))
        assertEquals(PinStatus.BLUE to "Sent to Claude ✓ 21:04", of(fired))
        val later = idle.copy(claude = ready.copy(lastFireAt = now - PinConfig.CLAUDE_PROCESSING_MS))
        assertEquals(PinStatus.GREEN, of(later).first)
    }

    @Test fun debouncedFireIsWorking() {
        val s = idle.copy(claude = ready.copy(uploadsSinceFire = 2, scheduled = true))
        assertEquals(PinStatus.AMBER to "Sending to Claude…", of(s))
        // Auto-send off: the hourly check will get it.
        assertEquals(PinStatus.GREEN to "Connected · uploaded, Claude checks hourly", of(s.copy(claude = s.claude.copy(auto = false))))
    }

    @Test fun notConnectedIsGrey() {
        assertEquals(PinStatus.GREY to "Pin not in range", of(idle.copy(link = LinkState.DISCONNECTED)))
        assertEquals(PinStatus.GREY to "Reconnecting to pin…", of(idle.copy(link = LinkState.CONNECTING)))
        assertEquals(PinStatus.GREY to "Bluetooth is off", of(idle.copy(link = LinkState.DISCONNECTED, bluetoothOn = false)))
    }

    @Test fun problemsAreRed() {
        assertEquals(PinStatus.RED to "Drive needs reconnecting", of(idle.copy(driveConnected = false, pipeline = Pipeline.Status(0, 1))))
        assertEquals(PinStatus.RED to "Add your Claude routine in Settings", of(idle.copy(claude = ClaudeTrigger.State())))
        assertEquals(PinStatus.RED to "Claude rejected the routine — check Settings", of(idle.copy(claude = ready.copy(rejected = true))))
        // Red outranks grey: a broken setup shows even with the pin away.
        assertEquals(PinStatus.RED, of(idle.copy(link = LinkState.DISCONNECTED, driveConnected = false)).first)
        // Transcription doesn't need Drive, so it still shows as work.
        assertEquals(PinStatus.AMBER, of(idle.copy(driveConnected = false, pipeline = Pipeline.Status(1, 0))).first)
    }
}

package com.yairsarig.pinlink

import java.util.UUID

/** Every tunable value in the app lives here. */
object PinConfig {
    const val LOG_TAG = "PINLINK"

    // --- Discovery ---
    const val NAME_PREFIX = "plaud"
    const val NAME_CONTAINS = "notepin"
    const val SCAN_TIMEOUT_MS = 20_000L

    // --- GATT (from Omi app/lib/services/devices/models.dart) ---
    val SERVICE_UUID: UUID = UUID.fromString("00001910-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("00002bb1-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("00002bb0-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- Diagnostics: other characteristics found on the 2024 NotePin ---
    val STATE_CHAR_UUID: UUID = UUID.fromString("0000b001-0000-1000-8000-00805f9b34fb")      // 1910, read
    val B004_CHAR_UUID: UUID = UUID.fromString("0000b004-0000-1000-8000-00805f9b34fb")       // 1910, indicate
    val FD44_SERVICE_UUID: UUID = UUID.fromString("0000fd44-0000-1000-8000-00805f9b34fb")
    val FD44_CHAR_UUIDS: List<UUID> = (1..4).map { UUID.fromString("4f86000$it-943b-49ef-bed4-2f730304427a") } // write+indicate
    val BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    /** (service, characteristic) pairs subscribed best-effort after the main notify char. */
    val EXTRA_SUBSCRIPTIONS: List<Pair<UUID, UUID>> =
        listOf(SERVICE_UUID to B004_CHAR_UUID) + FD44_CHAR_UUIDS.map { FD44_SERVICE_UUID to it }

    /** (service, characteristic) pairs read and logged by the diagnostics run. */
    val DIAGNOSTIC_READS: List<Pair<UUID, UUID>> =
        listOf(BATTERY_SERVICE_UUID to BATTERY_LEVEL_UUID, SERVICE_UUID to STATE_CHAR_UUID)

    /** Pause between the two cmd 9 write-type attempts so responses can't be confused. */
    const val DIAGNOSTIC_GAP_MS = 1_000L

    /** Default ATT MTU (23) truncates audio notifications, so ask for the max. */
    const val REQUESTED_MTU = 517
    const val POST_CONNECT_DELAY_MS = 2_000L
    const val GATT_OP_TIMEOUT_MS = 10_000L
    const val COMMAND_RESPONSE_TIMEOUT_MS = 10_000L

    // --- Live session recipe (Omi plaud_connection.dart) ---
    const val STOP_TO_START_DELAY_MS = 500L
    const val START_TO_SYNC_DELAY_MS = 1_000L
    const val SESSION_SETUP_ATTEMPTS = 3

    // --- Reconnect ---
    const val RECONNECT_INITIAL_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L

    // --- Audio ---
    const val OPUS_SAMPLE_RATE = 16_000
    const val OPUS_CHANNELS = 1
    /** Omi slices the stream into fixed 80-byte Opus frames (20 ms @ 32 kbps CBR). Unverified. */
    const val OPUS_FIXED_FRAME_BYTES = 80
    const val OPUS_FRAME_DURATION_US = 20_000L

    // --- Storage (under getExternalFilesDir so `adb pull` works) ---
    const val DIR_RECORDINGS = "recordings"
    const val DIR_LOGS = "logs"
    const val RAW_PACKET_EXT = ".opuspk"
    const val WAV_EXT = ".wav"

    // --- UI ---
    const val UI_LOG_LINES = 300

    // --- Prefs ---
    const val PREFS_NAME = "pinlink"
    const val PREF_LAST_ADDRESS = "last_address"
}

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

    // --- Sync ---
    /** Follow a pin-button recording live (cmd 28 with end=0), as the Plaud app does. */
    const val FOLLOW_BUTTON_RECORDINGS_LIVE = true
    /** Pull the complete file when the pin reports a recording stopped. */
    const val SYNC_ON_STOP = true
    /** Pull everything the pin lists on every (re)connect. Interrupted pulls resume where they stopped. */
    const val SYNC_ON_CONNECT = true
    /** Sync timeout = base + size / expected throughput. */
    const val SYNC_TIMEOUT_BASE_MS = 15_000L
    const val SYNC_MIN_BYTES_PER_SEC = 2_000L
    const val FILE_NAME_PATTERN = "yyyy-MM-dd_HH-mm-ss"

    // --- Live session recipe (Omi plaud_connection.dart) ---
    const val STOP_TO_START_DELAY_MS = 500L
    const val START_TO_SYNC_DELAY_MS = 1_000L
    const val SESSION_SETUP_ATTEMPTS = 3

    // --- Link health ---
    /** If nothing has arrived from the pin for this long, ping it (cmd 9); no answer = dead link. */
    const val HEALTH_CHECK_INTERVAL_MS = 45_000L

    // --- Reconnect ---
    const val RECONNECT_INITIAL_DELAY_MS = 1_000L
    const val RECONNECT_MAX_DELAY_MS = 30_000L

    // --- Audio ---
    const val OPUS_SAMPLE_RATE = 16_000
    const val OPUS_CHANNELS = 1
    /** Fixed 80-byte Opus frames, TOC 0xb8 (CELT WB 20 ms) — confirmed from capture. */
    const val OPUS_FIXED_FRAME_BYTES = 80
    const val OPUS_FRAME_DURATION_US = 20_000L
    /** CBR 32 kbps: 80-byte frames every 20 ms (confirmed from capture, see docs/PROTOCOL.md). */
    const val OPUS_BYTES_PER_SEC = 4_000L

    // --- Storage (under getExternalFilesDir so `adb pull` works) ---
    const val DIR_RECORDINGS = "recordings"
    const val DIR_LOGS = "logs"
    const val RAW_PACKET_EXT = ".opuspk"
    const val WAV_EXT = ".wav"

    const val DIR_TRANSCRIPTS = "transcripts"
    const val DIR_MODELS = "models"
    const val SYNC_SUFFIX = "_sync"
    const val LIVE_SUFFIX = "_live"
    const val PARTIAL_SUFFIX = "_partial"
    const val TRANSCRIPT_EXT = ".txt"
    const val TRANSCRIPT_HEADER_PREFIX = "Recorded: "

    // --- Transcription (whisper.cpp) ---
    const val WHISPER_MODEL_FILE = "ggml-base.en.bin"
    const val WHISPER_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin"
    const val WHISPER_MODEL_SHA256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002"
    const val WHISPER_MODEL_BYTES = 147_964_211L
    const val WHISPER_SAMPLE_RATE = 16_000
    const val WHISPER_LANGUAGE = "en"
    const val WHISPER_MAX_THREADS = 4
    const val LOG_TRANSCRIPT_CHARS = 120

    // --- Drive upload ---
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val DRIVE_FOLDER = "Pin Inbox"
    const val PREF_DRIVE_FOLDER_ID = "drive_folder_id"
    const val UPLOADED_MARKER_EXT = ".uploaded"
    const val HTTP_TIMEOUT_MS = 30_000

    // --- Housekeeping ---
    /** Local copies are deleted this long after their transcript reached Drive. */
    const val RETENTION_DAYS = 7L
    val XIAOMI_MANUFACTURERS = setOf("xiaomi", "redmi", "poco")

    // --- Background work ---
    const val WORK_BACKOFF_SECONDS = 30L
    const val WORK_MAX_ATTEMPTS = 5

    // --- UI ---
    const val UI_LOG_LINES = 300
    /** Log only every Nth audio packet (plus the first 3 of each transfer). */
    const val AUDIO_LOG_EVERY_N = 200

    // --- Prefs ---
    const val PREFS_NAME = "pinlink"
    const val PREF_LAST_ADDRESS = "last_address"
}

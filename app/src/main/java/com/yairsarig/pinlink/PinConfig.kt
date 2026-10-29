package com.yairsarig.pinlink

import java.util.UUID

/** Every tunable value in the app lives here. */
object PinConfig {
    const val LOG_TAG = "PINLINK"
    /** User-facing name (package and log tag stay pinlink: the OAuth client is tied to the package). */
    const val APP_NAME = "Pocket Ideas"

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
    /** Whisper loop guard: skip upload when more than this share of sentences are repeats. */
    const val TRANSCRIPT_MAX_REPEAT_FRACTION = 0.5
    /** Beside a transcript the guard judged empty/looping: never uploaded, cleaned up like an uploaded one. */
    const val SKIPPED_MARKER_EXT = ".skipped"

    // --- Drive upload ---
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    const val DRIVE_FOLDER = "Pin Inbox"
    const val PREF_DRIVE_FOLDER_ID = "drive_folder_id"
    const val UPLOADED_MARKER_EXT = ".uploaded"
    const val HTTP_TIMEOUT_MS = 30_000

    // --- Ideas sheet (app-owned "Pin Ideas"; the hourly Claude task writes it, the app reads/deletes) ---
    const val SHEET_TITLE = "Pin Ideas"
    const val SHEET_TAB_IDEAS = "Ideas"
    const val SHEET_TAB_LOG = "Log"
    val SHEET_IDEAS_HEADERS = listOf("Recorded", "Idea", "List", "Original wording", "Status", "Source file")
    val SHEET_LOG_HEADERS = listOf("Processed", "Source file", "Transcript", "Action taken", "Result")
    const val PREF_SHEET_ID = "sheet_id"
    /** An idea "needs review" when its Status contains this (case-insensitive). */
    const val STATUS_NEEDS_REVIEW = "review"
    const val RECENT_ACTIONS = 10
    /** Recent actions shows only the Log rows from this many days back. */
    const val RECENT_ACTIONS_DAYS = 7L
    const val UNDO_WINDOW_MS = 5_000L
    const val SHEET_URL_PREFIX = "https://docs.google.com/spreadsheets/d/"

    // --- Claude routine (https://platform.claude.com/docs/en/api/claude-code/routines-fire) ---
    /** URL and token are typed in by the owner and kept in EncryptedSharedPreferences; never logged. */
    const val SECURE_PREFS_NAME = "pinlink_secure"
    const val PREF_ROUTINE_URL = "routine_url"
    const val PREF_ROUTINE_TOKEN = "routine_token"
    const val PREF_CLAUDE_AUTO = "claude_auto"
    const val PREF_UPLOADS_SINCE_FIRE = "uploads_since_fire"
    const val PREF_FIRE_TIMES = "fire_times"
    const val PREF_LAST_FIRE_AT = "last_fire_at"
    const val PREF_CLAUDE_REJECTED = "claude_rejected"
    const val ROUTINE_API_VERSION = "2026-10-21"
    const val ROUTINE_URL_PREFIX = "https://"
    /** Background uploads wait this long for more memos, so a burst becomes one fire. */
    const val FIRE_DEBOUNCE_SECONDS = 60L
    /** Our own cap, under the routine's 30/hour. Past it, the hourly task picks memos up. */
    const val MAX_FIRES_PER_HOUR = 20
    const val FIRE_WINDOW_MS = 3_600_000L
    const val FIRE_MAX_ATTEMPTS = 3
    /** After a fire, the dot stays blue this long, then the ideas list refreshes. */
    const val CLAUDE_PROCESSING_MS = 120_000L
    const val FIRE_TEXT_TEMPLATE = "Triggered from Pocket Ideas: %d new memo(s) uploaded."
    const val FIRE_TEST_TEXT = "Test from Pocket Ideas settings: no new memos, nothing to do."

    // --- Send now (tap the pin button) ---
    const val SEND_CONNECT_TIMEOUT_MS = 30_000L
    /** Transcribing + uploading; past this the snackbar says it carries on in the background. */
    const val SEND_PIPELINE_TIMEOUT_MS = 360_000L
    const val SEND_POLL_MS = 1_000L

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

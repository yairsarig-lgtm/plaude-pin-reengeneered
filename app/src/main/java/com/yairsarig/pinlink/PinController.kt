package com.yairsarig.pinlink

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.yairsarig.pinlink.audio.OpusDecoder
import com.yairsarig.pinlink.audio.OpusPacketFile
import com.yairsarig.pinlink.audio.OpusPacketRecorder
import com.yairsarig.pinlink.ble.LinkState
import com.yairsarig.pinlink.ble.PinGattClient
import com.yairsarig.pinlink.ble.PinScanner
import com.yairsarig.pinlink.ble.WriteMode
import com.yairsarig.pinlink.protocol.PinNotification
import com.yairsarig.pinlink.protocol.PlaudCommand
import com.yairsarig.pinlink.protocol.PlaudProtocol
import com.yairsarig.pinlink.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1 brain: owns the link, routes notifications, follows/syncs recordings, records audio.
 * Process-wide singleton (see [PinLinkApp]); the foreground service just keeps the process alive.
 * Protocol details: docs/PROTOCOL.md.
 */
class PinController(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val scanner = PinScanner(context)
    private val client = PinGattClient(context, ::onNotification, ::onUnexpectedDisconnect)
    val linkState: StateFlow<LinkState> = client.state

    private val _battery = MutableStateFlow<PlaudProtocol.Battery?>(null)
    val battery: StateFlow<PlaudProtocol.Battery?> = _battery
    private val _recording = MutableStateFlow<String?>(null)
    val recording: StateFlow<String?> = _recording
    private val _lastCapture = MutableStateFlow<File?>(null)
    val lastCapture: StateFlow<File?> = _lastCapture
    /** True while the pin is recording (button or live session), between its start and stop events. */
    private val _pinRecording = MutableStateFlow(false)
    val pinRecording: StateFlow<Boolean> = _pinRecording
    private val _storedFiles = MutableStateFlow<List<PlaudProtocol.StoredFile>>(emptyList())
    val storedFiles: StateFlow<List<PlaudProtocol.StoredFile>> = _storedFiles
    private val _bluetoothOn = MutableStateFlow(
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled == true)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn

    private val prefs = context.getSharedPreferences(PinConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private val recordingsDir = Pipeline.recordingsDir(context)
    private val pendingResponses = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    private val stampFmt = SimpleDateFormat(PinConfig.FILE_NAME_PATTERN, Locale.US)

    /** One transfer (live follow or file sync) at a time. */
    private val transferLock = Mutex()
    @Volatile private var recorder: OpusPacketRecorder? = null
    @Volatile private var recorderSession: Long? = null
    @Volatile private var transferDone: CompletableDeferred<Unit>? = null
    @Volatile private var liveSessionId: Long? = null
    @Volatile private var userWantsLink = false
    private var reconnectJob: Job? = null
    private var healthJob: Job? = null
    @Volatile private var lastRxAt = 0L
    private var audioPacketsSinceUiLog = 0

    /**
     * Android often sends GATT clients no disconnect callback when the Bluetooth adapter turns
     * off, leaving a link that looks READY but is dead. Watch the adapter directly.
     */
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    _bluetoothOn.value = false
                    reconnectJob?.cancel()
                    if (client.state.value != LinkState.DISCONNECTED) handleDeadLink("Bluetooth turned off", reconnect = false)
                }
                BluetoothAdapter.STATE_ON -> {
                    _bluetoothOn.value = true
                    PinLog.i("Bluetooth on")
                    if (userWantsLink && client.state.value == LinkState.DISCONNECTED) connect()
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(context, adapterReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        _lastCapture.value = recordingsDir.listFiles { f -> f.name.endsWith(PinConfig.RAW_PACKET_EXT) }
            ?.maxByOrNull { it.lastModified() }
    }

    // ---------- Link ----------

    fun connect() = scope.launch {
        userWantsLink = true
        reconnectJob?.cancel()
        runCatching { connectOnce() }.onFailure {
            PinLog.e("Connect failed: ${it.message}")
            // Out of range, Bluetooth busy after boot, ...: keep trying in the background.
            if (userWantsLink && client.state.value != LinkState.READY) scheduleReconnect()
        }
    }

    /** Connects on launch if we've linked to a pin before and aren't linked already. */
    fun autoConnect() {
        if (client.state.value == LinkState.DISCONNECTED && prefs.contains(PinConfig.PREF_LAST_ADDRESS)) connect()
    }

    fun disconnect() = scope.launch {
        userWantsLink = false
        reconnectJob?.cancel()
        healthJob?.cancel()
        if (liveSessionId != null) stopLiveSession().join()
        client.close()
        closeRecorder()
    }

    private suspend fun connectOnce() {
        val device = prefs.getString(PinConfig.PREF_LAST_ADDRESS, null)?.let { scanner.deviceFor(it) }
            ?: scanner.find() ?: error("pin not found")
        client.connect(device)
        prefs.edit().putString(PinConfig.PREF_LAST_ADDRESS, device.address).apply()
        startHealthCheck()
        readBattery().join()
        if (PinConfig.SYNC_ON_CONNECT) syncAll()
    }

    /**
     * For Send now: true once the link is READY, connecting first if needed. Gives up after
     * [PinConfig.SEND_CONNECT_TIMEOUT_MS] (out of range); background reconnects carry on.
     */
    suspend fun ensureConnected(): Boolean {
        if (client.state.value == LinkState.READY) return true
        if (!_bluetoothOn.value) return false
        if (client.state.value == LinkState.DISCONNECTED) connect()
        return withTimeoutOrNull(PinConfig.SEND_CONNECT_TIMEOUT_MS) { client.state.first { it == LinkState.READY } } != null
    }

    /** Pings the pin when it has been quiet; a link that can't answer gets torn down and rebuilt. */
    private fun startHealthCheck() {
        healthJob?.cancel()
        lastRxAt = System.currentTimeMillis()
        healthJob = scope.launch {
            while (client.state.value == LinkState.READY) {
                delay(PinConfig.HEALTH_CHECK_INTERVAL_MS)
                if (client.state.value != LinkState.READY) break
                if (System.currentTimeMillis() - lastRxAt < PinConfig.HEALTH_CHECK_INTERVAL_MS) continue
                val alive = runCatching { command(PlaudCommand.GET_BATTERY) }.getOrNull() != null
                if (!alive) return@launch handleDeadLink("Link unresponsive", reconnect = true)
            }
        }
    }

    /** The link is dead but no GATT callback said so: close it and handle it like a drop. */
    private fun handleDeadLink(reason: String, reconnect: Boolean) {
        PinLog.w("$reason — closing link")
        client.close()
        healthJob?.cancel()
        _pinRecording.value = false
        transferDone?.cancel()
        closeRecorder()
        if (reconnect && userWantsLink) scheduleReconnect()
    }

    private fun onUnexpectedDisconnect() {
        healthJob?.cancel()
        PinLog.w("Link dropped")
        _pinRecording.value = false
        liveSessionId = null
        transferDone?.cancel()
        closeRecorder()
        if (userWantsLink) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var backoff = PinConfig.RECONNECT_INITIAL_DELAY_MS
            while (userWantsLink && client.state.value != LinkState.READY) {
                PinLog.i("Reconnecting in ${backoff}ms")
                delay(backoff)
                if (client.state.value == LinkState.READY) break // a drop's own reconnect got there first
                if (runCatching { connectOnce() }.isSuccess) break
                backoff = (backoff * 2).coerceAtMost(PinConfig.RECONNECT_MAX_DELAY_MS)
            }
        }
    }

    fun forgetPin() {
        prefs.edit().remove(PinConfig.PREF_LAST_ADDRESS).apply()
        PinLog.i("Forgot saved pin address; next connect will scan")
    }

    // ---------- Commands ----------

    /** Sends a command and waits for its response (null on timeout). */
    suspend fun command(
        cmdId: Int,
        payload: ByteArray = ByteArray(0),
        awaitResponse: Boolean = true,
        mode: WriteMode = WriteMode.AUTO,
    ): ByteArray? {
        val frame = PlaudProtocol.encodeCommand(cmdId, payload)
        val respId = PlaudCommand.responseIdFor(cmdId)
        val waiter = if (awaitResponse) CompletableDeferred<ByteArray>().also { pendingResponses[respId] = it } else null
        PinLog.i("TX cmd=$cmdId mode=$mode ${frame.toHex()}")
        client.write(frame, mode)
        if (waiter == null) return null
        return withTimeoutOrNull(PinConfig.COMMAND_RESPONSE_TIMEOUT_MS) { waiter.await() }
            .also { if (it == null) PinLog.w("cmd=$cmdId: no response") }
            .also { pendingResponses.remove(respId, waiter) }
    }

    fun readBattery() = scope.launch {
        val r = runCatching { command(PlaudCommand.GET_BATTERY) }.getOrNull() ?: return@launch
        _battery.value = PlaudProtocol.decodeBattery(r)
        PinLog.i("Battery: ${_battery.value}")
    }

    fun listFiles() = scope.launch {
        val r = runCatching { command(PlaudCommand.LIST_FILES, PlaudProtocol.listFilesPayload(nowEpoch())) }.getOrNull()
        val files = r?.let(PlaudProtocol::decodeFileList) ?: return@launch PinLog.w("List files failed")
        _storedFiles.value = files
        PinLog.i("Pin has ${files.size} stored file(s)" +
            files.joinToString("") { "\n  ${stamp(it.sessionId)} sid=${it.sessionId} ${it.bytes} B (~${it.bytes / PinConfig.OPUS_BYTES_PER_SEC}s) flags=${it.flags}" })
    }

    /** The header's pin button: connect if needed (connecting syncs), else pull whatever is pending. */
    fun syncNow() {
        if (client.state.value == LinkState.READY) syncAll() else connect()
    }

    private var syncJob: Deferred<Int>? = null

    /**
     * Pulls every file the pin lists; the result is how many came off complete. A call while a
     * sync is running joins it, so a connect's own sync and Send now never pull twice.
     */
    @Synchronized
    fun syncAll(): Deferred<Int> = syncJob?.takeIf { it.isActive } ?: scope.async {
        listFiles().join()
        syncPending()
    }.also { syncJob = it }

    /**
     * Sanity checks: read the standard battery char and b001, then try cmd 9 with both write
     * types (2bb1 advertises write and write-without-response).
     */
    fun runDiagnostics() = scope.launch {
        PinLog.i("--- diagnostics ---")
        for ((svc, chr) in PinConfig.DIAGNOSTIC_READS) {
            runCatching { client.read(svc, chr) }
                .onSuccess { PinLog.i("READ $chr = [${it.toHex()}] ascii='${it.printable()}' (${it.size} bytes)") }
                .onFailure { PinLog.w("READ $chr failed: ${it.message}") }
        }
        for (mode in listOf(WriteMode.WITH_RESPONSE, WriteMode.NO_RESPONSE)) {
            val r = runCatching { command(PlaudCommand.GET_BATTERY, mode = mode) }
                .onFailure { PinLog.w("cmd 9 ($mode) write failed: ${it.message}") }.getOrNull()
            PinLog.i("cmd 9 via $mode -> ${r?.toHex() ?: "no response"}")
            r?.let(PlaudProtocol::decodeBattery)?.let { _battery.value = it }
            delay(PinConfig.DIAGNOSTIC_GAP_MS)
        }
        PinLog.i("--- diagnostics done ---")
    }

    private fun ByteArray.printable() = map { b -> val c = b.toInt() and 0xFF; if (c in 32..126) c.toChar() else '.' }.joinToString("")

    /** Raw console for probing unknown commands. */
    fun sendRaw(cmdId: Int, payload: ByteArray, mode: WriteMode = WriteMode.AUTO) = scope.launch {
        runCatching { command(cmdId, payload, mode = mode) }
            .onSuccess { r -> PinLog.i("cmd=$cmdId response: ${r?.toHex() ?: "(none)"}") }
            .onFailure { PinLog.e("cmd=$cmdId failed", it) }
    }

    // ---------- Transfers ----------

    /**
     * Pulls a stored recording into `<start time>_partial.opuspk`, resuming from whatever an
     * earlier, interrupted attempt already got (the pin drops the link every ~2 minutes), and
     * renames it to `_sync` once every byte is there.
     */
    suspend fun syncFile(sessionId: Long, size: Long): Boolean = transferLock.withLock {
        val partial = captureFile(sessionId, PinConfig.PARTIAL_SUFFIX)
        val complete = captureFile(sessionId, PinConfig.SYNC_SUFFIX)
        var have = OpusPacketFile.trimToContiguous(partial)
        if (have == 0L) partial.delete()
        if (have > size) { partial.delete(); have = 0 } // stale partial from a different file size
        PinLog.i("Sync sid=$sessionId (${stamp(sessionId)}) $size B" + if (have > 0) ", resuming at $have" else "")
        val rec = openRecorder(sessionId, partial, resumeFrom = have)
        val done = CompletableDeferred<Unit>().also { transferDone = it }
        val ack = runCatching {
            command(PlaudCommand.SYNC_FILE_START, PlaudProtocol.syncFileStartPayload(sessionId, have, size))
        }.getOrNull()
        if (ack == null || ack.getOrNull(4)?.toInt() != 0) {
            PinLog.e("Sync sid=$sessionId refused: ${ack?.toHex()}")
            closeRecorder()
            return false
        }
        val timeout = PinConfig.SYNC_TIMEOUT_BASE_MS + (size - have) * 1000 / PinConfig.SYNC_MIN_BYTES_PER_SEC
        val finished = withTimeoutOrNull(timeout) { runCatching { done.await() }.isSuccess } == true
        runCatching { command(PlaudCommand.STOP_SYNC, PlaudProtocol.stopSyncPayload(sessionId)) }
        closeRecorder()
        val total = have + rec.bytes
        val ok = finished && total == size && rec.gaps == 0
        PinLog.i("Sync sid=$sessionId ${if (ok) "OK" else "INCOMPLETE"}: have $total/$size B, gaps=${rec.gaps}, finished=$finished")
        if (ok) {
            check(partial.renameTo(complete)) { "rename ${partial.name} failed" }
            _lastCapture.value = complete
            Pipeline.enqueueTranscription(context, complete, sessionId)
        }
        ok
    }

    /** Pulls everything the pin still lists (it stops listing a file once it has been synced). */
    private suspend fun syncPending(): Int {
        var pulled = 0
        for (f in _storedFiles.value) {
            if (client.state.value != LinkState.READY) return pulled
            if (syncFile(f.sessionId, f.bytes)) pulled++
        }
        if (_storedFiles.value.isNotEmpty()) listFiles().join()
        return pulled
    }

    /** Follows a recording that is still running (end = 0), until the pin says it's done. */
    private fun followLive(sessionId: Long) = scope.launch {
        transferLock.withLock {
            openRecorder(sessionId, "live")
            transferDone = CompletableDeferred()
            val ack = runCatching {
                command(PlaudCommand.SYNC_FILE_START, PlaudProtocol.syncFileStartPayload(sessionId, 0, 0))
            }.getOrNull()
            PinLog.i("Following sid=$sessionId live: ack=${ack?.toHex()}")
            if (ack == null) closeRecorder()
            // Recorder is closed by cmd 29 / end marker; the stop event then triggers a full sync.
        }
    }

    // ---------- Phone-initiated live session (Omi recipe, cmd 28 corrected) ----------

    fun startLiveSession() = scope.launch {
        repeat(PinConfig.SESSION_SETUP_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(attempt * 1_000L)
            try {
                command(PlaudCommand.STOP_RECORD, PlaudProtocol.stopRecordPayload(0))
                delay(PinConfig.STOP_TO_START_DELAY_MS)
                val started = command(PlaudCommand.START_RECORD, PlaudProtocol.startRecordPayload())
                    ?.let(PlaudProtocol::decodeStartRecord) ?: return@repeat
                PinLog.i("Recording started on pin: sid=${started.sessionId} (${stamp(started.sessionId)})")
                liveSessionId = started.sessionId
                _pinRecording.value = true
                delay(PinConfig.START_TO_SYNC_DELAY_MS)
                followLive(started.sessionId).join()
                if (recorder != null) return@launch
            } catch (t: Throwable) {
                PinLog.e("Live session attempt ${attempt + 1} failed", t)
            }
        }
        PinLog.e("Live session setup failed after ${PinConfig.SESSION_SETUP_ATTEMPTS} attempts")
    }

    fun stopLiveSession() = scope.launch {
        val id = liveSessionId ?: return@launch PinLog.w("No live session")
        liveSessionId = null
        _pinRecording.value = false
        val stopped = runCatching { command(PlaudCommand.STOP_RECORD, PlaudProtocol.stopRecordPayload(id)) }
            .onFailure { PinLog.e("Stop failed", it) }.getOrNull()?.let(PlaudProtocol::decodeStopRecord)
        PinLog.i("Recording stopped: $stopped")
        stopped?.let(::onRecordingStopped)
    }

    private fun onRecordingStopped(s: PlaudProtocol.RecordStopped) {
        if (!PinConfig.SYNC_ON_STOP || s.bytes == 0L) return
        scope.launch {
            // Let the live follow finish (cmd 29) before pulling the complete file.
            transferDone?.let { withTimeoutOrNull(PinConfig.COMMAND_RESPONSE_TIMEOUT_MS) { runCatching { it.await() } } }
            syncFile(s.sessionId, s.bytes)
        }
    }

    // ---------- Notifications ----------

    private fun onNotification(source: UUID, data: ByteArray) {
        lastRxAt = System.currentTimeMillis()
        if (source != PinConfig.NOTIFY_CHAR_UUID) {
            PinLog.rx("[$source] ${data.toHex()}", true)
            return
        }
        when (val n = PlaudProtocol.parse(data)) {
            is PinNotification.Audio -> {
                val rec = recorder ?: openRecorder(n.sessionId, "unsolicited").also { PinLog.w("Audio arrived with no transfer open!") }
                if (recorderSession != n.sessionId) PinLog.w("Audio for sid=${n.sessionId} while recording sid=$recorderSession")
                rec.append(n.position, n.audio)
                // Sampled: logging all ~25 packets/s floods logcat's buffer and the log file.
                if (rec.packets <= 3 || ++audioPacketsSinceUiLog % PinConfig.AUDIO_LOG_EVERY_N == 0) {
                    PinLog.rx("[audio sid=${n.sessionId} pos=${n.position} len=${n.audio.size}] ${data.toHex()}", toUi = true)
                }
            }
            is PinNotification.AudioEnd -> {
                // Always followed by cmd 29, which is what completes the transfer.
                PinLog.rx("[audio END sid=${n.sessionId}] ${data.toHex()}", true)
            }
            is PinNotification.CommandResponse -> {
                PinLog.rx("[resp cmd=${n.cmdId}] ${data.toHex()}", true)
                val waiter = pendingResponses.remove(n.cmdId)
                if (waiter != null) waiter.complete(n.payload) else onEvent(n.cmdId, n.payload)
            }
            is PinNotification.Unknown -> PinLog.rx("[unknown] ${data.toHex()}", true)
        }
    }

    /** Messages the pin sends on its own. */
    private fun onEvent(cmdId: Int, payload: ByteArray) {
        when (cmdId) {
            PlaudCommand.START_RECORD -> PlaudProtocol.decodeStartRecord(payload)?.let {
                PinLog.i("Pin button: recording started sid=${it.sessionId} (${stamp(it.sessionId)})")
                _pinRecording.value = true
                if (PinConfig.FOLLOW_BUTTON_RECORDINGS_LIVE) followLive(it.sessionId)
            }
            PlaudCommand.STOP_RECORD -> PlaudProtocol.decodeStopRecord(payload)?.let {
                PinLog.i("Pin button: recording stopped sid=${it.sessionId}, ${it.bytes} B (~${it.bytes / PinConfig.OPUS_BYTES_PER_SEC}s)")
                _pinRecording.value = false
                onRecordingStopped(it)
            }
            PlaudCommand.SYNC_DONE -> {
                PinLog.i("Pin: transfer done sid=${PlaudProtocol.decodeSyncDone(payload)}")
                finishTransfer()
            }
            else -> PinLog.w("Unsolicited cmd=$cmdId [${payload.toHex()}]")
        }
    }

    private fun finishTransfer() {
        transferDone?.complete(Unit)
        // A sync closes its own recorder after cmd 30; a live follow has nobody waiting, so close here.
        if (_recording.value?.contains("_live") == true) closeRecorder()
    }

    // ---------- Recording files ----------

    private fun nowEpoch() = System.currentTimeMillis() / 1000
    private fun stamp(sessionId: Long): String = stampFmt.format(Date(sessionId * 1000))

    private fun captureFile(sessionId: Long, suffix: String) =
        File(recordingsDir, "${stamp(sessionId)}$suffix${PinConfig.RAW_PACKET_EXT}")

    /** Fresh capture file (replaces any earlier copy). */
    private fun openRecorder(sessionId: Long, kind: String): OpusPacketRecorder =
        openRecorder(sessionId, captureFile(sessionId, "_$kind").apply { delete() })

    @Synchronized
    private fun openRecorder(sessionId: Long, f: File, resumeFrom: Long = 0): OpusPacketRecorder {
        recorder?.close()
        return OpusPacketRecorder(f, resumeFrom).also {
            recorder = it
            recorderSession = sessionId
            audioPacketsSinceUiLog = 0
            _recording.value = f.name
            PinLog.i("Recording to ${f.absolutePath}")
        }
    }

    @Synchronized
    private fun closeRecorder() {
        val r = recorder ?: return
        recorder = null
        recorderSession = null
        r.close()
        _recording.value = null
        if (r.packets > 0) _lastCapture.value = r.file
        else if (!r.file.name.contains(PinConfig.PARTIAL_SUFFIX)) r.file.delete() // keep partials to resume
    }

    /**
     * Decodes the newest *completed* capture to WAV next to it. Refused while the pin is recording
     * or a transfer is open: the playback would be an older memo, and the pin would record it.
     */
    suspend fun decodeLast(): OpusDecoder.Result? =
        _lastCapture.value?.let { decode(it) } ?: null.also { PinLog.w("Nothing captured yet") }

    /** Completed captures on the phone, newest first (for Settings > Developer). */
    fun recordingsOnPhone(): List<File> = recordingsDir.listFiles { f -> f.name.endsWith(PinConfig.SYNC_SUFFIX + PinConfig.RAW_PACKET_EXT) }
        .orEmpty().sortedByDescending { it.name }

    /** Decodes [src] to a WAV next to it; refused while busy (see [decodeLast]). */
    suspend fun decode(src: File): OpusDecoder.Result? {
        if (_pinRecording.value || _recording.value != null) return null.also { PinLog.w("Not playing back while recording/transferring") }
        val wav = File(src.parentFile, src.name.removeSuffix(PinConfig.RAW_PACKET_EXT) + PinConfig.WAV_EXT)
        return runCatching { OpusDecoder.decodeToWav(src, wav) }
            .onFailure { PinLog.e("Decode failed", it) }.getOrNull()
    }
}

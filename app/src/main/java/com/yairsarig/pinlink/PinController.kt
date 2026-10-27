package com.yairsarig.pinlink

import android.content.Context
import com.yairsarig.pinlink.audio.OpusDecoder
import com.yairsarig.pinlink.audio.OpusPacketRecorder
import com.yairsarig.pinlink.ble.LinkState
import com.yairsarig.pinlink.ble.PinGattClient
import com.yairsarig.pinlink.ble.PinScanner
import com.yairsarig.pinlink.protocol.PinNotification
import com.yairsarig.pinlink.protocol.PlaudCommand
import com.yairsarig.pinlink.protocol.PlaudProtocol
import com.yairsarig.pinlink.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 1 brain: owns the link, routes notifications, runs the live-session recipe, records audio.
 * Process-wide singleton (see [PinLinkApp]); the foreground service just keeps the process alive.
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

    private val prefs = context.getSharedPreferences(PinConfig.PREFS_NAME, Context.MODE_PRIVATE)
    private val recordingsDir = File(context.getExternalFilesDir(null), PinConfig.DIR_RECORDINGS).apply { mkdirs() }
    private val pendingResponses = ConcurrentHashMap<Int, CompletableDeferred<ByteArray>>()
    private val stampFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    @Volatile private var recorder: OpusPacketRecorder? = null
    @Volatile private var liveSessionId: Long? = null
    @Volatile private var userWantsLink = false
    private var reconnectJob: Job? = null
    private var audioPacketsSinceUiLog = 0

    init {
        _lastCapture.value = recordingsDir.listFiles { f -> f.name.endsWith(PinConfig.RAW_PACKET_EXT) }
            ?.maxByOrNull { it.lastModified() }
    }

    // ---------- Link ----------

    fun connect() = scope.launch {
        userWantsLink = true
        reconnectJob?.cancel()
        runCatching { connectOnce() }.onFailure { PinLog.e("Connect failed: ${it.message}") }
    }

    fun disconnect() = scope.launch {
        userWantsLink = false
        reconnectJob?.cancel()
        if (liveSessionId != null) stopLiveSession().join()
        client.close()
        closeRecorder()
    }

    private suspend fun connectOnce() {
        val device = prefs.getString(PinConfig.PREF_LAST_ADDRESS, null)?.let { scanner.deviceFor(it) }
            ?: scanner.find() ?: error("pin not found")
        client.connect(device)
        prefs.edit().putString(PinConfig.PREF_LAST_ADDRESS, device.address).apply()
        readBattery()
    }

    private fun onUnexpectedDisconnect() {
        PinLog.w("Link dropped")
        liveSessionId = null
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

    /** Sends a command and waits for the response with the same cmdId (null on timeout). */
    suspend fun command(cmdId: Int, payload: ByteArray = ByteArray(0), awaitResponse: Boolean = true): ByteArray? {
        val frame = PlaudProtocol.encodeCommand(cmdId, payload)
        val waiter = if (awaitResponse) CompletableDeferred<ByteArray>().also { pendingResponses[cmdId] = it } else null
        PinLog.i("TX cmd=$cmdId ${frame.toHex()}")
        client.write(frame)
        if (waiter == null) return null
        return withTimeoutOrNull(PinConfig.COMMAND_RESPONSE_TIMEOUT_MS) { waiter.await() }
            .also { if (it == null) PinLog.w("cmd=$cmdId: no response") }
            .also { pendingResponses.remove(cmdId, waiter) }
    }

    fun readBattery() = scope.launch {
        val r = runCatching { command(PlaudCommand.GET_BATTERY) }.getOrNull() ?: return@launch
        _battery.value = PlaudProtocol.decodeBattery(r)
        PinLog.i("Battery: ${_battery.value}")
    }

    /** Raw console for probing unknown commands. */
    fun sendRaw(cmdId: Int, payload: ByteArray) = scope.launch {
        runCatching { command(cmdId, payload) }
            .onSuccess { r -> PinLog.i("cmd=$cmdId response: ${r?.toHex() ?: "(none)"}") }
            .onFailure { PinLog.e("cmd=$cmdId failed", it) }
    }

    // ---------- Live session (Omi recipe) ----------

    fun startLiveSession() = scope.launch {
        repeat(PinConfig.SESSION_SETUP_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(attempt * 1_000L)
            try {
                command(PlaudCommand.STOP_RECORD, PlaudProtocol.stopRecordPayload(0))
                delay(PinConfig.STOP_TO_START_DELAY_MS)
                val started = command(PlaudCommand.START_RECORD, PlaudProtocol.startRecordPayload())
                    ?.let(PlaudProtocol::decodeStartRecord) ?: return@repeat
                PinLog.i("Recording started on pin: $started")
                liveSessionId = started.sessionId
                openRecorder("live")
                delay(PinConfig.START_TO_SYNC_DELAY_MS)
                if (command(PlaudCommand.SYNC_FILE_START, PlaudProtocol.syncFileStartPayload(started.sessionId, started.startTime)) != null) {
                    PinLog.i("Live session streaming (session=${started.sessionId})")
                    return@launch
                }
            } catch (t: Throwable) {
                PinLog.e("Live session attempt ${attempt + 1} failed", t)
            }
        }
        PinLog.e("Live session setup failed after ${PinConfig.SESSION_SETUP_ATTEMPTS} attempts")
    }

    fun stopLiveSession() = scope.launch {
        val id = liveSessionId
        runCatching {
            command(PlaudCommand.STOP_SYNC, PlaudProtocol.STOP_SYNC_PAYLOAD, awaitResponse = false)
            if (id != null) command(PlaudCommand.STOP_RECORD, PlaudProtocol.stopRecordPayload(id))
        }.onFailure { PinLog.e("Stop failed", it) }
        liveSessionId = null
        closeRecorder()
    }

    // ---------- Notifications ----------

    private fun onNotification(data: ByteArray) {
        when (val n = PlaudProtocol.parse(data)) {
            is PinNotification.Audio -> {
                // Pin-initiated audio arrives with no session open: capture it anyway (open question #1).
                val rec = recorder ?: openRecorder("unsolicited").also { PinLog.w("Audio arrived with no live session!") }
                rec.append(n.position, n.audio)
                val toUi = rec.packets <= 3 || ++audioPacketsSinceUiLog % 200 == 0
                PinLog.rx("[audio hdr=0x%08x pos=%d len=%d] %s".format(n.header, n.position, n.audio.size, data.toHex()), toUi)
            }
            is PinNotification.AudioEnd -> {
                PinLog.rx("[audio END hdr=0x%08x] %s".format(n.header, data.toHex()), true)
                closeRecorder()
            }
            is PinNotification.CommandResponse -> {
                PinLog.rx("[resp cmd=${n.cmdId}] ${data.toHex()}", true)
                pendingResponses.remove(n.cmdId)?.complete(n.payload)
                    ?: PinLog.w("Unsolicited response for cmd=${n.cmdId}")
            }
            is PinNotification.Unknown -> PinLog.rx("[unknown] ${data.toHex()}", true)
        }
    }

    // ---------- Recording files ----------

    @Synchronized
    private fun openRecorder(kind: String): OpusPacketRecorder {
        recorder?.close()
        val f = File(recordingsDir, "${stampFmt.format(Date())}_$kind${PinConfig.RAW_PACKET_EXT}")
        return OpusPacketRecorder(f).also {
            recorder = it
            audioPacketsSinceUiLog = 0
            _recording.value = f.name
            PinLog.i("Recording to ${f.absolutePath}")
        }
    }

    @Synchronized
    private fun closeRecorder() {
        val r = recorder ?: return
        recorder = null
        r.close()
        _recording.value = null
        if (r.packets > 0) _lastCapture.value = r.file else r.file.delete()
    }

    /** Decodes the newest capture to WAV next to it. */
    suspend fun decodeLast(): OpusDecoder.Result? {
        val src = _lastCapture.value ?: return null.also { PinLog.w("Nothing captured yet") }
        val wav = File(src.parentFile, src.name.removeSuffix(PinConfig.RAW_PACKET_EXT) + PinConfig.WAV_EXT)
        return runCatching { OpusDecoder.decodeToWav(src, wav) }
            .onFailure { PinLog.e("Decode failed", it) }.getOrNull()
    }
}

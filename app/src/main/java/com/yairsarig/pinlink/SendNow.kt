package com.yairsarig.pinlink

import android.content.Context
import com.yairsarig.pinlink.claude.ClaudeTrigger
import com.yairsarig.pinlink.claude.FireResult
import com.yairsarig.pinlink.claude.FireWorker
import com.yairsarig.pinlink.upload.DriveAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The pin button: connect if needed -> pull everything on the pin -> wait for transcription and
 * upload -> fire the Claude routine. One run at a time; each run ends with one snackbar message.
 */
class SendNow(private val context: Context, private val controller: PinController) {

    enum class Phase(val line: String) {
        CONNECTING("Connecting to pin…"),
        PULLING("Pulling memo from pin…"),
        TRANSCRIBING("Transcribing…"),
        UPLOADING("Uploading…"),
        SENDING("Sending to Claude…"),
    }

    enum class PipelineEnd { DONE, DRIVE, TIMEOUT }

    private val _phase = MutableStateFlow<Phase?>(null)
    val phase: StateFlow<Phase?> = _phase
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
    private val running = AtomicBoolean(false)

    /** False (and nothing happens) while a run is already going. */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        controller.scope.launch {
            val msg = try {
                run()
            } catch (t: Throwable) {
                PinLog.e("Send now failed", t)
                "Something went wrong — see Settings › Log"
            } finally {
                _phase.value = null
                running.set(false)
            }
            PinLog.i("Send now: $msg")
            _messages.emit(msg)
        }
        return true
    }

    private suspend fun run(): String {
        PinLog.i("Send now")
        _phase.value = Phase.CONNECTING
        val connected = controller.ensureConnected()
        var pulled = 0
        if (connected) {
            _phase.value = Phase.PULLING
            pulled = runCatching { controller.syncAll().await() }.getOrDefault(0)
        }
        val end = awaitPipeline()
        if (end != PipelineEnd.DONE) return message(controller.bluetoothOn.value, connected, pulled, end, null)
        _phase.value = Phase.SENDING
        val fired = ClaudeTrigger.firePending()
        if (fired?.result is FireResult.Failed && ClaudeTrigger.state.value.auto) FireWorker.schedule(context)
        return message(controller.bluetoothOn.value, connected, pulled, end, fired)
    }

    /** Waits until every capture on the phone is transcribed and uploaded (or can't be, for now). */
    private suspend fun awaitPipeline(): PipelineEnd {
        Pipeline.resumePending(context) // also pulls uploads out of retry backoff
        val deadline = System.currentTimeMillis() + PinConfig.SEND_PIPELINE_TIMEOUT_MS
        while (true) {
            val s = withContext(Dispatchers.IO) { Pipeline.status(context) }
            if (s.pending == 0) return PipelineEnd.DONE
            if (s.toTranscribe == 0 && DriveAuth.connected.value == false) return PipelineEnd.DRIVE
            if (System.currentTimeMillis() > deadline) return PipelineEnd.TIMEOUT
            _phase.value = if (s.toTranscribe > 0) Phase.TRANSCRIBING else Phase.UPLOADING
            delay(PinConfig.SEND_POLL_MS)
        }
    }

    companion object {
        fun memos(n: Int) = if (n == 1) "1 memo" else "$n memos"

        /** Pure: how a run ended, in plain words. [fired] null = nothing was waiting to send. */
        fun message(bluetoothOn: Boolean, connected: Boolean, pulled: Int, end: PipelineEnd, fired: ClaudeTrigger.Fired?): String {
            val pin = when {
                !bluetoothOn -> "Bluetooth is off"
                !connected -> "Pin not in range"
                else -> null
            }
            when (end) {
                PipelineEnd.DRIVE -> return "Drive needs reconnecting"
                PipelineEnd.TIMEOUT -> return "Still working — it will finish in the background"
                PipelineEnd.DONE -> Unit
            }
            if (fired == null) return pin ?: if (pulled == 0) "Nothing new on the pin" else "Pulled ${memos(pulled)}, nothing to send"
            val sent = when (fired.result) {
                is FireResult.Ok -> if (pulled > 0) "Pulled ${memos(pulled)}, sent to Claude" else "Sent ${memos(fired.memos)} to Claude"
                is FireResult.RateLimited, FireResult.Capped -> "Sent to Claude; it will run at the next hourly check"
                FireResult.NotConfigured -> "Uploaded. Add your Claude routine in Settings to send now"
                is FireResult.Rejected -> "Claude rejected the routine — check Settings"
                is FireResult.Failed -> "Couldn't reach Claude; the hourly check will pick it up"
            }
            return if (pin != null) "$pin · $sent" else sent
        }
    }
}

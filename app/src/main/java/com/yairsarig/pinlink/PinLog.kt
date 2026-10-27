package com.yairsarig.pinlink

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logs to logcat (tag PINLINK), to a daily file under logs/, and to an in-memory ring for the UI.
 * The daily file is the record of every raw notification — the evidence for Phase 1's open question.
 */
object PinLog {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private var logDir: File? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(dir: File) {
        logDir = dir.apply { mkdirs() }
    }

    fun i(msg: String) = write("I", msg) { Log.i(PinConfig.LOG_TAG, msg) }
    fun w(msg: String, t: Throwable? = null) = write("W", msg + (t?.let { " ($it)" } ?: "")) { Log.w(PinConfig.LOG_TAG, msg, t) }
    fun e(msg: String, t: Throwable? = null) = write("E", msg + (t?.let { " ($it)" } ?: "")) { Log.e(PinConfig.LOG_TAG, msg, t) }

    /** Raw notification hex: logcat + file always; UI only if [toUi] (audio floods the screen). */
    fun rx(hex: String, toUi: Boolean) {
        val line = "${timeFmt.format(Date())} RX $hex"
        Log.d(PinConfig.LOG_TAG, "RX $hex")
        appendToFile(line)
        if (toUi) pushUi(line)
    }

    private inline fun write(level: String, msg: String, logcat: () -> Unit) {
        logcat()
        val line = "${timeFmt.format(Date())} $level $msg"
        appendToFile(line)
        pushUi(line)
    }

    private fun pushUi(line: String) = _lines.update { (it + line).takeLast(PinConfig.UI_LOG_LINES) }

    @Synchronized
    private fun appendToFile(line: String) {
        val dir = logDir ?: return
        runCatching { File(dir, "pinlink-${dayFmt.format(Date())}.log").appendText(line + "\n") }
    }
}

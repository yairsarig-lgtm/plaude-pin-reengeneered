package com.yairsarig.pinlink.transcribe

import android.content.Context
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Whisper model on disk. Downloaded once (only the model is fetched; nothing is uploaded) and
 * verified against a pinned SHA-256 before use.
 */
class ModelManager(context: Context) {
    val modelFile = File(File(context.filesDir, PinConfig.DIR_MODELS).apply { mkdirs() }, PinConfig.WHISPER_MODEL_FILE)
    private val verifiedMarker = File(modelFile.path + ".verified")

    val isReady: Boolean get() = modelFile.exists() && verifiedMarker.exists()

    /**
     * Blocking. Returns the verified model file, downloading it if needed. WorkManager runs
     * transcriptions in parallel, so one caller downloads while the others wait for it.
     */
    fun ensureModel(): File = synchronized(downloadLock) {
        if (isReady) return modelFile
        val tmp = File(modelFile.path + ".part")
        PinLog.i("Downloading ${PinConfig.WHISPER_MODEL_URL}")
        val conn = URL(PinConfig.WHISPER_MODEL_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            check(conn.responseCode == 200) { "model download HTTP ${conn.responseCode}" }
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var nextLog = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        if (done >= nextLog) {
                            PinLog.i("Model download ${done * 100 / PinConfig.WHISPER_MODEL_BYTES}%")
                            nextLog += PinConfig.WHISPER_MODEL_BYTES / 10
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            check(sha == PinConfig.WHISPER_MODEL_SHA256) { "model hash mismatch: $sha" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        } finally {
            conn.disconnect()
        }
        check(tmp.renameTo(modelFile)) { "could not move model into place" }
        verifiedMarker.writeText(PinConfig.WHISPER_MODEL_SHA256)
        PinLog.i("Model ready: ${modelFile.length()} bytes, sha256 OK")
        return modelFile
    }

    private companion object {
        val downloadLock = Any()
    }
}

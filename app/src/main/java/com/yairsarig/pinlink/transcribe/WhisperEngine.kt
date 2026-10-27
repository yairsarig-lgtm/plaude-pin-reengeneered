package com.yairsarig.pinlink.transcribe

import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import java.io.File

/** whisper.cpp via JNI. One model context per process, loaded lazily; calls are serialized. */
object WhisperEngine {
    init {
        System.loadLibrary("pinlink_whisper")
    }

    private var ctx = 0L

    @Synchronized
    fun transcribe(model: File, samples16k: FloatArray): String {
        if (ctx == 0L) {
            ctx = nativeInit(model.absolutePath)
            check(ctx != 0L) { "whisper failed to load ${model.name}" }
            PinLog.i("Whisper loaded ${model.name}; ${nativeSystemInfo()}")
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, PinConfig.WHISPER_MAX_THREADS)
        val started = System.currentTimeMillis()
        val bytes = nativeTranscribe(ctx, samples16k, threads, PinConfig.WHISPER_LANGUAGE)
            ?: error("whisper_full failed")
        PinLog.i("Transcribed %.1fs of audio in %.1fs (%d threads)".format(
            samples16k.size / 16_000.0, (System.currentTimeMillis() - started) / 1000.0, threads))
        return String(bytes, Charsets.UTF_8)
    }

    @JvmStatic private external fun nativeInit(modelPath: String): Long
    @JvmStatic private external fun nativeFree(ctx: Long)
    @JvmStatic private external fun nativeTranscribe(ctx: Long, samples: FloatArray, threads: Int, language: String): ByteArray?
    @JvmStatic private external fun nativeSystemInfo(): String
}

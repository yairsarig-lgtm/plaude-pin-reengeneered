package com.yairsarig.pinlink.transcribe

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Integer-factor downsampler for 16-bit PCM: windowed-sinc low-pass, then keep every Nth sample.
 * The platform Opus decoder emits 48 kHz; Whisper wants 16 kHz floats in [-1, 1].
 */
class Resampler(private val factor: Int, taps: Int = 63) {
    private val kernel: FloatArray

    init {
        require(factor >= 1 && taps % 2 == 1)
        val cutoff = 0.5 / factor * 0.9 // normalized to input rate; 10% guard band below new Nyquist
        val mid = taps / 2
        val raw = DoubleArray(taps) { i ->
            val n = (i - mid).toDouble()
            val sinc = if (n == 0.0) 2 * cutoff else sin(2 * PI * cutoff * n) / (PI * n)
            val blackman = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
            sinc * blackman
        }
        val sum = raw.sum()
        kernel = FloatArray(taps) { (raw[it] / sum).toFloat() } // unity DC gain
    }

    fun downsample(pcm16: ShortArray): FloatArray {
        val mid = kernel.size / 2
        val out = FloatArray(pcm16.size / factor)
        for (o in out.indices) {
            val center = o * factor
            var acc = 0f
            for (k in kernel.indices) {
                val idx = center + k - mid
                if (idx in pcm16.indices) acc += kernel[k] * pcm16[idx]
            }
            out[o] = (acc / 32768f).coerceIn(-1f, 1f)
        }
        return out
    }
}

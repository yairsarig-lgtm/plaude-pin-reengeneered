package com.yairsarig.pinlink.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class ResamplerTest {
    private val r = Resampler(3)

    private fun tone(hz: Double, seconds: Double = 1.0, rate: Int = 48_000, amp: Double = 16_000.0) =
        ShortArray((rate * seconds).toInt()) { (amp * sin(2 * PI * hz * it / rate)).toInt().toShort() }

    /** RMS of the middle, away from filter edge effects. */
    private fun rms(x: FloatArray) = x.copyOfRange(x.size / 4, x.size * 3 / 4).let { m -> sqrt(m.sumOf { it.toDouble() * it } / m.size) }

    @Test fun lengthDividesByFactor() {
        assertEquals(16_000, r.downsample(ShortArray(48_000)).size)
    }

    @Test fun passesSpeechBand() {
        val amp = 16_000.0 / 32768
        val out = rms(r.downsample(tone(1_000.0)))
        assertEquals(amp / sqrt(2.0), out, 0.02) // ~unity gain at 1 kHz
    }

    @Test fun rejectsAboveNewNyquist() {
        // 12 kHz would alias to 4 kHz after naive decimation; the filter must kill it.
        val out = rms(r.downsample(tone(12_000.0)))
        assertTrue("12 kHz leaked: rms=$out", out < 0.005)
    }

    @Test fun dcIsPreserved() {
        val out = r.downsample(ShortArray(4_800) { 8_192 })
        assertTrue(abs(out[out.size / 2] - 0.25f) < 1e-3)
    }
}

package com.yairsarig.pinlink.audio

import android.media.MediaCodec
import android.media.MediaFormat
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Turns an .opuspk capture into a WAV using the platform Opus decoder (MediaCodec). */
object OpusDecoder {

    data class Result(val wav: File, val frames: Int, val badFrames: Int, val sampleRate: Int, val seconds: Double)

    fun decodeToWav(packetFile: File, wavFile: File): Result {
        val packets = OpusPacketFile.read(packetFile)
        val stream = packets.fold(java.io.ByteArrayOutputStream()) { acc, p -> acc.apply { write(p.bytes) } }.toByteArray()
        analyze(packets, stream)
        val frames = FrameSplitter.fixed(stream, PinConfig.OPUS_FIXED_FRAME_BYTES)
        return decodeFrames(frames, wavFile)
    }

    /** Logs the evidence needed to confirm or reject fixed-size framing. */
    private fun analyze(packets: List<OpusPacketFile.Packet>, stream: ByteArray) {
        val lenHist = packets.groupingBy { it.bytes.size }.eachCount().toSortedMap()
        PinLog.i("Packets=${packets.size} streamBytes=${stream.size} packetLengths=$lenHist")
        val tocs = FrameSplitter.tocHistogram(stream, PinConfig.OPUS_FIXED_FRAME_BYTES)
        PinLog.i("TOC bytes at every ${PinConfig.OPUS_FIXED_FRAME_BYTES}-byte boundary: " +
            tocs.entries.joinToString { "0x%02x×%d".format(it.key, it.value) } +
            if (tocs.isNotEmpty()) " -> ${OpusToc.describe(tocs.maxBy { it.value }.key)}" else "")
    }

    private fun decodeFrames(frames: List<ByteArray>, wavFile: File): Result {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, PinConfig.OPUS_SAMPLE_RATE, PinConfig.OPUS_CHANNELS).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
            setByteBuffer("csd-1", nativeLong(0))              // pre-skip, ns
            setByteBuffer("csd-2", nativeLong(80_000_000))     // seek pre-roll, ns
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var next = 0
        var inputDone = false
        var badFrames = 0
        WavWriter(wavFile).use { wav ->
            wav.sampleRate = PinConfig.OPUS_SAMPLE_RATE
            wav.channels = PinConfig.OPUS_CHANNELS
            while (true) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        if (next < frames.size) {
                            val f = frames[next]
                            codec.getInputBuffer(idx)!!.apply { clear(); put(f) }
                            codec.queueInputBuffer(idx, 0, f.size, next * PinConfig.OPUS_FRAME_DURATION_US, 0)
                            next++
                        } else {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        wav.sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        wav.channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        PinLog.i("Decoder output format: $f")
                    }
                    out >= 0 -> {
                        if (info.size > 0) {
                            val pcm = ByteArray(info.size)
                            codec.getOutputBuffer(out)!!.apply { position(info.offset); get(pcm) }
                            wav.write(pcm)
                        } else if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                            badFrames++
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            codec.stop()
            codec.release()
            val secs = wav.dataBytes.toDouble() / (wav.sampleRate * wav.channels * 2).coerceAtLeast(1)
            PinLog.i("Decoded ${frames.size} frames -> ${wavFile.name}: %.1fs @ %d Hz".format(secs, wav.sampleRate))
            return Result(wavFile, frames.size, badFrames, wav.sampleRate, secs)
        }
    }

    private fun opusHead(): ByteArray = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("OpusHead".toByteArray()); put(1); put(PinConfig.OPUS_CHANNELS.toByte())
        putShort(0); putInt(PinConfig.OPUS_SAMPLE_RATE); putShort(0); put(0)
    }.array()

    private fun nativeLong(v: Long): ByteBuffer =
        ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(v).apply { flip() }
}

/** Framing strategies, pure JVM so they're unit-testable. */
object FrameSplitter {
    fun fixed(stream: ByteArray, size: Int): List<ByteArray> =
        (0 until stream.size / size).map { stream.copyOfRange(it * size, (it + 1) * size) }

    fun tocHistogram(stream: ByteArray, size: Int): Map<Int, Int> =
        (0 until stream.size / size).groupingBy { stream[it * size].toInt() and 0xFF }.eachCount()
}

/** Decodes an Opus TOC byte (RFC 6716 §3.1). */
object OpusToc {
    private val bandwidths = listOf("NB", "MB", "WB", "SWB", "FB")

    fun describe(toc: Int): String {
        val config = toc shr 3
        val stereo = toc and 0x04 != 0
        val code = toc and 0x03
        val (mode, bw, ms) = when (config) {
            in 0..11 -> Triple("SILK", bandwidths[config / 4], listOf(10, 20, 40, 60)[config % 4])
            in 12..15 -> Triple("Hybrid", if (config < 14) "SWB" else "FB", listOf(10, 20)[config % 2])
            else -> Triple("CELT", listOf("NB", "WB", "SWB", "FB")[(config - 16) / 4], listOf(2.5, 5, 10, 20)[config % 4])
        }
        return "config=$config $mode $bw ${ms}ms ${if (stereo) "stereo" else "mono"} code=$code"
    }
}

package com.yairsarig.pinlink.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit PCM WAV. */
object Wav {
    const val HEADER_BYTES = 44

    fun header(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
            putInt(sampleRate); putInt(byteRate); putShort((channels * 2).toShort()); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }.array()
    }
}

/** Streams PCM to disk and patches the header sizes on close. */
class WavWriter(private val file: File) : AutoCloseable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(Wav.HEADER_BYTES)) }
    var dataBytes = 0
        private set
    var sampleRate = 0
    var channels = 1

    fun write(pcm: ByteArray, off: Int = 0, len: Int = pcm.size) {
        raf.write(pcm, off, len)
        dataBytes += len
    }

    override fun close() {
        raf.seek(0)
        raf.write(Wav.header(sampleRate, channels, dataBytes))
        raf.close()
    }
}

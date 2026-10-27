package com.yairsarig.pinlink.audio

import com.yairsarig.pinlink.PinLog
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream

/**
 * Raw audio exactly as it came off the pin, one record per BLE audio packet:
 * `[u32 LE position][u16 LE length][bytes]`. Keeping packet boundaries lets us work out the real
 * Opus framing offline instead of trusting Omi's 80-byte re-chunking.
 */
object OpusPacketFile {
    data class Packet(val position: Long, val bytes: ByteArray)

    private const val RECORD_HEADER_BYTES = 6

    /**
     * Truncates [file] to its audio that is contiguous from position 0 and returns where that audio
     * ends, i.e. where an interrupted transfer should resume. 0 if the file is absent.
     */
    fun trimToContiguous(file: File): Long {
        if (!file.exists()) return 0
        var next = 0L
        var fileBytes = 0L
        for (p in read(file)) {
            if (p.position != next) break
            next += p.bytes.size
            fileBytes += RECORD_HEADER_BYTES + p.bytes.size
        }
        if (file.length() != fileBytes) java.io.RandomAccessFile(file, "rw").use { it.setLength(fileBytes) }
        return next
    }

    fun read(file: File): List<Packet> {
        val out = ArrayList<Packet>()
        DataInputStream(file.inputStream().buffered()).use { s ->
            while (true) {
                val b = ByteArray(RECORD_HEADER_BYTES)
                try { s.readFully(b) } catch (_: EOFException) { break }
                val pos = (0 until 4).fold(0L) { a, i -> a or ((b[i].toLong() and 0xFF) shl (8 * i)) }
                val len = (b[4].toInt() and 0xFF) or ((b[5].toInt() and 0xFF) shl 8)
                val data = ByteArray(len)
                try { s.readFully(data) } catch (_: EOFException) { break } // torn final record
                out += Packet(pos, data)
            }
        }
        return out
    }
}

/**
 * Appends packets to an .opuspk file and tracks stream continuity. [resumeFrom] > 0 continues an
 * existing file whose audio ends at that byte position; [bytes] counts only what this instance adds.
 */
class OpusPacketRecorder(val file: File, resumeFrom: Long = 0) : AutoCloseable {
    private val out = BufferedOutputStream(FileOutputStream(file, true))
    var packets = 0
        private set
    var bytes = 0L
        private set
    var gaps = 0
        private set
    private var expectedPosition: Long? = resumeFrom.takeIf { it > 0 }

    fun append(position: Long, audio: ByteArray) {
        expectedPosition?.let { exp ->
            if (exp != position) {
                gaps++
                PinLog.w("Audio position jump: expected $exp got $position (Δ${position - exp})")
            }
        }
        expectedPosition = position + audio.size
        val hdr = ByteArray(6)
        for (i in 0 until 4) hdr[i] = ((position shr (8 * i)) and 0xFF).toByte()
        hdr[4] = (audio.size and 0xFF).toByte()
        hdr[5] = ((audio.size shr 8) and 0xFF).toByte()
        out.write(hdr)
        out.write(audio)
        packets++
        bytes += audio.size
        if (packets % 100 == 0) out.flush()
    }

    override fun close() {
        out.close()
        PinLog.i("Recording closed: ${file.name} packets=$packets bytes=$bytes positionGaps=$gaps")
    }
}

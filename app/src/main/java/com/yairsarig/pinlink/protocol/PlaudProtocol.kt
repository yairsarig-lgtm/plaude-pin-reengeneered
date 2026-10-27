/*
 * Plaud NotePin BLE framing, ported to Kotlin from the Omi app:
 *   https://github.com/BasedHardware/omi  app/lib/services/devices/connectors/plaud_connection.dart
 *   (commit ae20a36dff00ccbcec90a3fdc71165505022f15d, added in PR #3287)
 *
 * MIT License — Copyright (c) Based Hardware Contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.yairsarig.pinlink.protocol

/** Known command IDs. See docs/PROTOCOL.md for what each was observed to do. */
object PlaudCommand {
    const val GET_STATE = 3
    const val GET_BATTERY = 9
    const val START_RECORD = 20   // also sent unsolicited by the pin when its button starts a recording
    const val STOP_RECORD = 23    // also sent unsolicited on button stop, with the file size
    const val LIST_FILES = 26
    const val SYNC_FILE_START = 28
    const val SYNC_DONE = 29      // pin -> phone only
    const val STOP_SYNC = 30
    const val DEVICE_NAME = 108

    /** Commands whose response arrives under a different cmdId. */
    private val responseIds = mapOf(24 to 33, 25 to 34, STOP_SYNC to 31, 112 to 113, 116 to 117)

    fun responseIdFor(cmdId: Int): Int = responseIds[cmdId] ?: cmdId
}

/** A parsed notification from the pin's notify characteristic. */
sealed class PinNotification {
    abstract val raw: ByteArray

    /**
     * data[0] == 2. [sessionId] is the recording this audio belongs to (Omi left it unexplained).
     * [position] is a uint32 byte offset into the recording.
     */
    data class Audio(
        override val raw: ByteArray,
        val sessionId: Long,
        val position: Long,
        val audio: ByteArray,
    ) : PinNotification()

    /** Audio packet with position 0xFFFFFFFF: the pin has nothing more to send. */
    data class AudioEnd(override val raw: ByteArray, val sessionId: Long) : PinNotification()

    data class CommandResponse(
        override val raw: ByteArray,
        val cmdId: Int,
        val payload: ByteArray,
    ) : PinNotification()

    /** Too short, or an audio packet whose declared length overruns the buffer. */
    data class Unknown(override val raw: ByteArray) : PinNotification()
}

object PlaudProtocol {
    private const val FRAME_COMMAND: Byte = 0x01
    private const val FRAME_AUDIO = 0x02
    private const val AUDIO_HEADER_LEN = 9
    const val END_MARKER = 0xFFFFFFFFL

    fun encodeCommand(cmdId: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        byteArrayOf(FRAME_COMMAND, (cmdId and 0xFF).toByte(), ((cmdId shr 8) and 0xFF).toByte()) + payload

    fun parse(data: ByteArray): PinNotification {
        if (data.isEmpty()) return PinNotification.Unknown(data)
        if (data[0].toInt() and 0xFF == FRAME_AUDIO) {
            val p = data.copyOfRange(1, data.size)
            if (p.size < AUDIO_HEADER_LEN) return PinNotification.Unknown(data)
            val sessionId = Le.u32(p, 0)
            val position = Le.u32(p, 4)
            if (position == END_MARKER) return PinNotification.AudioEnd(data, sessionId)
            val len = p[8].toInt() and 0xFF
            if (AUDIO_HEADER_LEN + len > p.size) return PinNotification.Unknown(data)
            return PinNotification.Audio(data, sessionId, position, p.copyOfRange(AUDIO_HEADER_LEN, AUDIO_HEADER_LEN + len))
        }
        if (data.size >= 3) {
            val cmdId = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            return PinNotification.CommandResponse(data, cmdId, data.copyOfRange(3, data.size))
        }
        return PinNotification.Unknown(data)
    }

    // --- Payload builders ---

    fun startRecordPayload(): ByteArray = Le.bytes32(1) + Le.bytes32(0) + Le.bytes32(0)

    fun stopRecordPayload(sessionId: Long): ByteArray = Le.bytes32(sessionId) + Le.bytes32(0)

    /**
     * Three u32s, as the official app sends them (Omi uses three u64s, which this firmware's app
     * never does). [end] = 0 follows a recording that is still running.
     */
    fun syncFileStartPayload(sessionId: Long, start: Long, end: Long): ByteArray =
        Le.bytes32(sessionId) + Le.bytes32(start) + Le.bytes32(end)

    fun stopSyncPayload(sessionId: Long): ByteArray = Le.bytes32(sessionId)

    fun listFilesPayload(nowEpochSeconds: Long): ByteArray = Le.bytes32(nowEpochSeconds) + ByteArray(5)

    // --- Response decoders ---

    data class Battery(val charging: Boolean, val percent: Int)

    fun decodeBattery(payload: ByteArray): Battery? =
        if (payload.size >= 2) Battery(payload[0].toInt() != 0, payload[1].toInt() and 0xFF) else null

    data class RecordStarted(val sessionId: Long, val startTime: Long)

    fun decodeStartRecord(payload: ByteArray): RecordStarted? =
        if (payload.size >= 10) RecordStarted(Le.u32(payload, 0), Le.u32(payload, 4)) else null

    /** A stored recording. [sessionId] is also its start time in Unix seconds. */
    data class StoredFile(val sessionId: Long, val bytes: Long, val flags: Int)

    fun decodeFileList(payload: ByteArray): List<StoredFile>? {
        if (payload.size < 8) return null
        val count = Le.u32(payload, 4).toInt()
        val entries = (0 until count).map { 8 + it * FILE_ENTRY_BYTES }.takeWhile { it + FILE_ENTRY_BYTES <= payload.size }
        return entries.map { off ->
            StoredFile(Le.u32(payload, off), Le.u32(payload, off + 4),
                (payload[off + 8].toInt() and 0xFF) or ((payload[off + 9].toInt() and 0xFF) shl 8))
        }
    }

    private const val FILE_ENTRY_BYTES = 10

    data class RecordStopped(val sessionId: Long, val bytes: Long)

    /** cmd 23 payload: sessionId u32, two flag bytes, file size u32. */
    fun decodeStopRecord(payload: ByteArray): RecordStopped? =
        if (payload.size >= 10) RecordStopped(Le.u32(payload, 0), Le.u32(payload, 6)) else null

    /** cmd 29 payload: sessionId u32, ff ff. */
    fun decodeSyncDone(payload: ByteArray): Long? = if (payload.size >= 4) Le.u32(payload, 0) else null
}

/** Little-endian helpers. */
object Le {
    fun bytes32(v: Long): ByteArray = ByteArray(4) { i -> ((v shr (8 * i)) and 0xFF).toByte() }
    fun bytes32(v: Int): ByteArray = bytes32(v.toLong())
    fun bytes64(v: Long): ByteArray = ByteArray(8) { i -> ((v shr (8 * i)) and 0xFF).toByte() }
    fun u32(b: ByteArray, off: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[off + i].toLong() and 0xFF) shl (8 * i)) }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() && it != ',' }.removePrefix("0x")
    require(clean.length % 2 == 0) { "odd number of hex digits" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

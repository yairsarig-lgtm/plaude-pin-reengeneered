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

/** Known command IDs. Everything else is probed via the raw command console. */
object PlaudCommand {
    const val GET_BATTERY = 9
    const val START_RECORD = 20
    const val STOP_RECORD = 23
    const val SYNC_FILE_START = 28
    const val STOP_SYNC = 30
}

/** A parsed notification from the pin's notify characteristic. */
sealed class PinNotification {
    abstract val raw: ByteArray

    /**
     * data[0] == 2. [header] is payload bytes 0..3 (meaning unknown; Omi ignores it — logged so we
     * can figure it out). [position] is a uint32 byte offset into the recording.
     */
    data class Audio(
        override val raw: ByteArray,
        val header: Long,
        val position: Long,
        val audio: ByteArray,
    ) : PinNotification()

    /** Audio packet with position 0xFFFFFFFF: the pin has nothing more to send. */
    data class AudioEnd(override val raw: ByteArray, val header: Long) : PinNotification()

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
            val header = Le.u32(p, 0)
            val position = Le.u32(p, 4)
            if (position == END_MARKER) return PinNotification.AudioEnd(data, header)
            val len = p[8].toInt() and 0xFF
            if (AUDIO_HEADER_LEN + len > p.size) return PinNotification.Unknown(data)
            return PinNotification.Audio(data, header, position, p.copyOfRange(AUDIO_HEADER_LEN, AUDIO_HEADER_LEN + len))
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

    fun syncFileStartPayload(sessionId: Long, start: Long, end: Long = 0x7FFFFFFFL): ByteArray =
        Le.bytes64(sessionId) + Le.bytes64(start) + Le.bytes64(end)

    val STOP_SYNC_PAYLOAD = byteArrayOf(0x01)

    // --- Response decoders ---

    data class Battery(val charging: Boolean, val percent: Int)

    fun decodeBattery(payload: ByteArray): Battery? =
        if (payload.size >= 2) Battery(payload[0].toInt() != 0, payload[1].toInt() and 0xFF) else null

    data class RecordStarted(val sessionId: Long, val startTime: Long)

    fun decodeStartRecord(payload: ByteArray): RecordStarted? =
        if (payload.size >= 10) RecordStarted(Le.u32(payload, 0), Le.u32(payload, 4)) else null
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

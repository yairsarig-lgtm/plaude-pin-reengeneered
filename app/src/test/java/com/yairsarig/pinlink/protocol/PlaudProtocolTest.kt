package com.yairsarig.pinlink.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaudProtocolTest {

    @Test fun encodesCommandFrameLittleEndian() {
        assertArrayEquals(byteArrayOf(1, 9, 0), PlaudProtocol.encodeCommand(PlaudCommand.GET_BATTERY))
        assertArrayEquals(byteArrayOf(1, 0x2C, 0x01, 7), PlaudProtocol.encodeCommand(300, byteArrayOf(7)))
    }

    @Test fun startRecordPayloadIsThreeU32() {
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), PlaudProtocol.startRecordPayload())
    }

    @Test fun syncPayloadIsThreeU64() {
        val p = PlaudProtocol.syncFileStartPayload(0x01020304, 0x0A0B0C0D)
        assertEquals(24, p.size)
        assertArrayEquals("04 03 02 01 00 00 00 00 0d 0c 0b 0a 00 00 00 00 ff ff ff 7f 00 00 00 00".hexToBytes(), p)
    }

    @Test fun parsesAudioPacket() {
        // 02 | header(4) | position=0x10 | len=3 | audio | trailing junk ignored
        val raw = "02 aa bb cc dd 10 00 00 00 03 de ad be ff".hexToBytes()
        val n = PlaudProtocol.parse(raw) as PinNotification.Audio
        assertEquals(0xddccbbaaL, n.header)
        assertEquals(16L, n.position)
        assertArrayEquals("de ad be".hexToBytes(), n.audio)
    }

    @Test fun parsesEndMarker() {
        val raw = "02 01 00 00 00 ff ff ff ff 00".hexToBytes()
        assertTrue(PlaudProtocol.parse(raw) is PinNotification.AudioEnd)
    }

    @Test fun overrunningAudioIsUnknown() {
        val raw = "02 00 00 00 00 00 00 00 00 05 01 02".hexToBytes()
        assertTrue(PlaudProtocol.parse(raw) is PinNotification.Unknown)
    }

    @Test fun parsesCommandResponseAndBattery() {
        val n = PlaudProtocol.parse("01 09 00 01 57".hexToBytes()) as PinNotification.CommandResponse
        assertEquals(9, n.cmdId)
        assertEquals(PlaudProtocol.Battery(true, 87), PlaudProtocol.decodeBattery(n.payload))
    }

    @Test fun decodesStartRecord() {
        val payload = "78 56 34 12 ef cd ab 90 00 00".hexToBytes()
        val r = PlaudProtocol.decodeStartRecord(payload)!!
        assertEquals(0x12345678L, r.sessionId)
        assertEquals(0x90abcdefL, r.startTime)
    }

    @Test fun shortPacketIsUnknown() {
        assertTrue(PlaudProtocol.parse(byteArrayOf(5)) is PinNotification.Unknown)
    }
}

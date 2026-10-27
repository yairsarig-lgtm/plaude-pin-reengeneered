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

    // Vectors below are real frames from the Plaud app capture (docs/PROTOCOL.md).

    @Test fun syncPayloadMatchesPlaudApp() {
        val frame = PlaudProtocol.encodeCommand(PlaudCommand.SYNC_FILE_START,
            PlaudProtocol.syncFileStartPayload(0x6ae07c3f, 0, 0x14a0))
        assertArrayEquals("01 1c 00 3f 7c e0 6a 00 00 00 00 a0 14 00 00".hexToBytes(), frame)
    }

    @Test fun stopSyncAndListMatchPlaudApp() {
        assertArrayEquals("01 1e 00 3f 7c e0 6a".hexToBytes(),
            PlaudProtocol.encodeCommand(PlaudCommand.STOP_SYNC, PlaudProtocol.stopSyncPayload(0x6ae07c3f)))
        assertArrayEquals("01 1a 00 47 7c e0 6a 00 00 00 00 00".hexToBytes(),
            PlaudProtocol.encodeCommand(PlaudCommand.LIST_FILES, PlaudProtocol.listFilesPayload(0x6ae07c47)))
    }

    @Test fun decodesFileList() {
        val payload = "47 7c e0 6a 01 00 00 00 3f 7c e0 6a a0 14 00 00 01 00".hexToBytes()
        assertEquals(listOf(PlaudProtocol.StoredFile(0x6ae07c3f, 5280, 1)), PlaudProtocol.decodeFileList(payload))
        assertEquals(emptyList<PlaudProtocol.StoredFile>(), PlaudProtocol.decodeFileList("b4 7a e0 6a 00 00 00 00".hexToBytes()))
    }

    @Test fun decodesButtonStopWithSize() {
        val r = PlaudProtocol.decodeStopRecord("5f 7d e0 6a 01 01 00 dc 00 00".hexToBytes())!!
        assertEquals(PlaudProtocol.RecordStopped(0x6ae07d5f, 56320), r)
    }

    @Test fun responseIdRemapping() {
        assertEquals(31, PlaudCommand.responseIdFor(PlaudCommand.STOP_SYNC))
        assertEquals(26, PlaudCommand.responseIdFor(PlaudCommand.LIST_FILES))
    }

    @Test fun audioHeaderIsSessionId() {
        val raw = "02 3f 7c e0 6a a0 00 00 00 02 b8 08".hexToBytes()
        val n = PlaudProtocol.parse(raw) as PinNotification.Audio
        assertEquals(0x6ae07c3fL, n.sessionId)
        assertEquals(160L, n.position)
    }

    @Test fun parsesAudioPacket() {
        // 02 | header(4) | position=0x10 | len=3 | audio | trailing junk ignored
        val raw = "02 aa bb cc dd 10 00 00 00 03 de ad be ff".hexToBytes()
        val n = PlaudProtocol.parse(raw) as PinNotification.Audio
        assertEquals(0xddccbbaaL, n.sessionId)
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

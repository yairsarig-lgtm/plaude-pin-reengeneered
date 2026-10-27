package com.yairsarig.pinlink.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioTest {

    @Test fun wavHeaderFields() {
        val h = ByteBuffer.wrap(Wav.header(16000, 1, 3200)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(Wav.header(16000, 1, 3200), 0, 4))
        assertEquals(36 + 3200, h.getInt(4))
        assertEquals(16000, h.getInt(24))
        assertEquals(32000, h.getInt(28))
        assertEquals(3200, h.getInt(40))
    }

    @Test fun packetFileRoundTrip() {
        val f = File.createTempFile("capture", ".opuspk").apply { deleteOnExit() }
        OpusPacketRecorder(f).use {
            it.append(0, byteArrayOf(1, 2, 3))
            it.append(3, byteArrayOf(4, 5))
            it.append(100, byteArrayOf(6))
            assertEquals(1, it.gaps)
        }
        val p = OpusPacketFile.read(f)
        assertEquals(listOf(0L, 3L, 100L), p.map { it.position })
        assertArrayEquals(byteArrayOf(4, 5), p[1].bytes)
    }

    @Test fun resumeTrimsToContiguousAndContinues() {
        val f = File.createTempFile("partial", ".opuspk").apply { deleteOnExit() }
        OpusPacketRecorder(f).use {
            it.append(0, ByteArray(160))
            it.append(160, ByteArray(160))
            it.append(480, ByteArray(160)) // gap: 320 missing
        }
        assertEquals(320L, OpusPacketFile.trimToContiguous(f))
        assertEquals(2, OpusPacketFile.read(f).size)
        OpusPacketRecorder(f, resumeFrom = 320).use {
            it.append(320, ByteArray(160))
            assertEquals(0, it.gaps)
            assertEquals(160L, it.bytes)
        }
        assertEquals(480L, OpusPacketFile.trimToContiguous(f))
    }

    @Test fun trimMissingFileIsZero() {
        assertEquals(0L, OpusPacketFile.trimToContiguous(File("does-not-exist.opuspk")))
    }

    @Test fun fixedSplitDropsTrailingPartialFrame() {
        val frames = FrameSplitter.fixed(ByteArray(170) { it.toByte() }, 80)
        assertEquals(2, frames.size)
        assertEquals(80.toByte(), frames[1][0])
    }

    @Test fun tocDescribesSilkWideband20ms() {
        assertEquals("config=9 SILK WB 20ms mono code=0", OpusToc.describe(0x48))
    }
}

package com.yairsarig.pinlink.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class TranscriptTest {
    private val jerusalem = ZoneId.of("Asia/Jerusalem")

    @Test fun nameAndHeaderUseLocalTimeWithOffset() {
        val sid = 0x6ae07d5fL // 2026-10-27 10:30:55 UTC, the button test recording
        assertEquals("2026-10-27_12-30-55.txt", Transcript.fileName(sid, jerusalem))
        assertEquals("Recorded: 2026-10-27T12:30:55+02:00\n\nidea: water the basil\n",
            Transcript.render(sid, "  idea: water the basil \n", jerusalem))
    }

    @Test fun summerTimeOffset() {
        val sid = 1_782_900_000L // 2026-07-01, IDT
        assertEquals(true, Transcript.render(sid, "x", jerusalem).startsWith("Recorded: 2026-07-01T") &&
            Transcript.render(sid, "x", jerusalem).contains("+03:00"))
    }

    @Test fun segmentLinesAreTrimmed() {
        assertEquals("Recorded: 2026-10-27T12:30:55+02:00\n\nput X on my calendar\nThursday at 4\n",
            Transcript.render(0x6ae07d5fL, " put X on my calendar\n Thursday at 4", jerusalem))
    }
}

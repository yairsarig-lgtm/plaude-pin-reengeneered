package com.yairsarig.pinlink

import com.yairsarig.pinlink.ble.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkNoticeTest {
    private fun text(paused: Boolean = false, bt: Boolean = true, link: LinkState = LinkState.DISCONNECTED, next: Long? = null) =
        LinkNotice.of(paused, bt, link, next).text

    @Test fun backoffLadderThenStaysAtFiveMinutes() {
        assertEquals(listOf(5L, 15L, 30L, 60L, 120L, 300L, 300L, 300L), (0..7).map { LinkNotice.retryDelayMs(it) / 1000 })
    }

    @Test fun wording() {
        assertEquals("Connected to pin", text(link = LinkState.READY))
        assertEquals("Connecting to pin…", text(link = LinkState.CONNECTING))
        assertEquals("Looking for pin (next try in 5 min)", text(next = 300_000))
        assertEquals("Looking for pin (next try in 15 s)", text(next = 15_000))
        assertEquals("Looking for pin…", text())
        assertEquals("Bluetooth is off", text(bt = false))
    }

    @Test fun pausedWinsAndIsFlagged() {
        val n = LinkNotice.of(true, false, LinkState.DISCONNECTED, 5_000)
        assertEquals("Paused · tap to resume", n.text)
        assertTrue(n.paused)
        assertFalse(LinkNotice.of(false, true, LinkState.READY, null).paused)
    }

    @Test fun neverSaysListening() {
        for (paused in listOf(true, false)) for (bt in listOf(true, false)) for (l in LinkState.entries) for (next in listOf(null, 5_000L)) {
            assertFalse(text(paused, bt, l, next).contains("listen", ignoreCase = true))
        }
    }
}

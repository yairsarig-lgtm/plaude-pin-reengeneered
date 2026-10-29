package com.yairsarig.pinlink

import com.yairsarig.pinlink.SendNow.PipelineEnd
import com.yairsarig.pinlink.claude.ClaudeTrigger.Fired
import com.yairsarig.pinlink.claude.FireResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SendNowTest {
    private fun msg(pulled: Int, fired: Fired?, connected: Boolean = true, bt: Boolean = true, end: PipelineEnd = PipelineEnd.DONE) =
        SendNow.message(bt, connected, pulled, end, fired)

    @Test fun pulledAndSent() {
        assertEquals("Pulled 1 memo, sent to Claude", msg(1, Fired(FireResult.Ok(null), 1)))
        assertEquals("Pulled 2 memos, sent to Claude", msg(2, Fired(FireResult.Ok(null), 2)))
        // Nothing on the pin, but a background upload hadn't been sent yet.
        assertEquals("Sent 1 memo to Claude", msg(0, Fired(FireResult.Ok(null), 1)))
    }

    @Test fun nothingToDo() {
        assertEquals("Nothing new on the pin", msg(0, null))
        assertEquals("Pin not in range", msg(0, null, connected = false))
        assertEquals("Bluetooth is off", msg(0, null, connected = false, bt = false))
        assertEquals("Pulled 1 memo, nothing to send", msg(1, null)) // e.g. a silent memo the loop guard skipped
    }

    @Test fun outOfRangeStillSendsWhatsOnThePhone() {
        assertEquals("Pin not in range · Sent 1 memo to Claude", msg(0, Fired(FireResult.Ok(null), 1), connected = false))
    }

    @Test fun claudeProblems() {
        assertEquals("Sent to Claude; it will run at the next hourly check", msg(1, Fired(FireResult.RateLimited(60), 1)))
        assertEquals("Sent to Claude; it will run at the next hourly check", msg(1, Fired(FireResult.Capped, 1)))
        assertEquals("Uploaded. Add your Claude routine in Settings to send now", msg(1, Fired(FireResult.NotConfigured, 1)))
        assertEquals("Claude rejected the routine — check Settings", msg(1, Fired(FireResult.Rejected(401, "x"), 1)))
        assertEquals("Couldn't reach Claude; the hourly check will pick it up", msg(1, Fired(FireResult.Failed("x"), 1)))
    }

    @Test fun pipelineDidNotFinish() {
        assertEquals("Drive needs reconnecting", msg(1, null, end = PipelineEnd.DRIVE))
        assertEquals("Still working — it will finish in the background", msg(1, null, end = PipelineEnd.TIMEOUT))
    }
}

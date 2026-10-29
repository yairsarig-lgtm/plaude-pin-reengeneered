package com.yairsarig.pinlink.claude

import com.yairsarig.pinlink.PinConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutineTest {

    @Test fun okReadsSessionUrl() {
        val body = """{"type":"routine_fire","claude_code_session_id":"session_01","claude_code_session_url":"https://claude.ai/code/session_01"}"""
        assertEquals(FireResult.Ok("https://claude.ai/code/session_01"), RoutineClient.interpret(200, body, null))
        assertEquals(FireResult.Ok(null), RoutineClient.interpret(200, "", null))
    }

    @Test fun rateLimitKeepsRetryAfter() {
        assertEquals(FireResult.RateLimited(1800), RoutineClient.interpret(429, "{}", " 1800"))
        assertEquals(FireResult.RateLimited(null), RoutineClient.interpret(429, "{}", null))
    }

    @Test fun authAndMissingRoutineAreRejections() {
        val body = """{"type":"error","error":{"type":"authentication_error","message":"bad token"}}"""
        assertEquals(FireResult.Rejected(401, "bad token"), RoutineClient.interpret(401, body, null))
        assertEquals(FireResult.Rejected(404, "HTTP 404"), RoutineClient.interpret(404, "not json", null))
        assertEquals(true, RoutineClient.interpret(400, "{}", null) is FireResult.Rejected) // paused routine
    }

    @Test fun serverErrorsAreRetryable() {
        assertEquals(true, RoutineClient.interpret(503, "", null) is FireResult.Failed)
        assertEquals(true, RoutineClient.interpret(500, "", null) is FireResult.Failed)
    }

    @Test fun onlyFiresInsideTheHourCount() {
        val now = 10 * PinConfig.FIRE_WINDOW_MS
        val csv = listOf(now - PinConfig.FIRE_WINDOW_MS - 1, now - PinConfig.FIRE_WINDOW_MS + 1, now - 5).joinToString(",")
        assertEquals(listOf(now - PinConfig.FIRE_WINDOW_MS + 1, now - 5), ClaudeTrigger.recentFires(csv, now))
        assertEquals(emptyList<Long>(), ClaudeTrigger.recentFires("", now))
        assertEquals(emptyList<Long>(), ClaudeTrigger.recentFires("junk,,", now))
    }

    @Test fun fireTextCountsMemos() {
        assertEquals("Triggered from Pocket Ideas: 2 new memo(s) uploaded.", PinConfig.FIRE_TEXT_TEMPLATE.format(2))
    }
}

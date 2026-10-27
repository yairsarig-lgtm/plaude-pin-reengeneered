package com.yairsarig.pinlink.upload

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class DriveSessionTest {
    private val cleared = mutableListOf<String>()

    private fun session(vararg outcomes: DriveAuth.Outcome): DriveSession {
        val queue = ArrayDeque(outcomes.toList())
        return DriveSession({ queue.removeFirst() }, { cleared += it })
    }

    private fun token(t: String) = DriveAuth.Outcome.Token(t)
    private val consent = DriveAuth.Outcome.NeedsConsent(intent = null)
    private val unauthorized = DriveClient.HttpException(401, "invalid authentication credentials")

    @Test fun okPassesThrough() {
        assertEquals(DriveSession.Result.Ok("id1"), session(token("a")).run { "id1" })
        assertEquals(emptyList<String>(), cleared)
    }

    @Test fun staleTokenIsClearedAndFreshOneUsed() {
        val r = session(token("stale"), token("fresh")).run { t -> if (t == "stale") throw unauthorized else "id-$t" }
        assertEquals(DriveSession.Result.Ok("id-fresh"), r)
        assertEquals(listOf("stale"), cleared)
    }

    @Test fun revokedAccessEndsInNeedsConsent() {
        // After clearing, Play services can't mint a token without the consent screen.
        val r = session(token("stale"), consent).run<String> { throw unauthorized }
        assertEquals(DriveSession.Result.NeedsConsent, r)
        assertEquals(listOf("stale"), cleared)
    }

    @Test fun twoUnauthorizedInARowEndsInNeedsConsent() {
        val r = session(token("a"), token("b")).run<String> { throw unauthorized }
        assertEquals(DriveSession.Result.NeedsConsent, r)
        assertEquals(listOf("a", "b"), cleared)
    }

    @Test fun authorizeThrowingAfter401IsNeedsConsent() {
        val queue = ArrayDeque(listOf<() -> DriveAuth.Outcome>({ token("stale") }, { throw IllegalStateException("no account") }))
        val r = DriveSession({ queue.removeFirst()() }, { cleared += it }).run<String> { throw unauthorized }
        assertEquals(DriveSession.Result.NeedsConsent, r)
    }

    @Test(expected = DriveClient.HttpException::class)
    fun otherHttpErrorsPropagateForNormalRetry() {
        session(token("a")).run<String> { throw DriveClient.HttpException(503, "backend") }
    }

    @Test(expected = IOException::class)
    fun networkErrorsPropagateForNormalRetry() {
        session(token("a")).run<String> { throw IOException("timeout") }
    }
}

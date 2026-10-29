package com.yairsarig.pinlink.housekeeping

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetentionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = 24 * 3600 * 1000L
    private val now = 100 * day
    private val week = 7 * day

    private fun file(dir: File, name: String, ageDays: Int) =
        File(dir, name).apply { parentFile.mkdirs(); writeText("x"); setLastModified(now - ageDays * day) }

    private fun select(t: File, r: File, l: File) = Retention.select(t, r, l, now, week).map { it.name }.sorted()

    @Test fun deletesUploadedMemoAfterAWeekOfBeingInDrive() {
        val t = tmp.newFolder("t"); val r = tmp.newFolder("r"); val l = tmp.newFolder("l")
        file(t, "2026-10-01_10-00-00.txt", 20)
        file(t, "2026-10-01_10-00-00.txt.uploaded", 8)
        file(r, "2026-10-01_10-00-00_sync.opuspk", 20)
        file(r, "2026-10-01_10-00-00_sync.wav", 20)
        assertEquals(listOf("2026-10-01_10-00-00.txt", "2026-10-01_10-00-00.txt.uploaded",
            "2026-10-01_10-00-00_sync.opuspk", "2026-10-01_10-00-00_sync.wav"), select(t, r, l))
    }

    @Test fun keepsRecentlyUploadedMemo() {
        val t = tmp.newFolder("t"); val r = tmp.newFolder("r"); val l = tmp.newFolder("l")
        file(t, "2026-10-01_10-00-00.txt", 20)
        file(t, "2026-10-01_10-00-00.txt.uploaded", 2) // uploaded 2 days ago (e.g. Drive was offline)
        file(r, "2026-10-01_10-00-00_sync.opuspk", 20)
        assertEquals(emptyList<String>(), select(t, r, l))
    }

    @Test fun neverDeletesUnuploadedCaptures() {
        val t = tmp.newFolder("t"); val r = tmp.newFolder("r"); val l = tmp.newFolder("l")
        file(t, "2026-10-01_10-00-00.txt", 30) // transcribed, never uploaded
        file(r, "2026-10-01_10-00-00_sync.opuspk", 30)
        file(r, "2026-10-02_10-00-00_partial.opuspk", 30)
        file(r, "2026-10-03_10-00-00_sync.opuspk", 30) // not even transcribed
        assertEquals(emptyList<String>(), select(t, r, l))
    }

    @Test fun deletesSkippedMemoAfterAWeek() {
        val t = tmp.newFolder("t"); val r = tmp.newFolder("r"); val l = tmp.newFolder("l")
        file(t, "2026-10-01_10-00-00.txt", 9)
        file(t, "2026-10-01_10-00-00.txt.skipped", 9)
        file(r, "2026-10-01_10-00-00_sync.opuspk", 9)
        file(t, "2026-10-20_10-00-00.txt", 1)
        file(t, "2026-10-20_10-00-00.txt.skipped", 1) // too recent
        assertEquals(listOf("2026-10-01_10-00-00.txt", "2026-10-01_10-00-00.txt.skipped",
            "2026-10-01_10-00-00_sync.opuspk"), select(t, r, l))
    }

    @Test fun deletesOldPreviewsAndLogsOnly() {
        val t = tmp.newFolder("t"); val r = tmp.newFolder("r"); val l = tmp.newFolder("l")
        file(r, "2026-10-01_10-00-00_live.opuspk", 9)
        file(r, "2026-10-01_10-00-00_live.wav", 9)
        file(r, "2026-10-20_10-00-00_live.opuspk", 1)
        file(l, "pinlink-2026-10-01.log", 9)
        file(l, "pinlink-2026-10-26.log", 1)
        assertEquals(listOf("2026-10-01_10-00-00_live.opuspk", "2026-10-01_10-00-00_live.wav", "pinlink-2026-10-01.log"),
            select(t, r, l))
    }
}

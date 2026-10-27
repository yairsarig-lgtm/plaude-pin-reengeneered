package com.yairsarig.pinlink.ideas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class IdeasModelTest {
    private val header = listOf("Recorded", "Idea", "List", "Original wording", "Status", "Source file")
    private val rows = listOf(
        header,
        listOf("2026-10-27T12:30:55+02:00", "Water the basil", "Garden", "idea water the basil", "Filed", "2026-10-27_12-30-55.txt"),
        listOf("2026-10-27T14:37:42+02:00", "Ask about the lease", "Home", "", "Needs review"), // trailing cell missing
        listOf(), // blank row
        listOf("2026-10-26 09:00", "Compost bin", "garden"),
    )

    @Test fun parsesRaggedRowsAndSkipsHeaderAndBlanks() {
        val ideas = IdeasModel.parseIdeas(rows)
        assertEquals(3, ideas.size)
        assertEquals("", ideas[1].sourceFile)
        assertEquals("Compost bin", ideas[2].text)
    }

    @Test fun newestFirst() {
        val sorted = IdeasModel.newestFirst(IdeasModel.parseIdeas(rows)).map { it.text }
        assertEquals(listOf("Ask about the lease", "Water the basil", "Compost bin"), sorted)
    }

    @Test fun tagCountsGroupCaseInsensitively() {
        val counts = IdeasModel.tagCounts(IdeasModel.parseIdeas(rows) + Idea("", "x", "Garden", "", "", ""))
        assertEquals(listOf("Garden" to 3, "Home" to 1), counts)
    }

    @Test fun filters() {
        val ideas = IdeasModel.parseIdeas(rows)
        assertEquals(3, IdeasModel.apply(IdeaFilter.All, ideas).size)
        assertEquals(listOf("Ask about the lease"), IdeasModel.apply(IdeaFilter.NeedsReview, ideas).map { it.text })
        assertEquals(listOf("Water the basil", "Compost bin"), IdeasModel.apply(IdeaFilter.Tag("Garden"), ideas).map { it.text })
    }

    @Test fun rowIndexMatchesExactRowEvenAfterOthersMoved() {
        val target = IdeasModel.parseIdeas(rows)[1]
        assertEquals(2, IdeasModel.rowIndexOf(rows, target))
        // The hourly task inserted a row above it meanwhile.
        val shifted = listOf(header, listOf("2026-10-27T15:00:00+02:00", "New one", "Home")) + rows.drop(1)
        assertEquals(3, IdeasModel.rowIndexOf(shifted, target))
        assertNull(IdeasModel.rowIndexOf(listOf(header), target))
    }

    @Test fun timeLabels() {
        val zone = ZoneId.of("Asia/Jerusalem")
        val now = ZonedDateTime.of(2026, 10, 27, 16, 0, 0, 0, zone)
        assertEquals("14:37", IdeasModel.timeLabel("2026-10-27T14:37:42+02:00", now))
        assertEquals("Yesterday 09:00", IdeasModel.timeLabel("2026-10-26T09:00:00+02:00", now))
        assertEquals("Fri 09:00", IdeasModel.timeLabel("2026-10-23T09:00:00+03:00", now))
        assertEquals("1 Oct", IdeasModel.timeLabel("2026-10-01", now))
        assertEquals("sometime", IdeasModel.timeLabel("sometime", now))
    }

    @Test fun parsesLogAndKeepsNewestTen() {
        val log = listOf(listOf("Processed", "Source file", "Transcript", "Action taken", "Result")) +
            (1..12).map { listOf("2026-10-27 10:%02d".format(it), "f$it.txt", "t", "Filed idea", "OK") }
        val recent = IdeasModel.recentLog(IdeasModel.parseLog(log))
        assertEquals(10, recent.size)
        assertEquals("f12.txt", recent.first().sourceFile)
    }
}

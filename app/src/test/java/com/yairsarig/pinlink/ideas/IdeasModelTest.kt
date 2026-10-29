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
        assertEquals("Today 14:37", IdeasModel.timeLabel("2026-10-27T14:37:42+02:00", now))
        assertEquals("Yesterday", IdeasModel.timeLabel("2026-10-26T09:00:00+02:00", now))
        assertEquals("23 Oct", IdeasModel.timeLabel("2026-10-23T09:00:00+03:00", now))
        assertEquals("1 Oct", IdeasModel.timeLabel("2026-10-01", now))
        assertEquals("3 Dec 2025", IdeasModel.timeLabel("2025-12-03", now))
        assertEquals("sometime", IdeasModel.timeLabel("sometime", now))
    }

    @Test fun countsTodaysIdeasAndBuildsDateLine() {
        val zone = ZoneId.of("Asia/Jerusalem")
        val now = ZonedDateTime.of(2026, 10, 27, 16, 0, 0, 0, zone)
        fun idea(t: String) = Idea(t, "x", "", "", "", "")
        // 23:30 UTC on the 26th is 01:30 on the 27th in Jerusalem, so it counts as today.
        val ideas = listOf(idea("2026-10-27T13:56:00+02:00"), idea("2026-10-26T23:30:00Z"),
            idea("2026-10-26T21:00:00+02:00"), idea("garbage"))
        assertEquals(2, IdeasModel.newToday(ideas, now))
        assertEquals("Tuesday, 27 October · 2 new ideas today", IdeasModel.dateLine(2, now))
        assertEquals("Tuesday, 27 October · 1 new idea today", IdeasModel.dateLine(1, now))
        assertEquals("Tuesday, 27 October", IdeasModel.dateLine(0, now))
    }

    @Test fun parsesLogAndKeepsNewestTen() {
        val log = listOf(listOf("Processed", "Source file", "Transcript", "Action taken", "Result")) +
            (1..12).map { listOf("2026-10-27 10:%02d".format(it), "f$it.txt", "t", "Filed idea", "OK") }
        val recent = IdeasModel.recentLog(IdeasModel.parseLog(log))
        assertEquals(10, recent.size)
        assertEquals("f12.txt", recent.first().sourceFile)
    }

    @Test fun listsGroupTagsWithTwoPlusIdeasNewestListFirst() {
        fun idea(t: String, list: String, text: String) = Idea(t, text, list, "", "", "")
        val ideas = listOf(
            idea("2026-10-20 09:00", "Garden", "compost"),
            idea("2026-10-28 09:00", "Books", "Dune"),
            idea("2026-10-27 09:00", "garden", "basil"),
            idea("2026-10-26 09:00", "Books", "Piranesi"),
            idea("2026-10-29 09:00", "Home", "lease"),   // only one: a single
            idea("2026-10-25 09:00", "", "untagged"),    // untagged: a single
            idea("2026-10-21 09:00", "Garden", "rake"),
        )
        val l = IdeasModel.lists(ideas)
        assertEquals(listOf("Books", "Garden"), l.lists.map { it.name }) // Books' newest (28th) beats Garden's (27th)
        assertEquals(listOf("basil", "rake", "compost"), l.lists[1].ideas.map { it.text })
        assertEquals("Dune", l.lists[0].newest.text)
        assertEquals(listOf("lease", "untagged"), l.singles.map { it.text })
        assertEquals(ListsLayout(emptyList(), emptyList()), IdeasModel.lists(emptyList()))
    }
}

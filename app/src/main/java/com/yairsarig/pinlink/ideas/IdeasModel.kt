package com.yairsarig.pinlink.ideas

import com.yairsarig.pinlink.PinConfig
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One row of the sheet's Ideas tab: Recorded | Idea | List | Original wording | Status | Source file. */
data class Idea(
    val recorded: String,
    val text: String,
    val list: String,
    val originalWording: String,
    val status: String,
    val sourceFile: String,
) {
    /** Cells in sheet column order; used to find this exact row again before deleting it. */
    val cells: List<String> get() = listOf(recorded, text, list, originalWording, status, sourceFile)
    val needsReview: Boolean get() = status.contains(PinConfig.STATUS_NEEDS_REVIEW, ignoreCase = true)
}

/** One row of the Log tab: Processed | Source file | Transcript | Action taken | Result. */
data class LogEntry(val processed: String, val sourceFile: String, val transcript: String, val action: String, val result: String)

/** What the ideas list is showing. */
sealed class IdeaFilter {
    data object All : IdeaFilter()
    data object NeedsReview : IdeaFilter()
    data class Tag(val name: String) : IdeaFilter()

    val label: String get() = when (this) { All -> "All"; NeedsReview -> "Needs review"; is Tag -> name }
}

/** One tag with its ideas, newest first; [name] is its most common spelling. */
data class IdeaList(val name: String, val ideas: List<Idea>) {
    val newest: Idea get() = ideas.first()
}

/** The Lists view: tags with [PinConfig.LIST_MIN_IDEAS]+ ideas as tiles, everything else as single cards. */
data class ListsLayout(val lists: List<IdeaList>, val singles: List<Idea>)

/** Pure functions over sheet values (as the Sheets API returns them: ragged rows of strings). */
object IdeasModel {

    /** Rows after the header; trailing empty cells are missing in the API response, so pad. */
    fun parseIdeas(rows: List<List<String>>): List<Idea> = dataRows(rows, PinConfig.SHEET_IDEAS_HEADERS.first()).mapNotNull { r ->
        val c = pad(r, PinConfig.SHEET_IDEAS_HEADERS.size)
        if (c.all { it.isBlank() }) null else Idea(c[0], c[1], c[2], c[3], c[4], c[5])
    }

    fun parseLog(rows: List<List<String>>): List<LogEntry> = dataRows(rows, PinConfig.SHEET_LOG_HEADERS.first()).mapNotNull { r ->
        val c = pad(r, PinConfig.SHEET_LOG_HEADERS.size)
        if (c.all { it.isBlank() }) null else LogEntry(c[0], c[1], c[2], c[3], c[4])
    }

    /** Newest first; rows whose time can't be parsed sink to the bottom in sheet order. */
    fun newestFirst(ideas: List<Idea>): List<Idea> =
        ideas.withIndex().sortedWith(compareByDescending<IndexedValue<Idea>> { parseTime(it.value.recorded) ?: Long.MIN_VALUE }
            .thenByDescending { it.index }).map { it.value }

    fun recentLog(entries: List<LogEntry>, n: Int = PinConfig.RECENT_ACTIONS): List<LogEntry> =
        entries.withIndex().sortedByDescending { parseTime(it.value.processed) ?: it.index.toLong() }.take(n).map { it.value }

    /**
     * Tag -> count over the List column, case-insensitive ("Garden" and "garden" are one tag,
     * labelled with its most common spelling). Blank tags aren't counted. Most common first, then A-Z.
     */
    fun tagCounts(ideas: List<Idea>): List<Pair<String, Int>> =
        ideas.map { it.list.trim() }.filter { it.isNotEmpty() }
            .groupBy { it.lowercase() }.values
            .map { spellings -> spellings.groupingBy { it }.eachCount().maxBy { it.value }.key to spellings.size }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first.lowercase() })

    /**
     * Groups by tag (case-insensitive, like [tagCounts]). Lists are ordered by their newest idea;
     * singles (one-idea tags and untagged ideas) newest first.
     */
    fun lists(ideas: List<Idea>): ListsLayout {
        val sorted = newestFirst(ideas)
        val groups = sorted.filter { it.list.isNotBlank() }.groupBy { it.list.trim().lowercase() }.values
        val lists = groups.filter { it.size >= PinConfig.LIST_MIN_IDEAS }
            .map { g -> IdeaList(g.map { it.list.trim() }.groupingBy { it }.eachCount().maxBy { it.value }.key, g) }
        val inLists = lists.flatMap { it.ideas }.toSet()
        // Groups keep newestFirst order, so lists come out sorted by their newest idea already.
        return ListsLayout(lists, sorted.filterNot { it in inLists })
    }

    fun apply(filter: IdeaFilter, ideas: List<Idea>): List<Idea> = when (filter) {
        IdeaFilter.All -> ideas
        IdeaFilter.NeedsReview -> ideas.filter { it.needsReview }
        is IdeaFilter.Tag -> ideas.filter { it.list.trim().equals(filter.name, ignoreCase = true) }
    }

    /**
     * 0-based sheet row index (header = 0) of the row matching [idea] exactly, re-read just before
     * deleting so rows added/removed by the hourly task in the meantime don't shift the target.
     */
    fun rowIndexOf(rows: List<List<String>>, idea: Idea): Int? {
        val headerOffset = if (isHeader(rows.firstOrNull(), PinConfig.SHEET_IDEAS_HEADERS.first())) 1 else 0
        val i = rows.drop(headerOffset).indexOfFirst { pad(it, idea.cells.size) == idea.cells }
        return if (i < 0) null else i + headerOffset
    }

    /** "Today 14:37", "Yesterday", "27 Oct" earlier this year, "27 Oct 2025" before; raw text if unparseable. */
    fun timeLabel(recorded: String, now: ZonedDateTime = ZonedDateTime.now()): String {
        val t = parseTime(recorded)?.let { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), now.zone) } ?: return recorded
        val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> "Today " + DateTimeFormatter.ofPattern("HH:mm", Locale.US).format(t)
            days == 1L -> "Yesterday"
            t.year == now.year -> DateTimeFormatter.ofPattern("d MMM", Locale.US).format(t)
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US).format(t)
        }
    }

    /** Ideas recorded on [now]'s calendar day. */
    fun newToday(ideas: List<Idea>, now: ZonedDateTime = ZonedDateTime.now()): Int = ideas.count { idea ->
        parseTime(idea.recorded, now.zone)?.let { java.time.Instant.ofEpochMilli(it).atZone(now.zone).toLocalDate() } == now.toLocalDate()
    }

    /** "Tuesday, 27 October · 3 new ideas today"; just the date when nothing is new. */
    fun dateLine(newToday: Int, now: ZonedDateTime = ZonedDateTime.now()): String {
        val date = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.US).format(now)
        return when (newToday) {
            0 -> date
            1 -> "$date · 1 new idea today"
            else -> "$date · $newToday new ideas today"
        }
    }

    /** Epoch millis from the formats the sheet is likely to hold: ISO with offset, ISO local, "yyyy-MM-dd HH:mm[:ss]", date. */
    fun parseTime(s: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val v = s.trim()
        if (v.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(v).toInstant().toEpochMilli() }
        for (p in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd_HH-mm-ss", "d/M/yyyy H:mm:ss", "d/M/yyyy H:mm")) {
            runCatching { return LocalDateTime.parse(v, DateTimeFormatter.ofPattern(p)).atZone(zone).toInstant().toEpochMilli() }
        }
        runCatching { return LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli() }
        return null
    }

    private fun dataRows(rows: List<List<String>>, firstHeader: String) =
        if (isHeader(rows.firstOrNull(), firstHeader)) rows.drop(1) else rows

    private fun isHeader(row: List<String>?, firstHeader: String) =
        row?.firstOrNull()?.trim()?.equals(firstHeader, ignoreCase = true) == true

    private fun pad(r: List<String>, n: Int) = List(n) { r.getOrNull(it) ?: "" }
}

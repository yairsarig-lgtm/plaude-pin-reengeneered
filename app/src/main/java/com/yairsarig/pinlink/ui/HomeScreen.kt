package com.yairsarig.pinlink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.ideas.Idea
import com.yairsarig.pinlink.ideas.IdeaFilter
import com.yairsarig.pinlink.ideas.IdeasModel
import com.yairsarig.pinlink.ideas.LogEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withTimeoutOrNull

val PinStatus.color: Color
    get() = when (this) {
        PinStatus.GREEN -> Pocket.green
        PinStatus.AMBER -> Pocket.amber
        PinStatus.BLUE -> Pocket.blue
        PinStatus.GREY -> Pocket.grey
        PinStatus.RED -> Pocket.danger
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: IdeasViewModel.State,
    status: StatusView,
    /** One-off messages (Send now results); each becomes a snackbar. */
    messages: Flow<String>,
    onSendNow: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (Idea) -> Unit,
    onUndo: () -> Unit,
    onFilter: (IdeaFilter) -> Unit,
    onReconnectDrive: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var showFilter by remember { mutableStateOf(false) }

    LaunchedEffect(messages) { messages.collect { snackbar.showSnackbar(it) } }

    // Undo snackbar for the row waiting out its undo window.
    val pending = state.pendingDelete
    LaunchedEffect(pending) {
        if (pending == null) return@LaunchedEffect
        val result = withTimeoutOrNull(PinConfig.UNDO_WINDOW_MS) {
            snackbar.showSnackbar("Idea deleted", actionLabel = "Undo", withDismissAction = false,
                duration = androidx.compose.material3.SnackbarDuration.Indefinite)
        }
        if (result == SnackbarResult.ActionPerformed) onUndo() else snackbar.currentSnackbarData?.dismiss()
    }

    Box(Modifier.fillMaxSize().background(Pocket.background).safeDrawingPadding()) {
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Pocket.gutter, end = Pocket.gutter, top = 28.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Header(status, IdeasModel.dateLine(IdeasModel.newToday(state.ideas)), onSendNow, onOpenSettings) }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        val title = if (state.filter == IdeaFilter.All) "All ideas" else state.filter.label
                        SectionLabel("$title · ${state.visible.size}", Modifier.weight(1f))
                        FilterPill(if (state.filter == IdeaFilter.All) "Filter" else state.filter.label) { showFilter = true }
                    }
                }
                when {
                    state.needsDrive -> item { Notice("Google Drive isn't connected.", "Reconnect", onReconnectDrive) }
                    state.error != null && state.ideas.isEmpty() -> item { Notice(state.error, "Try again", onRefresh) }
                    !state.loaded -> item { Text("Loading…", style = Pocket.small, modifier = Modifier.padding(8.dp)) }
                    state.visible.isEmpty() -> item {
                        Text(if (state.filter == IdeaFilter.All) "No ideas yet. Press the pin and talk." else "Nothing under “${state.filter.label}”.",
                            style = Pocket.small, modifier = Modifier.padding(8.dp))
                    }
                }
                items(state.visible, key = { it.cells.joinToString("\u0001") }) { idea -> IdeaCard(idea, onDelete) }
                // Only the last week's actions; the card disappears when there are none.
                if (state.recentLog.isNotEmpty()) item { RecentActions(state.recentLog, Modifier.padding(top = 10.dp)) }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (showFilter) {
        ModalBottomSheet(onDismissRequest = { showFilter = false }, containerColor = Pocket.background) {
            FilterSheet(state) { onFilter(it); showFilter = false }
        }
    }
}

@Composable
private fun Header(status: StatusView, dateLine: String, onSendNow: () -> Unit, onOpenSettings: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("pocket•ideas", style = Pocket.wordmark, maxLines = 1, modifier = Modifier.weight(1f))
            CircleButton(LineIcons.pin, "Send now. ${status.line}", onSendNow, dot = status.dot.color, pulse = status.pulsing)
            Spacer(Modifier.width(12.dp))
            CircleButton(LineIcons.gear, "Settings", onOpenSettings)
        }
        Text(dateLine, style = Pocket.dateLine, modifier = Modifier.padding(top = 12.dp))
        Text(status.line, style = Pocket.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 48 dp outlined circle with a line icon; [dot] adds a status dot on the rim at one o'clock, [pulse] fades it in and out. */
@Composable
private fun CircleButton(icon: ImageVector, description: String, onClick: () -> Unit, dot: Color? = null, pulse: Boolean = false) {
    Box(Modifier.size(48.dp)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).border(1.dp, Pocket.outline, CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(icon, description, Modifier.size(22.dp), tint = Pocket.ink)
        }
        if (dot != null) {
            val alpha = if (pulse) {
                rememberInfiniteTransition(label = "pulse").animateFloat(1f, Pocket.pulseMinAlpha,
                    infiniteRepeatable(tween(Pocket.pulseMs), RepeatMode.Reverse), label = "dot").value
            } else 1f
            Box(Modifier.offset(x = 36.dp, y = 2.dp).size(10.dp).alpha(alpha).background(dot, CircleShape))
        }
    }
}

@Composable
private fun FilterPill(text: String, onClick: () -> Unit) {
    Row(Modifier.clip(Pocket.pillShape).border(1.dp, Pocket.outline, Pocket.pillShape).clickable(onClick = onClick)
        .padding(start = 14.dp, end = 18.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(LineIcons.funnel, null, Modifier.size(18.dp), tint = Pocket.ink)
        Spacer(Modifier.width(8.dp))
        Text(text, style = Pocket.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun IdeaCard(idea: Idea, onDelete: (Idea) -> Unit) {
    Column(Modifier.fillMaxWidth().pocketCard(if (idea.needsReview) Pocket.reviewCard else Pocket.card)
        .padding(start = 20.dp, end = 10.dp, top = 20.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(idea.text.ifBlank { idea.originalWording }, style = Pocket.ideaText,
                modifier = Modifier.weight(1f).padding(end = 10.dp, bottom = 4.dp))
            if (idea.needsReview) {
                Text("Needs review", style = Pocket.small.copy(color = Pocket.reviewText, fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.background(Pocket.reviewPill, Pocket.pillShape).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (idea.list.isNotBlank()) {
                Tag(idea.list.trim(), Modifier.widthIn(max = 180.dp))
                Spacer(Modifier.width(18.dp))
            }
            Text(IdeasModel.timeLabel(idea.recorded), style = Pocket.tag.copy(color = Pocket.muted), maxLines = 1)
            Spacer(Modifier.weight(1f))
            IconTap(LineIcons.trash, "Delete idea", Pocket.muted) { onDelete(idea) }
        }
    }
}

@Composable
private fun Tag(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Pocket.tag, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.background(Pocket.pill, Pocket.pillShape).padding(horizontal = 14.dp, vertical = 6.dp))
}

@Composable
private fun IconTap(icon: ImageVector, description: String, tint: Color, size: Dp = 22.dp, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(size), tint = tint)
    }
}

@Composable
fun Pill(text: String, onClick: () -> Unit) {
    Text(text, style = Pocket.small.copy(color = Pocket.ink),
        modifier = Modifier.border(1.dp, Pocket.frame, Pocket.pillShape).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp))
}

/** Outlined card; collapsed it still shows the newest action, open it shows them all. */
@Composable
private fun RecentActions(entries: List<LogEntry>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().clip(Pocket.corner).background(Color.White).border(1.dp, Pocket.frame, Pocket.corner)) {
        Row(Modifier.fillMaxWidth().clickable(enabled = entries.size > 1) { open = !open }
            .padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Recent actions · ${entries.size}", Modifier.weight(1f))
            if (entries.size > 1) Icon(LineIcons.chevronDown, if (open) "Collapse" else "Expand",
                Modifier.size(22.dp).rotate(if (open) 180f else 0f), tint = Pocket.ink)
        }
        Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(Pocket.divider))
        if (entries.isEmpty()) {
            Text("Nothing logged yet.", style = Pocket.small, modifier = Modifier.padding(20.dp))
            return@Column
        }
        ActionRow(entries.first(), detailed = open)
        AnimatedVisibility(open) {
            Column { for (e in entries.drop(1)) ActionRow(e, detailed = true) }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ActionRow(e: LogEntry, detailed: Boolean) {
    val isCalendar = listOf("calendar", "event").any { e.action.contains(it, ignoreCase = true) || e.result.contains(it, ignoreCase = true) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(if (isCalendar) LineIcons.calendar else LineIcons.check, null, Modifier.padding(top = 1.dp).size(22.dp), tint = Pocket.ink)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(buildAnnotatedString {
                append(e.action.ifBlank { "—" })
                val time = IdeasModel.timeLabel(e.processed)
                if (time.isNotBlank()) withStyle(SpanStyle(color = Pocket.muted)) { append(" — $time") }
            }, style = Pocket.ideaText.copy(fontSize = 17.sp, lineHeight = 23.sp), maxLines = if (detailed) 3 else 1,
                overflow = TextOverflow.Ellipsis)
            if (detailed && e.result.isNotBlank()) {
                Text(e.result, style = Pocket.small, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun FilterSheet(state: IdeasViewModel.State, onPick: (IdeaFilter) -> Unit) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 32.dp)) {
        SectionLabel("Filter", Modifier.padding(bottom = 8.dp))
        FilterRow("All", state.ideas.size, state.filter == IdeaFilter.All) { onPick(IdeaFilter.All) }
        FilterRow("Needs review", state.needsReviewCount, state.filter == IdeaFilter.NeedsReview) { onPick(IdeaFilter.NeedsReview) }
        if (state.tags.isNotEmpty()) SectionLabel("Lists", Modifier.padding(top = 16.dp, bottom = 8.dp))
        for ((tag, n) in state.tags) {
            FilterRow(tag, n, (state.filter as? IdeaFilter.Tag)?.name?.equals(tag, ignoreCase = true) == true) { onPick(IdeaFilter.Tag(tag)) }
        }
    }
}

@Composable
private fun FilterRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Pocket.body.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal), modifier = Modifier.weight(1f))
        Text("$count", style = Pocket.small)
        if (selected) Icon(LineIcons.check, "Selected", Modifier.padding(start = 10.dp).size(18.dp), tint = Pocket.ink)
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().pocketCard().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Pocket.body, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action, color = Pocket.ink, fontWeight = FontWeight.SemiBold) }
    }
}

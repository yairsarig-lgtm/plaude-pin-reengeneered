package com.yairsarig.pinlink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.ideas.Idea
import com.yairsarig.pinlink.ideas.IdeaFilter
import com.yairsarig.pinlink.ideas.IdeasModel
import com.yairsarig.pinlink.ideas.LogEntry
import kotlinx.coroutines.withTimeoutOrNull

/** Pin status for the header dot. */
enum class PinStatus(val color: Color, val description: String) {
    CONNECTED(Pocket.green, "Connected and synced"),
    SYNCING(Pocket.amber, "Syncing"),
    DISCONNECTED(Pocket.grey, "Pin not connected"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: IdeasViewModel.State,
    pinStatus: PinStatus,
    onSyncNow: () -> Unit,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: (Idea) -> Unit,
    onUndo: () -> Unit,
    onFilter: (IdeaFilter) -> Unit,
    onReconnectDrive: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    var showFilter by remember { mutableStateOf(false) }

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
        Column(Modifier.fillMaxSize()) {
            Header(pinStatus, onSyncNow, onOpenSettings)
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Ideas · ${state.visible.size}")
                Spacer(Modifier.weight(1f))
                Pill(if (state.filter == IdeaFilter.All) "Filter" else "Filter: ${state.filter.label}") { showFilter = true }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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
                    item { Spacer(Modifier.height(8.dp)); RecentActions(state.recentLog) }
                }
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
private fun Header(status: PinStatus, onSyncNow: () -> Unit, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("pocket•ideas", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = Pocket.ink)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(44.dp).clickable(onClick = onSyncNow), contentAlignment = Alignment.Center) {
            Text("📌", fontSize = 20.sp)
            Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 8.dp).size(10.dp)
                .background(Pocket.background, CircleShape).padding(1.5.dp).background(status.color, CircleShape))
        }
        Box(Modifier.size(44.dp).clickable(onClick = onOpenSettings), contentAlignment = Alignment.Center) {
            Text("⚙️", fontSize = 20.sp)
        }
    }
}

@Composable
private fun IdeaCard(idea: Idea, onDelete: (Idea) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Pocket.card, Pocket.corner).padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(idea.text.ifBlank { idea.originalWording }, style = Pocket.ideaText, modifier = Modifier.weight(1f).padding(end = 4.dp))
            Box(Modifier.size(40.dp).clickable { onDelete(idea) }, contentAlignment = Alignment.Center) { Text("🗑", fontSize = 16.sp) }
        }
        Row(Modifier.padding(top = 2.dp, bottom = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (idea.list.isNotBlank()) Tag(idea.list.trim())
            if (idea.needsReview) Tag("Needs review")
            Spacer(Modifier.weight(1f))
            Text(IdeasModel.timeLabel(idea.recorded), style = Pocket.small)
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(text, style = Pocket.small.copy(color = Pocket.ink), maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.background(Pocket.pill, Pocket.pillShape).padding(horizontal = 10.dp, vertical = 3.dp))
}

@Composable
fun Pill(text: String, onClick: () -> Unit) {
    Text(text, style = Pocket.small.copy(color = Pocket.ink),
        modifier = Modifier.border(1.dp, Pocket.frame, Pocket.pillShape).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp))
}

@Composable
private fun RecentActions(entries: List<LogEntry>) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(Pocket.background, Pocket.corner).border(1.dp, Pocket.frame, Pocket.corner)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Recent actions")
            Spacer(Modifier.weight(1f))
            Text(if (open) "▴" else "▾", style = Pocket.small)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (entries.isEmpty()) Text("Nothing logged yet.", style = Pocket.small)
                for (e in entries) {
                    Column {
                        Text(e.action.ifBlank { "—" }, style = Pocket.body)
                        Text(listOf(IdeasModel.timeLabel(e.processed), e.result, e.sourceFile).filter { it.isNotBlank() }.joinToString(" · "),
                            style = Pocket.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
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
        if (selected) Text("  ✓", style = Pocket.body)
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Pocket.card, Pocket.corner).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = Pocket.body, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action, color = Pocket.ink, fontWeight = FontWeight.SemiBold) }
    }
}

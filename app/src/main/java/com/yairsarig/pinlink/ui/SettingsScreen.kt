package com.yairsarig.pinlink.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.Pipeline
import com.yairsarig.pinlink.ble.LinkState
import com.yairsarig.pinlink.ble.WriteMode
import com.yairsarig.pinlink.claude.ClaudeTrigger
import com.yairsarig.pinlink.housekeeping.DeviceSetup
import com.yairsarig.pinlink.ideas.IdeasModel
import com.yairsarig.pinlink.protocol.PlaudProtocol
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Everything that isn't the ideas list. */
class SettingsUi(
    val link: LinkState,
    val battery: PlaudProtocol.Battery?,
    val filesOnPin: Int,
    val busy: Boolean,
    val driveConnected: Boolean?,
    val sheetId: String?,
    /** Background-setup steps that apply to this phone, and which of them are still to do. */
    val setupSteps: List<DeviceSetup.Step>,
    val setupPending: List<DeviceSetup.Step>,
    val modelReady: Boolean,
    val pipeline: Pipeline.Status,
    val lastUploadAt: Long?,
    val recordings: List<File>,
    val log: List<String>,
    val claude: ClaudeTrigger.State,
    val routineUrl: String,
    val hasToken: Boolean,
    /** Outcome of the last Test, in plain words (null = not run, "Testing…" while running). */
    val testResult: String?,
)

class SettingsActions(
    val back: () -> Unit,
    val syncNow: () -> Unit,
    val disconnect: () -> Unit,
    val forget: () -> Unit,
    val reconnectDrive: () -> Unit,
    val openSheet: () -> Unit,
    val openSetup: (DeviceSetup.Step) -> Unit,
    val confirmSetup: (DeviceSetup.Step) -> Unit,
    val diagnose: () -> Unit,
    val play: (File) -> Unit,
    val startLive: () -> Unit,
    val stopLive: () -> Unit,
    val sendRaw: (cmd: String, payloadHex: String, WriteMode) -> Unit,
    /** [token] null keeps the stored token. */
    val saveRoutine: (url: String, token: String?) -> Unit,
    val testRoutine: () -> Unit,
    val setAutoSend: (Boolean) -> Unit,
)

/** Same look as home: one #F2F1EC card per section, hairlines inside, small uppercase labels above. */
@Composable
fun SettingsScreen(ui: SettingsUi, act: SettingsActions) {
    Column(Modifier.fillMaxSize().background(Pocket.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = Pocket.gutter, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = act.back), contentAlignment = Alignment.Center) {
                Icon(LineIcons.arrowLeft, "Back", Modifier.size(24.dp), tint = Pocket.ink)
            }
            Spacer(Modifier.width(6.dp))
            Text("Settings", style = Pocket.listTitle)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = Pocket.gutter, end = Pocket.gutter, top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp)) {
            PinCard(ui, act)
            ClaudeCard(ui, act)
            StatusColorsCard()
            GoogleCard(ui, act)
            if (ui.setupPending.isNotEmpty()) BackgroundCard(ui, act)
            TranscriptionCard(ui)
            DeveloperCard(ui, act)
        }
    }
}

// ---------- Sections ----------

@Composable
private fun PinCard(ui: SettingsUi, act: SettingsActions) = SettingsCard("Pin") {
    Padded {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val dot = when (ui.link) { LinkState.READY -> Pocket.green; LinkState.CONNECTING -> Pocket.amber; LinkState.DISCONNECTED -> Pocket.grey }
            Box(Modifier.size(10.dp).background(dot, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text("Plaud NotePin", style = Pocket.rowTitle)
        }
        val state = when (ui.link) { LinkState.READY -> "Connected"; LinkState.CONNECTING -> "Connecting…"; LinkState.DISCONNECTED -> "Not connected" }
        val battery = ui.battery?.let { " · battery ${it.percent}%${if (it.charging) " (charging)" else ""}" }.orEmpty()
        val waiting = if (ui.filesOnPin == 1) "1 recording waiting" else "${ui.filesOnPin} recordings waiting"
        Text("$state$battery · $waiting", style = Pocket.small, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledButton("Sync now", Modifier.weight(1f), act.syncNow)
            OutlineButton("Disconnect", Modifier.weight(1f), act.disconnect)
        }
    }
    Hairline()
    ActionRow("Forget this pin", color = Pocket.danger, onClick = act.forget)
}

@Composable
private fun ClaudeCard(ui: SettingsUi, act: SettingsActions) = SettingsCard("Claude") {
    var url by remember { mutableStateOf(ui.routineUrl) }
    var token by remember { mutableStateOf("") }
    Padded {
        Text(when {
            ui.claude.rejected -> "Routine rejected the last request — check the URL and token"
            ui.claude.configured -> "Routine set up"
            else -> "Not set up: memos wait for the hourly check"
        }, style = Pocket.small.copy(color = if (ui.claude.rejected) Pocket.danger else Pocket.muted))
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().padding(top = 10.dp), label = { Text("Routine URL") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Routine token") }, singleLine = true,
            placeholder = { if (ui.hasToken) Text("Saved — type to replace") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledButton("Save", Modifier.weight(1f)) { act.saveRoutine(url, token.ifBlank { null }); token = "" }
            OutlineButton("Test", Modifier.weight(1f), act.testRoutine)
        }
        ui.testResult?.let { SelectionContainer { Text(it, style = Pocket.small, modifier = Modifier.padding(top = 10.dp)) } }
    }
    Hairline()
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Send to Claude automatically", style = Pocket.body)
            Text("Batches memos for ${PinConfig.FIRE_DEBOUNCE_SECONDS} s · hourly check stays as backup", style = Pocket.small.copy(fontSize = 12.sp))
        }
        Switch(ui.claude.auto, act.setAutoSend, colors = SwitchDefaults.colors(
            checkedTrackColor = Pocket.ink, checkedThumbColor = Color.White,
            uncheckedTrackColor = Pocket.background, uncheckedBorderColor = Pocket.outline, uncheckedThumbColor = Pocket.muted))
    }
}

@Composable
private fun StatusColorsCard() = SettingsCard("Status colors") {
    Padded(Arrangement.spacedBy(12.dp)) {
        for (st in PinStatus.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(st.color, CircleShape))
                Spacer(Modifier.width(14.dp))
                Text(st.short, style = Pocket.body)
            }
        }
    }
    Hairline()
    Text("Tap the pin button to send now.", style = Pocket.small, modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
}

@Composable
private fun GoogleCard(ui: SettingsUi, act: SettingsActions) = SettingsCard("Google") {
    Padded {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(when (ui.driveConnected) { true -> "Drive connected"; false -> "Drive not connected"; null -> "Checking Drive…" },
                    style = Pocket.rowTitle.copy(color = if (ui.driveConnected == false) Pocket.danger else Pocket.ink))
                Text("Last upload: " + (ui.lastUploadAt?.let { IdeasModel.timeLabel(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()) } ?: "none yet"),
                    style = Pocket.small, modifier = Modifier.padding(top = 4.dp))
            }
            OutlineButton("Reconnect", onClick = act.reconnectDrive)
        }
    }
    Hairline()
    if (ui.sheetId != null) {
        ActionRow("Open Pin Ideas sheet ↗", subtitle = ui.sheetId, onClick = act.openSheet)
    } else {
        Text("The ideas sheet is created on first load.", style = Pocket.small, modifier = Modifier.padding(18.dp))
    }
}

@Composable
private fun BackgroundCard(ui: SettingsUi, act: SettingsActions) = SettingsCard("Keep running in background") {
    ui.setupSteps.forEachIndexed { i, step ->
        if (i > 0) Hairline()
        val pending = step in ui.setupPending
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(step.title, style = Pocket.body)
                Text(step.why, style = Pocket.small.copy(fontSize = 12.sp))
            }
            when {
                !pending -> Text("On", style = Pocket.small)
                step == DeviceSetup.Step.BATTERY_EXEMPTION -> OutlineButton("Turn on") { act.openSetup(step) }
                else -> Column(horizontalAlignment = Alignment.End) {
                    OutlineButton("Turn on") { act.openSetup(step) }
                    Text("Done", style = Pocket.small.copy(color = Pocket.ink, fontWeight = FontWeight.Medium),
                        modifier = Modifier.clip(Pocket.pillShape).clickable { act.confirmSetup(step) }.padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun TranscriptionCard(ui: SettingsUi) = SettingsCard("Transcription") {
    Padded {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Offline · English", style = Pocket.rowTitle)
                Text("Whisper base.en, on this phone", style = Pocket.small, modifier = Modifier.padding(top = 4.dp))
            }
            Text(if (ui.modelReady) "Ready" else "Downloads on Wi-Fi", style = Pocket.small.copy(color = if (ui.modelReady) Pocket.green else Pocket.muted,
                fontWeight = FontWeight.Medium))
        }
        if (ui.pipeline.pending > 0) {
            Text("${ui.pipeline.toTranscribe} to transcribe · ${ui.pipeline.toUpload} to upload", style = Pocket.small, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun DeveloperCard(ui: SettingsUi, act: SettingsActions) = SettingsCard("Developer") {
    ActionRow("Diagnose connection", subtitle = "Reads characteristics, tries both write types; see log", onClick = act.diagnose)
    Hairline()
    Expandable("Recordings on phone", "${ui.recordings.size}") {
        if (ui.recordings.isEmpty()) Text("None", style = Pocket.small)
        for (f in ui.recordings.take(15)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.name.take(19), style = Pocket.small.copy(color = Pocket.ink), modifier = Modifier.weight(1f))
                Text("${f.length() / 1024} KB", style = Pocket.small)
                Spacer(Modifier.size(8.dp))
                if (ui.busy) Text("busy", style = Pocket.small) else Pill("Play") { act.play(f) }
            }
        }
    }
    Hairline()
    Expandable("Live record from phone") {
        Text("Makes the pin record and streams it live. Slow; the button is better.", style = Pocket.small)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Pill("Start live", act.startLive); Pill("Stop live", act.stopLive) }
    }
    Hairline()
    Expandable("Raw command console") { RawConsole(act.sendRaw) }
    Hairline()
    Expandable("View log") {
        Column(Modifier.fillMaxWidth().height(260.dp).background(Pocket.background, Pocket.corner)
            .verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(10.dp)) {
            for (l in ui.log.takeLast(120)) Text(l, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 11.sp)
        }
    }
}

@Composable
private fun RawConsole(send: (String, String, WriteMode) -> Unit) {
    var cmd by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(WriteMode.AUTO) }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(cmd, { cmd = it }, Modifier.weight(0.3f), label = { Text("cmd") }, singleLine = true)
        OutlinedTextField(payload, { payload = it }, Modifier.weight(0.7f), label = { Text("payload hex") }, singleLine = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Write: $mode") { mode = WriteMode.entries[(mode.ordinal + 1) % WriteMode.entries.size] }
        Pill("Send") { send(cmd, payload, mode) }
    }
}

// ---------- Building blocks ----------

/** Label above a borderless card; children stack edge to edge so [Hairline]s span the card. */
@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        SectionLabel(title, Modifier.padding(start = 4.dp, bottom = 10.dp))
        Column(Modifier.fillMaxWidth().clip(Pocket.corner).background(Pocket.card), content = content)
    }
}

@Composable
private fun Padded(arrangement: Arrangement.Vertical = Arrangement.Top, content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = arrangement, content = content)

@Composable
private fun Hairline() = Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(1.dp).background(Pocket.hairline))

/** Full-width tappable row: title (optionally coloured), grey subtitle. */
@Composable
private fun ActionRow(title: String, subtitle: String? = null, color: Color = Pocket.ink, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(title, style = Pocket.body.copy(color = color))
        if (subtitle != null) Text(subtitle, style = Pocket.small.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Row that opens in place, chevron on the right. */
@Composable
private fun Expandable(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 18.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Pocket.body, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = Pocket.small, modifier = Modifier.padding(end = 8.dp))
        Icon(LineIcons.chevronDown, if (open) "Collapse" else "Expand", Modifier.size(20.dp).rotate(if (open) 180f else 0f), tint = Pocket.muted)
    }
    AnimatedVisibility(open) {
        Column(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun FilledButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) = ButtonBox(text, modifier, true, onClick)

@Composable
private fun OutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) = ButtonBox(text, modifier, false, onClick)

@Composable
private fun ButtonBox(text: String, modifier: Modifier, filled: Boolean, onClick: () -> Unit) {
    val base = modifier.height(44.dp).clip(Pocket.pillShape)
    Box((if (filled) base.background(Pocket.ink) else base.border(1.dp, Pocket.outline, Pocket.pillShape))
        .clickable(onClick = onClick).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, style = Pocket.body.copy(color = if (filled) Color.White else Pocket.ink, fontWeight = FontWeight.Medium),
            textAlign = TextAlign.Center, maxLines = 1)
    }
}


package com.yairsarig.pinlink.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yairsarig.pinlink.Pipeline
import com.yairsarig.pinlink.ble.LinkState
import com.yairsarig.pinlink.ble.WriteMode
import com.yairsarig.pinlink.housekeeping.DeviceSetup
import com.yairsarig.pinlink.protocol.PlaudProtocol
import java.io.File

/** Everything that isn't the ideas list. */
class SettingsUi(
    val link: LinkState,
    val battery: PlaudProtocol.Battery?,
    val filesOnPin: Int,
    val busy: Boolean,
    val driveConnected: Boolean?,
    val sheetId: String?,
    val setupSteps: List<DeviceSetup.Step>,
    val modelReady: Boolean,
    val pipeline: Pipeline.Status,
    val recordings: List<File>,
    val log: List<String>,
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
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(ui: SettingsUi, act: SettingsActions) {
    Column(Modifier.fillMaxSize().background(Pocket.background).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = act.back), contentAlignment = Alignment.Center) { Text("←", fontSize = 22.sp) }
            Text("Settings", style = Pocket.body.copy(fontSize = 20.sp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {

            Section("Pin") {
                Line(when (ui.link) { LinkState.READY -> "Connected"; LinkState.CONNECTING -> "Connecting…"; LinkState.DISCONNECTED -> "Not connected" } +
                    (ui.battery?.let { " · battery ${it.percent}%${if (it.charging) " (charging)" else ""}" } ?: ""))
                Line("${ui.filesOnPin} memo(s) waiting on the pin", small = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Sync now", act.syncNow); Pill("Disconnect", act.disconnect); Pill("Forget pin", act.forget)
                }
            }

            Section("Google") {
                Line("Drive: " + when (ui.driveConnected) { true -> "connected"; false -> "not connected"; null -> "checking…" })
                if (ui.sheetId != null) {
                    Line("Ideas sheet ID", small = true)
                    SelectionContainer { Text(ui.sheetId, style = Pocket.small.copy(fontFamily = FontFamily.Monospace, color = Pocket.ink)) }
                } else {
                    Line("The ideas sheet is created on first load.", small = true)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Reconnect Drive", act.reconnectDrive)
                    if (ui.sheetId != null) Pill("Open sheet", act.openSheet)
                }
            }

            if (ui.setupSteps.isNotEmpty()) {
                Section("Keep running in background") {
                    for (step in ui.setupSteps) {
                        Column(Modifier.fillMaxWidth().background(Pocket.card, Pocket.corner).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Line(step.title)
                            Line(step.why, small = true)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Pill("Open settings") { act.openSetup(step) }
                                if (step != DeviceSetup.Step.BATTERY_EXEMPTION) Pill("Done") { act.confirmSetup(step) }
                            }
                        }
                    }
                }
            }

            Section("Transcription") {
                Line(if (ui.modelReady) "Offline model ready (Whisper base.en)" else "Model downloads on Wi-Fi with the first memo")
                Line("${ui.pipeline.toTranscribe} to transcribe · ${ui.pipeline.toUpload} to upload", small = true)
            }

            Section("Developer") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Diagnose", act.diagnose); Pill("Start live", act.startLive); Pill("Stop live", act.stopLive)
                }
                SectionLabel("Recordings on phone", Modifier.padding(top = 8.dp))
                if (ui.recordings.isEmpty()) Line("None", small = true)
                for (f in ui.recordings.take(15)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name.take(19), style = Pocket.small.copy(color = Pocket.ink), modifier = Modifier.weight(1f))
                        Text("${f.length() / 1024} KB", style = Pocket.small)
                        Spacer(Modifier.size(8.dp))
                        if (ui.busy) Text("busy", style = Pocket.small) else Pill("Play") { act.play(f) }
                    }
                }
                RawConsole(act.sendRaw)
                SectionLabel("Log", Modifier.padding(top = 8.dp))
                Column(Modifier.fillMaxWidth().height(260.dp).border(1.dp, Pocket.frame, Pocket.corner)
                    .verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(10.dp)) {
                    for (l in ui.log.takeLast(120)) Text(l, fontFamily = FontFamily.Monospace, fontSize = 9.sp, lineHeight = 11.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun RawConsole(send: (String, String, WriteMode) -> Unit) {
    var cmd by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(WriteMode.AUTO) }
    SectionLabel("Raw command", Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(cmd, { cmd = it }, Modifier.weight(0.3f), label = { Text("cmd") }, singleLine = true)
        OutlinedTextField(payload, { payload = it }, Modifier.weight(0.7f), label = { Text("payload hex") }, singleLine = true)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pill("Write: $mode") { mode = WriteMode.entries[(mode.ordinal + 1) % WriteMode.entries.size] }
        Pill("Send") { send(cmd, payload, mode) }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun Line(text: String, small: Boolean = false) {
    Text(text, style = if (small) Pocket.small else Pocket.body)
}

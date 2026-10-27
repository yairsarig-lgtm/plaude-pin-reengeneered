package com.yairsarig.pinlink

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yairsarig.pinlink.ble.WriteMode
import com.yairsarig.pinlink.protocol.hexToBytes
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val controller by lazy { (application as PinLinkApp).controller }
    private var player: MediaPlayer? = null

    private val requiredPermissions: Array<String>
        get() = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        PinLog.i("Permissions: $r")
        if (hasPermissions()) PinLinkService.start(this)
    }

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (hasPermissions()) PinLinkService.start(this) else permissionLauncher.launch(requiredPermissions)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun Screen() {
        val link by controller.linkState.collectAsStateWithLifecycle()
        val battery by controller.battery.collectAsStateWithLifecycle()
        val recording by controller.recording.collectAsStateWithLifecycle()
        val lastCapture by controller.lastCapture.collectAsStateWithLifecycle()
        val log by PinLog.lines.collectAsStateWithLifecycle()
        val stored by controller.storedFiles.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        var cmdId by remember { mutableStateOf("") }
        var payload by remember { mutableStateOf("") }
        var writeMode by remember { mutableStateOf(WriteMode.AUTO) }

        Column(Modifier.safeDrawingPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Link: $link   Battery: ${battery?.let { "${it.percent}%${if (it.charging) " ⚡" else ""}" } ?: "?"}")
            Text("Recording: ${recording ?: "-"}")
            Text("Last capture: ${lastCapture?.name ?: "-"}")
            Text("On pin: ${stored.size} file(s), ${stored.sumOf { it.bytes } / PinConfig.OPUS_BYTES_PER_SEC}s")

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { controller.connect() }) { Text("Connect") }
                Button(onClick = { controller.disconnect() }) { Text("Disconnect") }
                Button(onClick = { controller.forgetPin() }) { Text("Forget") }
                Button(onClick = { controller.readBattery() }) { Text("Battery") }
                Button(onClick = { controller.runDiagnostics() }) { Text("Diagnose") }
                Button(onClick = { controller.listFiles() }) { Text("List files") }
                Button(onClick = { controller.syncAll() }) { Text("Sync all") }
                Button(onClick = { controller.startLiveSession() }) { Text("Start live") }
                Button(onClick = { controller.stopLiveSession() }) { Text("Stop live") }
                Button(onClick = { scope.launch { decodeAndPlay() } }) { Text("Decode + play") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(cmdId, { cmdId = it }, Modifier.weight(0.25f), label = { Text("cmd") }, singleLine = true)
                OutlinedTextField(payload, { payload = it }, Modifier.weight(0.55f), label = { Text("payload hex") }, singleLine = true)
                Button(onClick = { sendRaw(cmdId, payload, writeMode) }, Modifier.weight(0.2f)) { Text("Send") }
            }
            Button(onClick = { writeMode = WriteMode.entries[(writeMode.ordinal + 1) % WriteMode.entries.size] }) {
                Text("Console write: $writeMode")
            }

            val listState = rememberLazyListState()
            LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
                items(log) { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp) }
            }
        }
    }

    private fun sendRaw(cmd: String, hex: String, mode: WriteMode) {
        val id = cmd.trim().let { if (it.startsWith("0x")) it.drop(2).toIntOrNull(16) else it.toIntOrNull() }
        val bytes = runCatching { hex.hexToBytes() }.getOrNull()
        if (id == null || bytes == null) PinLog.w("Bad cmd/payload: '$cmd' '$hex'") else controller.sendRaw(id, bytes, mode)
    }

    private suspend fun decodeAndPlay() {
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { controller.decodeLast() } ?: return
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(result.wav.absolutePath)
            prepare()
            start()
        }
    }
}

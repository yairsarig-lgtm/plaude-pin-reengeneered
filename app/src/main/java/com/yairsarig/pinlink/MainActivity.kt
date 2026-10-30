package com.yairsarig.pinlink

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yairsarig.pinlink.ble.LinkState
import com.yairsarig.pinlink.ble.WriteMode
import com.yairsarig.pinlink.claude.ClaudeTrigger
import com.yairsarig.pinlink.claude.FireResult
import com.yairsarig.pinlink.housekeeping.DeviceSetup
import com.yairsarig.pinlink.protocol.hexToBytes
import com.yairsarig.pinlink.transcribe.ModelManager
import com.yairsarig.pinlink.ui.HomeScreen
import com.yairsarig.pinlink.ui.IdeasViewModel
import com.yairsarig.pinlink.ui.StatusInputs
import com.yairsarig.pinlink.ui.StatusModel
import com.yairsarig.pinlink.ui.PocketTheme
import com.yairsarig.pinlink.ui.SettingsActions
import com.yairsarig.pinlink.ui.SettingsScreen
import com.yairsarig.pinlink.ui.SettingsUi
import com.yairsarig.pinlink.upload.DriveAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Pocket Ideas: the ideas list, with everything else behind ⚙️. Also hosts permission and Drive-consent flows. */
class MainActivity : ComponentActivity() {
    private val controller by lazy { (application as PinLinkApp).controller }
    private val sendNow by lazy { (application as PinLinkApp).sendNow }
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
        if (hasPermissions()) onPermissionsReady()
    }

    private fun onPermissionsReady() {
        PinLinkService.start(this) // the service auto-connects
    }

    private val setup by lazy { DeviceSetup(this) }
    private val setupSteps = mutableStateOf<List<DeviceSetup.Step>>(emptyList())
    /** Bumped whenever Drive access is (re)granted, so the ideas list reloads. */
    private val driveGrants = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        setupSteps.value = setup.pendingSteps() // re-check after returning from a settings screen
        controller.wake() // opening the app ends any backoff wait
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** The "reconnect Drive" notification opens us with EXTRA_CONNECT_DRIVE. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifications.EXTRA_CONNECT_DRIVE, false) == true) {
            intent.removeExtra(Notifications.EXTRA_CONNECT_DRIVE)
            connectDrive(interactive = true)
        }
    }

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val outcome = runCatching { DriveAuth.fromConsentResult(this, r.data) }
        DriveAuth.setConnected(outcome.getOrNull() is DriveAuth.Outcome.Token)
        PinLog.i(if (DriveAuth.connected.value == true) "Drive connected" else "Drive consent not granted: ${outcome.exceptionOrNull()?.message ?: "cancelled"}")
        if (DriveAuth.connected.value == true) {
            Notifications.clearDriveReconnect(this)
            Pipeline.resumePending(this)
            driveGrants.value++
        }
    }

    /** Checks Drive access; with [interactive] shows Google's consent screen if needed. */
    private fun connectDrive(interactive: Boolean) {
        DriveAuth.authorizeAsync(this) { result ->
            result.onSuccess { outcome ->
                when (outcome) {
                    is DriveAuth.Outcome.Token -> DriveAuth.setConnected(true)
                    is DriveAuth.Outcome.NeedsConsent -> {
                        DriveAuth.setConnected(false)
                        val pi = outcome.intent
                        if (interactive && pi != null) consentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    }
                }
            }.onFailure {
                DriveAuth.setConnected(false)
                PinLog.e("Drive authorization failed: ${it.message}")
            }
        }
    }

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (hasPermissions()) onPermissionsReady() else permissionLauncher.launch(requiredPermissions)
        connectDrive(interactive = false)
        handleIntent(intent)
        setContent { PocketTheme { App() } }
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }

    @Composable
    private fun App() {
        val ideas: IdeasViewModel = viewModel()
        val ideasState by ideas.state.collectAsStateWithLifecycle()
        val link by controller.linkState.collectAsStateWithLifecycle()
        val transfer by controller.recording.collectAsStateWithLifecycle()
        val pinRecording by controller.pinRecording.collectAsStateWithLifecycle()
        val stored by controller.storedFiles.collectAsStateWithLifecycle()
        val battery by controller.battery.collectAsStateWithLifecycle()
        val driveConnected by DriveAuth.connected.collectAsStateWithLifecycle()
        val log by PinLog.lines.collectAsStateWithLifecycle()
        val grants by driveGrants
        val bluetoothOn by controller.bluetoothOn.collectAsStateWithLifecycle()
        val sendPhase by sendNow.phase.collectAsStateWithLifecycle()
        val claude by ClaudeTrigger.state.collectAsStateWithLifecycle()
        val paused by controller.paused.collectAsStateWithLifecycle()
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        var testResult by remember { mutableStateOf<String?>(null) }
        var showSettings by remember { mutableStateOf(false) }
        var pipeline by remember { mutableStateOf(Pipeline.Status(0, 0)) }
        val scope = rememberCoroutineScope()

        // Cheap file scan; drives the amber dot and the Transcription section.
        LaunchedEffect(Unit) {
            while (true) {
                pipeline = withContext(Dispatchers.IO) { Pipeline.status(this@MainActivity) }
                now = System.currentTimeMillis()
                delay(STATUS_POLL_MS)
            }
        }
        LaunchedEffect(grants) { if (grants > 0) ideas.refresh() }
        // Claude usually has filed the memos by the time the blue dot clears: show them.
        LaunchedEffect(claude.lastFireAt) {
            val wait = claude.lastFireAt + PinConfig.CLAUDE_PROCESSING_MS - System.currentTimeMillis()
            if (claude.lastFireAt == 0L || wait <= 0) return@LaunchedEffect
            delay(wait)
            now = System.currentTimeMillis()
            ideas.refresh()
        }

        val busy = pinRecording || transfer != null
        val status = StatusModel.of(StatusInputs(bluetoothOn, link, pinRecording, transfer != null, stored.size,
            pipeline, sendPhase, driveConnected, claude, now, paused))

        if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScreen(
                SettingsUi(link, battery, stored.size, busy, driveConnected, ideas.sheetId,
                    DeviceSetup.Step.entries.filter { it == DeviceSetup.Step.BATTERY_EXEMPTION || setup.isXiaomi }, setupSteps.value,
                    ModelManager(this).isReady, pipeline, Pipeline.lastUploadAt(this), controller.recordingsOnPhone(), log,
                    claude, ClaudeTrigger.routineUrl(), ClaudeTrigger.hasToken(), testResult, paused),
                SettingsActions(
                    back = { showSettings = false },
                    syncNow = controller::syncNow,
                    disconnect = { controller.disconnect() },
                    forget = controller::forgetPin,
                    reconnectDrive = { connectDrive(interactive = true) },
                    openSheet = { ideas.sheetId?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PinConfig.SHEET_URL_PREFIX + it))) } },
                    openSetup = setup::open,
                    confirmSetup = { setup.confirm(it); setupSteps.value = setup.pendingSteps() },
                    diagnose = { controller.runDiagnostics() },
                    play = { f -> scope.launch { play(f) } },
                    startLive = { controller.startLiveSession() },
                    stopLive = { controller.stopLiveSession() },
                    sendRaw = ::sendRaw,
                    saveRoutine = { url, token -> ClaudeTrigger.saveRoutine(url, token); testResult = null },
                    testRoutine = {
                        testResult = "Testing…"
                        scope.launch { testResult = describe(ClaudeTrigger.fireTest()) }
                    },
                    setAutoSend = ClaudeTrigger::setAuto,
                    setPaused = { if (it) controller.pause() else controller.resume() },
                ),
            )
        } else {
            HomeScreen(
                state = ideasState,
                status = status,
                messages = sendNow.messages,
                onSendNow = { if (!sendNow.start()) PinLog.i("Send now: already running, tap ignored") },
                onOpenSettings = { showSettings = true },
                onRefresh = ideas::refresh,
                onDelete = ideas::delete,
                onUndo = ideas::undo,
                onFilter = ideas::setFilter,
                onReconnectDrive = { connectDrive(interactive = true) },
            )
        }
    }

    private fun describe(r: FireResult): String = when (r) {
        is FireResult.Ok -> "Fired OK — the routine is running" + (r.sessionUrl?.let { "\n$it" } ?: "")
        is FireResult.RateLimited -> "Rate limited by Claude (hourly limit)" + (r.retryAfterSec?.let { "; resets in ${it / 60} min" } ?: "")
        is FireResult.Rejected -> "Rejected (${r.code}): ${r.message}"
        is FireResult.Failed -> "Couldn't reach Claude: ${r.message}"
        FireResult.NotConfigured -> "Enter an https:// routine URL and a token, then Save"
        FireResult.Capped -> "Skipped: already ${PinConfig.MAX_FIRES_PER_HOUR} fires this hour"
    }

    private fun sendRaw(cmd: String, hex: String, mode: WriteMode) {
        val id = cmd.trim().let { if (it.startsWith("0x")) it.drop(2).toIntOrNull(16) else it.toIntOrNull() }
        val bytes = runCatching { hex.hexToBytes() }.getOrNull()
        if (id == null || bytes == null) PinLog.w("Bad cmd/payload: '$cmd' '$hex'") else controller.sendRaw(id, bytes, mode)
    }

    private suspend fun play(capture: File) {
        val result = withContext(Dispatchers.IO) { controller.decode(capture) } ?: return
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(result.wav.absolutePath)
            prepare()
            start()
        }
    }

    private companion object {
        const val STATUS_POLL_MS = 3_000L
    }
}

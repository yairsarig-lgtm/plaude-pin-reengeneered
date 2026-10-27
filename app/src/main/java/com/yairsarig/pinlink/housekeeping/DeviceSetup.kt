package com.yairsarig.pinlink.housekeeping

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.yairsarig.pinlink.PinConfig
import com.yairsarig.pinlink.PinLog

/**
 * One-time phone settings that keep PinLink alive in the background. Android's battery
 * exemption can be checked; Xiaomi's Autostart and per-app battery saver can't be read by apps, so
 * the owner confirms those by hand and we remember it.
 */
class DeviceSetup(private val context: Context) {

    enum class Step(val title: String, val why: String) {
        BATTERY_EXEMPTION("Ignore battery optimization", "Lets the link and uploads run with the screen off."),
        XIAOMI_AUTOSTART("Xiaomi: enable Autostart", "HyperOS blocks the restart after a reboot without it."),
        XIAOMI_NO_RESTRICTIONS("Xiaomi: Battery saver → No restrictions", "HyperOS kills background apps otherwise."),
    }

    val isXiaomi: Boolean = Build.MANUFACTURER.lowercase() in PinConfig.XIAOMI_MANUFACTURERS

    private val prefs = context.getSharedPreferences(PinConfig.PREFS_NAME, Context.MODE_PRIVATE)

    fun pendingSteps(): List<Step> = Step.entries.filter { step ->
        when (step) {
            Step.BATTERY_EXEMPTION -> !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
            Step.XIAOMI_AUTOSTART, Step.XIAOMI_NO_RESTRICTIONS -> isXiaomi && !prefs.getBoolean(confirmKey(step), false)
        }
    }

    /** Owner says they've done a step we can't verify. */
    fun confirm(step: Step) = prefs.edit().putBoolean(confirmKey(step), true).apply()

    /** Opens the settings screen for [step], falling back to the app's details page. */
    @SuppressLint("BatteryLife") // Personal device; exemption is the whole point.
    fun open(step: Step) {
        val pkg = context.packageName
        val candidates = when (step) {
            Step.BATTERY_EXEMPTION -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
            Step.XIAOMI_AUTOSTART -> listOf(
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
            Step.XIAOMI_NO_RESTRICTIONS -> listOf(
                Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                    .putExtra("package_name", pkg).putExtra("package_label", "PinLink"))
        } + Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

        for (intent in candidates) {
            val ok = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (ok) return PinLog.i("Setup: opened ${intent.component?.className ?: intent.action} for $step")
        }
        PinLog.w("Setup: no settings screen could be opened for $step")
    }

    private fun confirmKey(step: Step) = "setup_confirmed_${step.name}"
}

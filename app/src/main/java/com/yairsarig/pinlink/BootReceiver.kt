package com.yairsarig.pinlink

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Restarts the link after a reboot or an app update. (On Xiaomi this only fires if Autostart is
 * enabled; DeviceSetup prompts for that.) Process start also re-queues pending pipeline work.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PinLog.i("Received ${intent.action}")
        val canConnect = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (!canConnect) return PinLog.w("Not starting: Bluetooth permission missing (open PinLink once)")
        PinLinkService.start(context)
    }
}

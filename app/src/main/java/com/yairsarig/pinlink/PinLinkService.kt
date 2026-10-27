package com.yairsarig.pinlink

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Keeps the process (and the BLE link) alive while the screen is off. */
class PinLinkService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notifications.service(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifications.ID_SERVICE, notification)
        }
        // The service owns the link: (re)started by the app, a reboot, an update, or START_STICKY.
        (application as PinLinkApp).controller.autoConnect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(context: Context) = context.startForegroundService(Intent(context, PinLinkService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, PinLinkService::class.java))
    }
}

package com.yairsarig.pinlink

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Keeps the process (and the BLE link) alive while the screen is off, and says what the link is doing. */
class PinLinkService : Service() {
    private val controller get() = (application as PinLinkApp).controller
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        val c = controller
        scope.launch {
            combine(c.paused, c.bluetoothOn, c.linkState, c.nextTryInMs) { paused, bt, link, next ->
                LinkNotice.of(paused, bt, link, next)
            }.distinctUntilChanged().collect { Notifications.updateService(this@PinLinkService, it) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = controller
        val notification = Notifications.service(this,
            LinkNotice.of(c.paused.value, c.bluetoothOn.value, c.linkState.value, c.nextTryInMs.value))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifications.ID_SERVICE, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifications.ID_SERVICE, notification)
        }
        when (intent?.action) {
            ACTION_PAUSE -> c.pause()
            ACTION_RESUME -> c.resume()
            // The service owns the link: (re)started by the app, a reboot, an update, or START_STICKY.
            else -> if (!c.paused.value) c.autoConnect()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PAUSE = "com.yairsarig.pinlink.PAUSE"
        const val ACTION_RESUME = "com.yairsarig.pinlink.RESUME"

        fun start(context: Context) = context.startForegroundService(Intent(context, PinLinkService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, PinLinkService::class.java))
    }
}

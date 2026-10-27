package com.yairsarig.pinlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** All notification channels and IDs in one place. */
object Notifications {
    private const val CHANNEL_LINK = "pinlink"
    private const val CHANNEL_ALERTS = "alerts"
    const val ID_SERVICE = 1
    private const val ID_DRIVE = 2

    /** Extra on MainActivity's intent: open Google's Drive consent screen right away. */
    const val EXTRA_CONNECT_DRIVE = "connect_drive"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LINK, "Pin link", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Needs attention", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun service(context: Context): Notification =
        Notification.Builder(context, CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(PinConfig.APP_NAME)
            .setContentText("Listening to the pin")
            .setContentIntent(openApp(context, connectDrive = false))
            .setOngoing(true)
            .build()

    /** Uploads are stuck until the owner re-grants Drive access; tapping opens the consent screen. */
    fun driveReconnect(context: Context) {
        val n = Notification.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("${PinConfig.APP_NAME} can't upload to Drive")
            .setContentText("Tap to reconnect Google Drive. Memos are kept until then.")
            .setContentIntent(openApp(context, connectDrive = true))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ID_DRIVE, n)
    }

    fun clearDriveReconnect(context: Context) =
        context.getSystemService(NotificationManager::class.java).cancel(ID_DRIVE)

    private fun openApp(context: Context, connectDrive: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CONNECT_DRIVE, connectDrive)
        return PendingIntent.getActivity(context, if (connectDrive) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

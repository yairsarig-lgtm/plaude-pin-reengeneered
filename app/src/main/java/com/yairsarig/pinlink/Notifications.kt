package com.yairsarig.pinlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

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

    /** The persistent notification: what the link is doing, a Pause/Resume button. Paused, a tap resumes. */
    fun service(context: Context, notice: LinkNotice): Notification {
        val toggle = serviceAction(context, if (notice.paused) PinLinkService.ACTION_RESUME else PinLinkService.ACTION_PAUSE)
        return Notification.Builder(context, CHANNEL_LINK)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(PinConfig.APP_NAME)
            .setContentText(notice.text)
            .setContentIntent(if (notice.paused) toggle else openApp(context, connectDrive = false))
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(context, if (notice.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause),
                if (notice.paused) "Resume" else "Pause", toggle).build())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    fun updateService(context: Context, notice: LinkNotice) =
        context.getSystemService(NotificationManager::class.java).notify(ID_SERVICE, service(context, notice))

    private fun serviceAction(context: Context, action: String): PendingIntent =
        PendingIntent.getForegroundService(context, action.hashCode(),
            Intent(context, PinLinkService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

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

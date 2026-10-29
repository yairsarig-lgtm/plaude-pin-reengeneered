package com.yairsarig.pinlink

import android.app.Application
import com.yairsarig.pinlink.claude.ClaudeTrigger
import com.yairsarig.pinlink.housekeeping.CleanupWorker
import java.io.File

class PinLinkApp : Application() {
    lateinit var controller: PinController
        private set
    lateinit var sendNow: SendNow
        private set

    override fun onCreate() {
        super.onCreate()
        PinLog.init(File(getExternalFilesDir(null), PinConfig.DIR_LOGS))
        controller = PinController(this)
        ClaudeTrigger.init(this)
        sendNow = SendNow(this, controller)
        PinLog.i("PinLink started")
        Notifications.createChannels(this)
        Pipeline.resumePending(this)
        CleanupWorker.schedule(this)
    }
}

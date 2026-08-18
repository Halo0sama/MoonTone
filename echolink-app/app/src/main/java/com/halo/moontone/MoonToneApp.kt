package com.halo.moontone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.halo.moontone.control.DebugControlServer
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.log.MoonToneLog

class MoonToneApp : Application() {
    companion object {
        const val CHANNEL_PLAYBACK = "moontone_playback"
        const val NOTIFICATION_PLAYING = 1
    }

    lateinit var controller: MoonToneController
        private set

    private lateinit var controlServer: DebugControlServer

    override fun onCreate() {
        super.onCreate()
        MoonToneLog.init(filesDir)
        MoonToneLog.installCrashHandler()
        MoonToneLog.i("App", "MoonTone starting, version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
        createNotificationChannels()

        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            ""
        }

        // Worker processes only run one Moonlight connection; they must not
        // start the main controller or the debug TCP server (port conflict).
        if (processName?.contains(":conn") != true) {
            controller = MoonToneController(this)
            controlServer = DebugControlServer(controller)
            controlServer.start()
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_PLAYBACK,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when MoonTone is streaming audio"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }
}

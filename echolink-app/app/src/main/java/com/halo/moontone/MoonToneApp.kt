package com.halo.moontone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.halo.moontone.control.DebugControlServer
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.log.MoonToneLog
import com.halo.moontone.multi.AndroidMultiStreamingRuntime
import com.halo.moontone.multi.MultiController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MoonToneApp : Application() {
    companion object {
        const val CHANNEL_PLAYBACK = "moontone_playback"
        const val NOTIFICATION_PLAYING = 1
    }

    lateinit var controller: MoonToneController
        private set

    val multiController: MultiController by lazy {
        MultiController(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            AndroidMultiStreamingRuntime(this, controller.crypto),
            android.os.SystemClock::elapsedRealtime
        )
    }

    private lateinit var controlServer: DebugControlServer

    private val playbackOwners = mutableSetOf<String>()

    @Synchronized
    fun startPlayback(owner: String) {
        if (owner in playbackOwners) return
        androidx.core.content.ContextCompat.startForegroundService(
            this, android.content.Intent(this, com.halo.moontone.audio.MoonToneAudioService::class.java)
        )
        playbackOwners.add(owner)
    }

    @Synchronized
    fun stopPlayback(owner: String) {
        playbackOwners.remove(owner)
        if (playbackOwners.isEmpty()) {
            stopService(android.content.Intent(this, com.halo.moontone.audio.MoonToneAudioService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            // getProcessName() was introduced in API 28. Workers on API 24-27
            // still need to skip the main controller and debug TCP listener.
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
                ?: java.io.File("/proc/self/cmdline").readText().substringBefore('\u0000')
        }

        MoonToneLog.init(filesDir, processName?.substringAfter(':', "") ?: "")
        MoonToneLog.installCrashHandler()
        MoonToneLog.i("App", "MoonTone starting, version=${BuildConfig.VERSION_NAME} debug=${BuildConfig.DEBUG}")
        createNotificationChannels()


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

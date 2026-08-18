package com.halo.moontone.audio

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.halo.moontone.MoonToneApp
import com.halo.moontone.ui.MainActivity

class MoonToneAudioService : Service() {

    inner class LocalBinder : Binder() {
        val service: MoonToneAudioService get() = this@MoonToneAudioService
    }

    private val binder = LocalBinder()
    var isStreaming = false

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startStreamingNotification()
                isStreaming = true
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                isStreaming = false
            }
            else -> {
                startStreamingNotification()
                isStreaming = true
            }
        }
        return START_STICKY
    }

    private fun startStreamingNotification() {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MoonToneAudioService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, MoonToneApp.CHANNEL_PLAYBACK)
            .setContentTitle("MoonTone")
            .setContentText("Streaming audio from PC")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Disconnect", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(MoonToneApp.NOTIFICATION_PLAYING, notification)
    }

    override fun onDestroy() {
        isStreaming = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.halo.moontone.action.START_STREAMING"
        const val ACTION_STOP = "com.halo.moontone.action.STOP_STREAMING"
    }
}

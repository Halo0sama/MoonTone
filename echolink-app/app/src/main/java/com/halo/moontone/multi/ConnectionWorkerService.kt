package com.halo.moontone.multi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import com.halo.moontone.R
import com.halo.moontone.connection.MoonToneConnection
import com.halo.moontone.log.MoonToneLog
import kotlin.concurrent.thread

/**
 * Runs one Moonlight connection inside its own Android process.
 *
 * The main process performs pairing/launch, then starts this service with the
 * session parameters. This service connects back to the main process over a
 * LocalSocket and streams decoded PCM frames to [SocketAudioRenderer].
 */
open class ConnectionWorkerService : Service() {

    private var connection: MoonToneConnection? = null
    private var socket: LocalSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val host = intent.getStringExtra(EXTRA_HOST) ?: run { stopSelf(); return START_NOT_STICKY }
        val rtspUrl = intent.getStringExtra(EXTRA_RTSP_URL) ?: run { stopSelf(); return START_NOT_STICKY }
        val appVersion = intent.getStringExtra(EXTRA_APP_VERSION) ?: "7.0.0"
        val gfeVersion = intent.getStringExtra(EXTRA_GFE_VERSION)
        val codecMode = intent.getIntExtra(EXTRA_CODEC_MODE, 0)
        val audioConfig = intent.getIntExtra(EXTRA_AUDIO_CONFIG, 0)
        val riKey = intent.getByteArrayExtra(EXTRA_RI_KEY) ?: ByteArray(16)
        val riKeyId = intent.getByteArrayExtra(EXTRA_RI_KEY_ID) ?: ByteArray(16)
        val socketName = intent.getStringExtra(EXTRA_SOCKET_NAME) ?: run { stopSelf(); return START_NOT_STICKY }

        thread {
            try {
                val local = LocalSocket()
                local.connect(LocalSocketAddress(socketName))
                socket = local

                val renderer = SocketAudioRenderer(local.outputStream)
                val conn = MoonToneConnection(applicationContext)
                conn.setAudioRenderer(renderer)
                connection = conn
                conn.connect(
                    address = host,
                    appVersion = appVersion,
                    gfeVersion = gfeVersion,
                    rtspSessionUrl = rtspUrl,
                    serverCodecModeSupport = codecMode,
                    audioConfiguration = audioConfig,
                    riAesKey = riKey,
                    riAesIv = riKeyId
                )
            } catch (e: Exception) {
                MoonToneLog.e("Worker", "worker failed", e)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            connection?.disconnect()
        } catch (e: Exception) {
        }
        connection = null
        try {
            socket?.close()
        } catch (e: Exception) {
        }
        socket = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val channelId = "moontone_worker"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "MoonTone 串流进程", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification: Notification = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("MoonTone 串流")
                .setContentText("正在为多设备混音提供音频")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("MoonTone 串流")
                .setContentText("正在为多设备混音提供音频")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build()
        }
        startForeground(1001, notification)
    }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_RTSP_URL = "rtspUrl"
        const val EXTRA_APP_VERSION = "appVersion"
        const val EXTRA_GFE_VERSION = "gfeVersion"
        const val EXTRA_CODEC_MODE = "codecMode"
        const val EXTRA_AUDIO_CONFIG = "audioConfig"
        const val EXTRA_RI_KEY = "riKey"
        const val EXTRA_RI_KEY_ID = "riKeyId"
        const val EXTRA_SOCKET_NAME = "socketName"
    }
}

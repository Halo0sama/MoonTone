package com.halo.moontone.audio

import android.Manifest
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.halo.moontone.MoonToneApp
import com.halo.moontone.log.MoonToneLog
import com.halo.moontone.ui.MainActivity
import kotlinx.coroutines.*

/** Owns microphone access under the Android microphone foreground-service type. */
class MoonToneMicrophoneService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var capture: MoonToneMicCapture? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MoonToneApp
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra("host")
        if (host.isNullOrBlank() || ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, MoonToneApp.CHANNEL_PLAYBACK)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("MoonTone 麦克风")
                .setContentText("正在向 $host 回传麦克风")
                .setContentIntent(open)
                .addAction(android.R.drawable.ic_media_pause, "停止麦克风", stop)
                .setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 30) startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(2, notification)
            capture?.stop()
            capture = MoonToneMicCapture(this, host, intent.getIntExtra("port", 48100))
            if (capture?.start() != true) { stopSelf(); return START_NOT_STICKY }
            app.controller.micRunning.value = true
            scope.coroutineContext.cancelChildren()
            scope.launch {
                while (capture?.isRunning == true) delay(500)
                stopSelf()
            }
        } catch (e: Exception) {
            MoonToneLog.e("MicService", "cannot start microphone", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        capture?.stop()
        capture = null
        (application as MoonToneApp).controller.micRunning.value = false
        super.onDestroy()
    }

    companion object { const val ACTION_STOP = "com.halo.moontone.STOP_MICROPHONE" }
}

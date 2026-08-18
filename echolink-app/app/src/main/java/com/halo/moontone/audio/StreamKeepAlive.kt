package com.halo.moontone.audio

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.halo.moontone.log.MoonToneLog

/**
 * Keeps the CPU and WiFi awake while a MoonTone stream is active.
 *
 * Without this, MIUI/stock Android may suspend WiFi or the app process after
 * the screen turns off, which makes the UDP audio stream appear to
 * "automatically disconnect after a while".
 */
class StreamKeepAlive(private val app: Context) {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun acquire() {
        release()
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MoonTone:Stream")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Exception) {
            MoonToneLog.w("KeepAlive", "wake lock failed: ${e.message}")
        }

        try {
            val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MoonTone:Stream")
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        } catch (e: Exception) {
            MoonToneLog.w("KeepAlive", "wifi lock failed: ${e.message}")
        }

        MoonToneLog.i("KeepAlive", "acquired")
    }

    fun release() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            MoonToneLog.w("KeepAlive", "wake lock release failed: ${e.message}")
        }
        wakeLock = null

        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            MoonToneLog.w("KeepAlive", "wifi lock release failed: ${e.message}")
        }
        wifiLock = null

        MoonToneLog.i("KeepAlive", "released")
    }
}

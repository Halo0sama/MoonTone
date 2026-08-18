package com.halo.moontone.audio

import android.content.Context
import android.net.wifi.WifiManager
import com.halo.moontone.log.MoonToneLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Samples the local network environment (WiFi RSSI/link speed) once per second.
 *
 * The quality score is a 0-100 estimate used by [AdaptiveAudioController]:
 * 100 = excellent/stable, 0 = very poor. If WiFi info is unavailable (e.g.
 * location permission missing), it stays at a conservative default so the
 * adaptive logic still relies on jitter/underrun signals.
 */
class EnvironmentMonitor(private val app: Context) {

    @Volatile
    var rssiDbm: Int = -60
        private set

    @Volatile
    var linkSpeedMbps: Int = 0
        private set

    @Volatile
    var networkQualityPercent: Int = 80
        private set

    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        job = scope.launch {
            while (isActive) {
                update()
                delay(1000)
            }
        }
        MoonToneLog.i("EnvMonitor", "started")
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun update() {
        try {
            val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val info = wifi.connectionInfo ?: return

            rssiDbm = info.rssi
            linkSpeedMbps = info.linkSpeed

            // Convert RSSI (-100..-50) to 0..100 quality.
            val rssiQuality = ((rssiDbm + 100) * 2).coerceIn(0, 100)
            // Link speed (Mbps) contributes a mild cap for very slow links.
            val speedQuality = (linkSpeedMbps * 2).coerceIn(0, 100)
            networkQualityPercent = ((rssiQuality * 0.7) + (speedQuality * 0.3)).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            // No location permission / WiFi disabled: keep the conservative default.
            MoonToneLog.d("EnvMonitor", "wifi info unavailable: ${e.message}")
        }
    }
}

package com.halo.moontone.data

import android.content.Context

/**
 * Per-host session capability cache.
 *
 * Audio-only sessions require the MoonTone-patched Sunshine build. Stock
 * Sunshine always runs a video pipeline and kills an audio-only session with
 * "Initial Ping Timeout" ~10s after CONNECTED. We detect that signature once,
 * remember the host, and use the dummy-video fallback from then on.
 *
 * Capabilities are stored under both the host address and the host's stable
 * Sunshine uniqueid (from /serverinfo), because LAN IPs change across DHCP
 * renewals and we must not re-learn (10s of dead audio) on every change.
 *
 * Default is optimistic (true) so patched hosts get zero-overhead sessions
 * without any probing; official hosts pay the fallback cost exactly once.
 */
object HostCapabilities {
    private const val PREFS = "moontone_host_caps"
    private const val KEY_IP = "audioOnly:"
    private const val KEY_UID = "audioOnlyUid:"

    fun audioOnly(context: Context, host: String, uniqueId: String? = null): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (uniqueId != null && prefs.contains(KEY_UID + uniqueId)) {
            return prefs.getBoolean(KEY_UID + uniqueId, true)
        }
        return prefs.getBoolean(KEY_IP + host, true)
    }

    fun setAudioOnly(context: Context, host: String, uniqueId: String?, supported: Boolean) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        editor.putBoolean(KEY_IP + host, supported)
        if (uniqueId != null) editor.putBoolean(KEY_UID + uniqueId, supported)
        editor.apply()
    }
}

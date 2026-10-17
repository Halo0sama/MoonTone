package com.halo.moontone.data

import android.content.Context
import android.content.SharedPreferences

/** Local speaker preference, independent of the phone's mixer mute/volume. */
object HostAudioPreferences {
    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("moontone_host_audio", Context.MODE_PRIVATE)

    fun overrideForHost(context: Context, host: String): Boolean? {
        val prefs = preferences(context)
        val key = host.trim()
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun playOnHost(context: Context, host: String): Boolean =
        overrideForHost(context, host) ?: AppPreferences.playOnHostByDefault(context)

    fun followDefault(context: Context, host: String) {
        preferences(context).edit().remove(host.trim()).apply()
    }

    fun setPlayOnHost(context: Context, host: String, enabled: Boolean) {
        require(host.isNotBlank())
        preferences(context).edit().putBoolean(host.trim(), enabled).apply()
    }
}

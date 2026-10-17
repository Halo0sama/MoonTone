package com.halo.moontone.data

import android.content.Context
import android.content.SharedPreferences

/** Application defaults; per-host overrides remain in their original store. */
object AppPreferences {
    private const val HOST_AUDIO = "host_audio_default"
    private const val CHECK_UPDATES = "check_updates_on_launch"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("moontone_settings", Context.MODE_PRIVATE)

    fun playOnHostByDefault(context: Context) = preferences(context).getBoolean(HOST_AUDIO, false)
    fun setPlayOnHostByDefault(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(HOST_AUDIO, enabled).apply()
    }

    fun checkUpdatesOnLaunch(context: Context) = preferences(context).getBoolean(CHECK_UPDATES, true)
    fun setCheckUpdatesOnLaunch(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(CHECK_UPDATES, enabled).apply()
    }
}

package com.halo.moontone.data

import android.content.Context
import android.content.SharedPreferences

/** Application defaults; per-host overrides remain in their original store. */
object AppPreferences {
    private const val HOST_AUDIO = "host_audio_default"
    private const val CHECK_UPDATES = "check_updates_on_launch"

    /** Appearance settings (Material 3 Expressive redesign). */
    internal const val KEY_THEME_MODE = "theme_mode"
    internal const val KEY_DYNAMIC_COLOR = "dynamic_color"

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

    /** "system" / "light" / "dark" — parsed by com.halo.moontone.ui.theme.ThemeMode. */
    fun themeMode(context: Context): String =
        preferences(context).getString(KEY_THEME_MODE, "system") ?: "system"

    fun setThemeMode(context: Context, storageValue: String) {
        preferences(context).edit().putString(KEY_THEME_MODE, storageValue).apply()
    }

    /** Follow the wallpaper's Material You palette instead of the MoonTone brand palette. */
    fun dynamicColor(context: Context) = preferences(context).getBoolean(KEY_DYNAMIC_COLOR, false)
    fun setDynamicColor(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
    }
}

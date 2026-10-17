package com.halo.moontone.data

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halo.moontone.ui.MainActivity
import com.halo.moontone.ui.UiActions
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostAudioPreferencesTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext

    @Test fun defaultsAndChangesAreIndependentForEachHostAndPersistAcrossReads() {
        val name = "audio-options-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String, mode: Int) = super.getSharedPreferences(name, mode)
        }
        try {
            assertFalse(HostAudioPreferences.playOnHost(context, "pc-a"))
            HostAudioPreferences.setPlayOnHost(context, " pc-a ", true)
            assertTrue(HostAudioPreferences.playOnHost(context, "pc-a"))
            assertFalse(HostAudioPreferences.playOnHost(context, "pc-b"))
            HostAudioPreferences.setPlayOnHost(context, "pc-a", false)
            assertFalse(HostAudioPreferences.playOnHost(context, "pc-a"))
            assertThrows(IllegalArgumentException::class.java) { HostAudioPreferences.setPlayOnHost(context, " ", true) }
        } finally { app.deleteSharedPreferences(name) }
    }

    @Test fun settingsDefaultAndExistingDeviceChoicesSurviveActivityRecreation() {
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        if (Build.VERSION.SDK_INT >= 37) shell("pm grant ${app.packageName} ${Manifest.permission.ACCESS_LOCAL_NETWORK}")
        val host = "127.0.0.25"
        val oldDefault = AppPreferences.playOnHostByDefault(app)
        val oldCheckUpdates = AppPreferences.checkUpdatesOnLaunch(app)
        SavedHosts.add(app, "Audio options test", host)
        // This is the on-disk format written by the previous APK.
        HostAudioPreferences.preferences(app).edit().putBoolean(host, false).commit()
        AppPreferences.setPlayOnHostByDefault(app, false)
        AppPreferences.setCheckUpdatesOnLaunch(app, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                UiActions.waitText("MoonTone")
                assertTrue(UiActions.visibleNodes { it.text?.toString() == "主机同时播放声音" }.isEmpty())
                UiActions.clickDescription("打开设置")
                UiActions.waitToggle("主机同时播放声音", false)
                UiActions.clickText("主机同时播放声音")
                UiActions.waitToggle("主机同时播放声音", true)
                assertTrue(AppPreferences.playOnHostByDefault(app))
                assertFalse("An existing per-host choice must override the new default", HostAudioPreferences.playOnHost(app, host))
                UiActions.clickText("设备声音例外")
                UiActions.waitText("设备声音")
                UiActions.clickText("Audio options test")
                UiActions.clickText("主机与手机同时播放")
                assertTrue(HostAudioPreferences.playOnHost(app, host))
                scenario.recreate()
                UiActions.waitText("设备声音")
                UiActions.clickText("Audio options test")
                UiActions.clickText("只在手机播放")
                assertFalse(HostAudioPreferences.playOnHost(app, host))
                UiActions.clickDescription("返回")
                UiActions.waitToggle("主机同时播放声音", true)
                UiActions.clickDescription("返回")
                UiActions.waitText("MoonTone")
                assertTrue(UiActions.visibleNodes { it.text?.toString() == "主机同时播放声音" }.isEmpty())
            }
        } finally {
            SavedHosts.remove(app, host)
            HostAudioPreferences.preferences(app).edit().remove(host).commit()
            AppPreferences.setPlayOnHostByDefault(app, oldDefault)
            AppPreferences.setCheckUpdatesOnLaunch(app, oldCheckUpdates)
        }
    }

    @Test fun globalDefaultCanBeOverriddenAndRestoredWithoutMigratingExistingKeys() {
        val name = "audio-defaults-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String, mode: Int) = super.getSharedPreferences(name, mode)
        }
        try {
            context.getSharedPreferences("moontone_host_audio", 0).edit().putBoolean("legacy", false).commit()
            AppPreferences.setPlayOnHostByDefault(context, true)
            assertFalse(HostAudioPreferences.playOnHost(context, "legacy"))
            assertTrue(HostAudioPreferences.playOnHost(context, "new-host"))
            HostAudioPreferences.followDefault(context, "legacy")
            assertTrue(HostAudioPreferences.playOnHost(context, "legacy"))
            HostAudioPreferences.setPlayOnHost(context, "new-host", false)
            assertFalse(HostAudioPreferences.playOnHost(context, "new-host"))
            AppPreferences.setPlayOnHostByDefault(context, false)
            assertFalse(HostAudioPreferences.playOnHost(context, "legacy"))
        } finally { app.deleteSharedPreferences(name) }
    }
}

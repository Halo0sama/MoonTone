package com.halo.moontone.data

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halo.moontone.ui.MainActivity
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

    @Test fun deviceSwitchPersistsAcrossActivityRecreationAndCanBeTurnedOff() {
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        }
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${app.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        if (Build.VERSION.SDK_INT >= 37) shell("pm grant ${app.packageName} ${Manifest.permission.ACCESS_LOCAL_NETWORK}")
        val host = "127.0.0.25"
        SavedHosts.add(app, "Audio options test", host)
        HostAudioPreferences.setPlayOnHost(app, host, false)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertTrue(waitForSwitch(host, false).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitForSwitch(host, true)
                assertTrue(HostAudioPreferences.playOnHost(app, host))
                scenario.recreate()
                assertTrue(waitForSwitch(host, true).performAction(AccessibilityNodeInfo.ACTION_CLICK))
                waitForSwitch(host, false)
                assertFalse(HostAudioPreferences.playOnHost(app, host))
            }
        } finally {
            SavedHosts.remove(app, host)
            HostAudioPreferences.preferences(app).edit().remove(host).commit()
        }
    }

    private fun waitForSwitch(host: String, checked: Boolean): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == "主机同时播放声音：$host" && node.isVisibleToUser) {
                // Material's Switch exposes the description on an inner
                // semantics node; its nearest checkable ancestor owns clicks.
                var control: AccessibilityNodeInfo = node
                while (!control.isCheckable) control = control.parent ?: break
                if (control.isCheckable && control.isChecked == checked) return control
            }
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        repeat(40) {
            find(instrumentation.uiAutomation.rootInActiveWindow)?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("Host audio switch did not reach checked=$checked")
    }
}

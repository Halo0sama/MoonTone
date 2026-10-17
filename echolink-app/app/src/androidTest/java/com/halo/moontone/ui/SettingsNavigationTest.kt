package com.halo.moontone.ui

import android.Manifest
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.halo.moontone.MoonToneApp
import com.halo.moontone.data.AppPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsNavigationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as MoonToneApp
    private var oldUpdates = true

    @Before fun prepare() {
        oldUpdates = AppPreferences.checkUpdatesOnLaunch(app)
        AppPreferences.setCheckUpdatesOnLaunch(app, false)
        fun grant(permission: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${app.packageName} $permission")).use { it.readBytes() }
        }
        if (Build.VERSION.SDK_INT >= 33) grant(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37) grant(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    @After fun restore() { AppPreferences.setCheckUpdatesOnLaunch(app, oldUpdates) }

    @Test fun toolsLiveInSettingsAndBackReturnsToHomeWithoutStoppingTheSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val controller = app.multiController
            scenario.onActivity { controller.connect("127.0.0.77") }
            try {
                UiActions.clickDescription("打开设置")
                UiActions.waitText("设置")
                UiActions.clickText("高级连接")
                UiActions.waitText("连接手动会话")
                assertEquals(2, UiActions.visibleNodes { it.isPassword && it.isEditable }.size)
                UiActions.clickDescription("返回")
                UiActions.waitText("设置")
                UiActions.clickText("运行日志")
                UiActions.waitText("刷新日志")
                UiActions.clickDescription("返回")
                UiActions.waitText("设置")
                scenario.recreate()
                UiActions.waitText("设置")
                UiActions.clickDescription("返回")
                UiActions.waitText("MoonTone")
                scenario.onActivity {
                    assertSame(controller, app.multiController)
                    assertTrue(controller.devices.value.any { it.host == "127.0.0.77" })
                }
                assertTrue(UiActions.visibleNodes { it.text?.toString() in setOf("主机同时播放声音", "显示日志", "更多", "导出客户端证书") }.isEmpty())
            } finally { scenario.onActivity { controller.stopAll() } }
        }
    }
}

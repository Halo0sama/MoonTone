package com.halo.moontone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.halo.moontone.multi.MultiConnState
import com.halo.moontone.multi.MultiDeviceState
import com.halo.moontone.ui.components.MoonToneSectionHeader
import com.halo.moontone.ui.theme.MoonToneTheme

/**
 * Debug-only screen that renders the Home state matrix (connected / muted / mixing / pairing /
 * device cards) with fake data, so the connected UI can be screenshot-verified on the emulator
 * without a real Sunshine host. Launch with:
 *
 *   adb shell am start -n com.halo.moontone/.ui.DesignPreviewActivity
 *
 * Not included in release builds.
 */
class DesignPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MoonToneTheme {
                Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                    DesignPreviewContent()
                }
            }
        }
    }
}

@Composable
private fun DesignPreviewContent() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MoonToneSectionHeader("单设备已连接")
        ConnectedHero(
            name = "客厅主机 · MacBook Pro",
            host = "192.168.31.174",
            muted = false,
            micRunning = true,
            onToggleMute = {},
            onToggleMic = {},
            onDisconnect = {},
        )

        MoonToneSectionHeader("单设备已静音")
        ConnectedHero(
            name = "客厅主机 · MacBook Pro",
            host = "192.168.31.174",
            muted = true,
            micRunning = false,
            onToggleMute = {},
            onToggleMic = {},
            onDisconnect = {},
        )

        MoonToneSectionHeader("多设备混音")
        MixingHero(connectedCount = 3, onStopAll = {})

        MoonToneSectionHeader("配对 PIN")
        ConnectingHero(
            stage = "准备配对",
            pairingText = "在 Sunshine 网页输入 PIN 完成配对",
            pin = "4821",
            host = "192.168.31.174",
            onCancel = {},
        )

        MoonToneSectionHeader("设备卡片状态")
        DeviceRowCard(
            device = DeviceEntry("客厅主机 · MacBook Pro", "192.168.31.174", true, true),
            multi = MultiDeviceState(
                slot = 0,
                host = "192.168.31.174",
                state = MultiConnState.CONNECTED,
                muted = false,
                volume = 0.65f,
            ),
            current = false,
            singleMuted = false,
            mixMode = true,
            canConnect = true,
            onConnect = {},
            onDisconnect = {},
            onMute = {},
            onVolume = {},
            onSave = {},
        )
        DeviceRowCard(
            device = DeviceEntry("书房 PC", "192.168.31.20", true, true),
            multi = MultiDeviceState(
                slot = 1,
                host = "192.168.31.20",
                state = MultiConnState.CONNECTING,
            ),
            current = false,
            singleMuted = false,
            mixMode = true,
            canConnect = true,
            onConnect = {},
            onDisconnect = {},
            onMute = {},
            onVolume = {},
            onSave = {},
        )
        DeviceRowCard(
            device = DeviceEntry("卧室小主机", "192.168.31.33", true, false),
            multi = MultiDeviceState(
                slot = 2,
                host = "192.168.31.33",
                state = MultiConnState.ERROR,
                error = "failed to connect after 5000ms",
            ),
            current = false,
            singleMuted = false,
            mixMode = true,
            canConnect = true,
            onConnect = {},
            onDisconnect = {},
            onMute = {},
            onVolume = {},
            onSave = {},
        )
        DeviceRowCard(
            device = DeviceEntry("电脑示例", "198.51.100.10", true, false),
            multi = null,
            current = false,
            singleMuted = false,
            mixMode = false,
            canConnect = true,
            onConnect = {},
            onDisconnect = {},
            onMute = {},
            onVolume = {},
            onSave = {},
        )
        Text("MoonTone design preview · debug only", style = MaterialTheme.typography.bodySmall)
    }
}

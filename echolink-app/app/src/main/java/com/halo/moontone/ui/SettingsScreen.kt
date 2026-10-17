package com.halo.moontone.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.halo.moontone.BuildConfig
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.data.AppPreferences
import com.halo.moontone.data.HostAudioPreferences
import com.halo.moontone.data.SavedHost
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings contain preferences and tools; playback operations live on Home. */
@Composable
internal fun SettingsScreen(
    modifier: Modifier, activity: MainActivity, hosts: List<SavedHost>, localNetworkGranted: Boolean,
    micRunning: Boolean, singleConnected: Boolean, currentHost: String,
    onMicChange: (Boolean) -> Unit, onNavigate: (String) -> Unit, controller: MoonToneController
) {
    val context = LocalContext.current
    val revision = preferenceRevision(context)
    val defaultHostAudio = remember(revision) { AppPreferences.playOnHostByDefault(context) }
    val checkUpdates = remember(revision) { AppPreferences.checkUpdatesOnLaunch(context) }
    val overrides = remember(revision, hosts) { HostAudioPreferences.preferences(context).all.size }
    val permissionRevision = activity.permissionRevision
    val notificationsGranted = remember(permissionRevision) { Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf(false) }
    Box(modifier) {
        LazyColumn(
            Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                SettingsGroup("播放") {
                    SettingsToggle("主机同时播放声音", "未单独设置的电脑使用此选项；连接或重连后生效。", defaultHostAudio) {
                        AppPreferences.setPlayOnHostByDefault(context, it)
                    }
                    HorizontalDivider()
                    SettingsAction("设备声音例外", if (overrides == 0) "所有电脑均跟随默认设置" else "$overrides 台电脑已单独设置") {
                        onNavigate(AppPage.HOST_AUDIO)
                    }
                    HorizontalDivider()
                    SettingsToggle(
                        "麦克风上行",
                        when {
                            micRunning -> if (currentHost.isBlank()) "正在回传手机麦克风" else "正在将手机麦克风发送到 $currentHost"
                            singleConnected -> "发送到 $currentHost；开启时申请麦克风权限。"
                            else -> "单设备连接后可开启，回传到当前电脑。"
                        }, micRunning, enabled = micRunning || singleConnected, onChange = onMicChange
                    )
                    HorizontalDivider()
                    SettingsAction("音频信息", "编码、缓冲策略与系统输出") { onNavigate(AppPage.AUDIO_INFO) }
                }
            }
            item {
                SettingsGroup("连接与权限") {
                    if (Build.VERSION.SDK_INT >= 37) {
                        SettingsAction("局域网访问", if (localNetworkGranted) "已允许" else "未允许，点击申请") {
                            if (localNetworkGranted) openAppSettings(context) else activity.requestLocalNetworkPermission()
                        }
                        HorizontalDivider()
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        SettingsAction("播放通知", if (notificationsGranted) "已允许" else "未允许，点击申请") {
                            if (notificationsGranted) openAppSettings(context) else activity.requestNotificationPermission()
                        }
                        HorizontalDivider()
                    }
                    SettingsAction("系统应用设置", "管理权限、通知和后台运行") { openAppSettings(context) }
                }
            }
            item {
                SettingsGroup("高级与诊断") {
                    SettingsAction("高级连接", "使用已有的 RTSP 会话地址与密钥") { onNavigate(AppPage.MANUAL) }
                    HorizontalDivider()
                    SettingsAction("运行日志", "查看最近的连接和音频记录") { onNavigate(AppPage.LOGS) }
                    HorizontalDivider()
                    SettingsAction(if (exporting) "正在导出证书" else "导出客户端证书", "导出公钥证书并复制到剪贴板，不包含私钥。", enabled = !exporting) {
                        exporting = true
                        scope.launch {
                            val message = try {
                                val exported = withContext(Dispatchers.IO) {
                                    controller.crypto.getClientCertificate()
                                    val pem = controller.crypto.getPemEncodedClientCertificate() ?: error("证书尚未就绪")
                                    val directory = context.getExternalFilesDir(null) ?: error("无法访问导出目录")
                                    val file = File(directory, "moontone_client.pem").apply { writeBytes(pem) }
                                    file.absolutePath to String(pem)
                                }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MoonTone 公钥证书", exported.second))
                                "证书已复制，文件已保存到 ${exported.first}"
                            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { e.message ?: "导出失败" }
                            finally { exporting = false }
                            snackbar.showSnackbar(message)
                        }
                    }
                }
            }
            item {
                SettingsGroup("应用") {
                    SettingsToggle("启动时检查更新", "有新版本时提示，不会自动下载安装。", checkUpdates) {
                        AppPreferences.setCheckUpdatesOnLaunch(context, it)
                    }
                    HorizontalDivider()
                    SettingsAction(if (activity.checkingUpdate) "正在检查更新" else "检查更新", "从 GitHub Releases 获取版本信息", enabled = !activity.checkingUpdate) {
                        activity.checkForAppUpdate(reportResult = true)
                    }
                    HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("MoonTone", style = MaterialTheme.typography.bodyLarge)
                        Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
internal fun HostAudioSettingsScreen(modifier: Modifier, hosts: List<SavedHost>) {
    val context = LocalContext.current
    val revision = preferenceRevision(context)
    val defaultOn = remember(revision) { AppPreferences.playOnHostByDefault(context) }
    val allHosts = remember(hosts, revision) {
        val all = hosts.associateByTo(linkedMapOf()) { it.host }
        HostAudioPreferences.preferences(context).all.keys.forEach { if (it !in all) all[it] = SavedHost(it, it) }
        all.values.toList()
    }
    var editing by remember { mutableStateOf<SavedHost?>(null) }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("默认：${if (defaultOn) "主机与手机同时播放" else "只在手机播放"}", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text("单独设置会覆盖默认选项，在下次连接或重连时应用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (allHosts.isEmpty()) item { Text("暂无设备，保存电脑后可在这里设置例外。") }
        items(allHosts, key = { it.host }) { host ->
            val override = remember(host.host, revision) { HostAudioPreferences.overrideForHost(context, host.host) }
            Card {
                SettingsAction(host.name.ifBlank { host.host }, "${host.host}\n${when (override) {
                    true -> "主机与手机同时播放"
                    false -> "只在手机播放"
                    null -> "跟随默认"
                }}") { editing = host }
            }
        }
    }
    editing?.let { host ->
        val current = HostAudioPreferences.overrideForHost(context, host.host)
        AlertDialog(
            onDismissRequest = { editing = null }, title = { Text(host.name.ifBlank { host.host }) },
            text = {
                Column {
                    listOf(null to "跟随默认", true to "主机与手机同时播放", false to "只在手机播放").forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = current == value, role = Role.RadioButton, onClick = {
                                if (value == null) HostAudioPreferences.followDefault(context, host.host)
                                else HostAudioPreferences.setPlayOnHost(context, host.host, value)
                                editing = null
                            }).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = current == value, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Card(Modifier.fillMaxWidth()) { Column(content = content) }
    }
}

@Composable
internal fun SettingsAction(title: String, detail: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Text("›", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsToggle(title: String, detail: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun preferenceRevision(context: Context): Int {
    val app = remember(context) { AppPreferences.preferences(context) }
    val host = remember(context) { HostAudioPreferences.preferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(app, host) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        app.registerOnSharedPreferenceChangeListener(listener)
        host.registerOnSharedPreferenceChangeListener(listener)
        onDispose { app.unregisterOnSharedPreferenceChangeListener(listener); host.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return revision
}

private fun openAppSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
}

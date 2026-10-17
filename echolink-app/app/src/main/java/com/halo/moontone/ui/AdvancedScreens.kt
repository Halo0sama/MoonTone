package com.halo.moontone.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.log.MoonToneLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun parseSessionKey(value: String, field: String): ByteArray {
    val hex = value.trim()
    if (hex.isEmpty()) return ByteArray(16)
    require(hex.matches(Regex("[0-9a-fA-F]{32}"))) { "$field 需要 32 位十六进制字符" }
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

@Composable
internal fun ManualConnectionScreen(modifier: Modifier, controller: MoonToneController, initialHost: String, onConnected: () -> Unit) {
    var host by rememberSaveable { mutableStateOf(initialHost) }
    // Session secrets remain only in memory and are cleared when this page leaves.
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var iv by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("连接由其他工具启动的 Sunshine 会话。普通连接请使用首页设备列表。", style = MaterialTheme.typography.bodyMedium)
        Text("主机声音由发起会话的工具设置，页面关闭后不会保留会话密钥。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(host, { host = it }, label = { Text("Sunshine 主机地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(url, { url = it }, label = { Text("RTSP 会话地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        OutlinedTextField(key, { key = it }, label = { Text("riKey（十六进制，可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        OutlinedTextField(iv, { iv = it }, label = { Text("riKeyId（十六进制，可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            busy = true
            error = null
            scope.launch {
                try {
                    val sessionKey = parseSessionKey(key, "riKey")
                    val sessionIv = parseSessionKey(iv, "riKeyId")
                    controller.connectManually(host.trim(), url.trim(), sessionKey, sessionIv)
                    key = ""; iv = ""
                    onConnected()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "连接失败" }
                finally { busy = false }
            }
        }, enabled = !busy && host.isNotBlank() && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(if (busy) "正在连接" else "连接手动会话")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun LogsScreen(modifier: Modifier) {
    var logs by remember { mutableStateOf(MoonToneLog.tail(150)) }
    Column(modifier.padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最近 150 行", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { logs = MoonToneLog.tail(150) }) { Text("刷新日志") }
        }
        SelectionContainer(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            Text(logs.ifBlank { "暂无日志" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun AudioInfoScreen(modifier: Modifier, micRunning: Boolean) {
    val context = LocalContext.current
    fun outputs(): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.productName.toString() }.distinct().joinToString("、").ifBlank { "系统默认输出" }
    }
    var available by remember { mutableStateOf(outputs()) }
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { SettingsGroup("音频串流") {
            AudioInfoRow("解码", "Opus")
            AudioInfoRow("PCM 格式", "48 kHz · 双声道 · 16 bit")
            AudioInfoRow("缓冲策略", "自动适应网络抖动，目标 20–600 ms")
            AudioInfoRow("麦克风上行", if (micRunning) "运行中" else "未开启")
        } }
        item { SettingsGroup("系统输出") {
            AudioInfoRow("检测到的输出设备", available)
            Text("实际输出跟随系统，可在手机的音量面板中切换。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { available = outputs() }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("刷新输出信息") }
        } }
    }
}

@Composable
private fun AudioInfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

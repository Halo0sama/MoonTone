package com.halo.moontone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.halo.moontone.MoonToneApp
import com.halo.moontone.R
import com.halo.moontone.connection.MoonToneState
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.data.*
import com.halo.moontone.multi.MultiConnState
import com.halo.moontone.multi.MultiDeviceState
import kotlinx.coroutines.*

internal object AppPage {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HOST_AUDIO = "host_audio"
    const val MANUAL = "manual"
    const val LOGS = "logs"
    const val AUDIO_INFO = "audio_info"
}

private data class DeviceEntry(val name: String, val host: String, val saved: Boolean, val online: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoonToneUI(controller: MoonToneController, activity: MainActivity, localNetworkGranted: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by controller.state.collectAsState()
    val stage by controller.stageMessage.collectAsState()
    val error by controller.errorMessage.collectAsState()
    val muted by controller.muted.collectAsState()
    val micRunning by controller.micRunning.collectAsState()
    val multi = (context.applicationContext as MoonToneApp).multiController
    val devices by multi.devices.collectAsState()
    var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
    var mixMode by rememberSaveable { mutableStateOf(devices.isNotEmpty()) }
    var host by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf<String?>(null) }
    var pairingText by remember { mutableStateOf("") }
    var connectJob by remember { mutableStateOf<Job?>(null) }
    var saved by remember { mutableStateOf(SavedHosts.load(context)) }
    var online by remember { mutableStateOf(emptyMap<String, Boolean>()) }
    var discovered by remember { mutableStateOf(emptyList<DiscoveredHost>()) }

    // This scope and the application controllers stay mounted while navigating.
    LaunchedEffect(saved, localNetworkGranted) {
        if (!localNetworkGranted) { online = emptyMap(); return@LaunchedEffect }
        while (isActive) {
            online = saved.map { h -> async(Dispatchers.IO) { h.host to SavedHosts.isHostOnline(h.host) } }.awaitAll().toMap()
            delay(10_000)
        }
    }
    val discovery = remember {
        DeviceDiscovery(context,
            onDeviceLost = { lost -> discovered = discovered.filterNot { it.host == lost.host } },
            onDeviceFound = { found -> discovered = discovered.filterNot { it.host == found.host } + found })
    }
    LifecycleStartEffect(localNetworkGranted) {
        if (localNetworkGranted) discovery.start()
        onStopOrDispose { discovery.stop(); discovered = emptyList() }
    }
    val entries = remember(saved, discovered, online, devices, state) {
        val all = linkedMapOf<String, DeviceEntry>()
        saved.forEach { all[it.host] = DeviceEntry(it.name, it.host, true, online[it.host] == true) }
        discovered.forEach { d ->
            val old = all[d.host]
            all[d.host] = DeviceEntry(old?.name?.ifBlank { d.name } ?: d.name, d.host, old?.saved == true, true)
        }
        devices.forEach { if (it.host !in all) all[it.host] = DeviceEntry(it.host, it.host, false, it.state == MultiConnState.CONNECTED) }
        if (state == MoonToneState.CONNECTED && controller.currentHost !in all)
            all[controller.currentHost] = DeviceEntry(controller.currentHost, controller.currentHost, false, true)
        all.values.toList()
    }
    val settingsHosts = entries.map { SavedHost(it.name, it.host) }
    val singleActive = state == MoonToneState.CONNECTED || state == MoonToneState.CONNECTING
    val canConnect = localNetworkGranted && !busy && state != MoonToneState.CONNECTING &&
        (if (mixMode) !singleActive else devices.isEmpty())
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && controller.state.value == MoonToneState.CONNECTED) controller.micStart(controller.currentHost)
    }
    val toggleMic: (Boolean) -> Unit = { on ->
        if (!on) controller.micStop()
        else if (controller.state.value == MoonToneState.CONNECTED) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                controller.micStart(controller.currentHost)
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val connect: (String) -> Unit = { address ->
        val target = address.trim()
        if (target.isNotEmpty() && !busy) {
            if (!localNetworkGranted) page = AppPage.SETTINGS
            else {
                val useMixer = mixMode
                host = target
                busy = true
                connectJob = scope.launch {
                    try {
                        if (!useMixer && singleActive && controller.currentHost != target) controller.disconnect()
                        if (!controller.isPaired(target)) {
                            pin = controller.generatePin()
                            pairingText = "在 Sunshine 网页输入 PIN 完成配对"
                            controller.startPairing(target, pin!!)
                        }
                        pairingText = "正在启动会话"
                        if (useMixer) multi.connect(target) else controller.launchAndConnect(target)
                        SavedHosts.add(context, target, target)
                        saved = SavedHosts.load(context)
                        pin = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        controller.connection.setError(e.message ?: "连接失败")
                        pin = null
                    } finally { busy = false; pairingText = "" }
                }
            }
        }
    }
    val back: () -> Unit = { page = if (page == AppPage.SETTINGS) AppPage.HOME else AppPage.SETTINGS }
    BackHandler(page != AppPage.HOME, back)

    Scaffold(
        topBar = {
            // Give each destination a fresh accessibility identity as well
            // as new visible text; cached toolbar semantics can otherwise lag.
            key(page) {
                TopAppBar(
                    title = { Text(when (page) {
                        AppPage.HOME -> "MoonTone"
                        AppPage.HOST_AUDIO -> "设备声音"
                        AppPage.MANUAL -> "高级连接"
                        AppPage.LOGS -> "运行日志"
                        AppPage.AUDIO_INFO -> "音频信息"
                        else -> "设置"
                    }) },
                    navigationIcon = {
                        if (page != AppPage.HOME) IconButton(onClick = back) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                        }
                    },
                    actions = {
                        if (page == AppPage.HOME) IconButton(onClick = { page = AppPage.SETTINGS }) {
                            Icon(painterResource(R.drawable.ic_settings), contentDescription = "打开设置")
                        }
                    }
                )
            }
        }
    ) { padding ->
        val modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        key(page) {
            when (page) {
                AppPage.SETTINGS -> SettingsScreen(
                    modifier, activity, settingsHosts, localNetworkGranted,
                    micRunning, state == MoonToneState.CONNECTED, controller.currentHost,
                    onMicChange = toggleMic, onNavigate = { page = it }, controller = controller
                )
                AppPage.HOST_AUDIO -> HostAudioSettingsScreen(modifier, settingsHosts)
                AppPage.MANUAL -> ManualConnectionScreen(modifier, controller, host) {
                    saved = SavedHosts.load(context)
                    mixMode = false
                    page = AppPage.HOME
                }
                AppPage.LOGS -> LogsScreen(modifier)
                AppPage.AUDIO_INFO -> AudioInfoScreen(modifier, micRunning)
                else -> LazyColumn(
                    modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text("PC 音频 → 手机", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        PrimaryTabRow(selectedTabIndex = if (mixMode) 1 else 0) {
                            Tab(selected = !mixMode, enabled = !busy && state != MoonToneState.CONNECTING, onClick = { mixMode = false }, text = { Text("单设备播放") })
                            Tab(selected = mixMode, enabled = !busy && state != MoonToneState.CONNECTING, onClick = { mixMode = true }, text = { Text("多设备混音") })
                        }
                    }
                    if (!localNetworkGranted) item {
                        NoticeCard("需要局域网权限", "允许访问本地网络后，才能发现和连接电脑。", "前往设置") { page = AppPage.SETTINGS }
                    }
                    if (!mixMode && devices.isNotEmpty()) item {
                        NoticeCard("存在混音会话", "结束混音会话后可使用单设备播放。", "停止混音") { multi.stopAll() }
                    }
                    if (mixMode && singleActive) item {
                        NoticeCard("单设备播放正在运行", "断开当前播放后可连接多台电脑。", "断开单设备播放") { controller.disconnect() }
                    }
                    error?.let { message -> item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(message, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    } }
                    if (busy || state == MoonToneState.CONNECTING) item {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text(pairingText.ifBlank { stage.ifBlank { "正在连接" } }, style = MaterialTheme.typography.bodyMedium)
                                pin?.let {
                                    Text("PIN：$it", style = MaterialTheme.typography.headlineMedium)
                                    Text("打开 http://$host:47990，在 PIN 页面输入以上数字。", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { connectJob?.cancel(); controller.disconnect(); pin = null }) { Text("取消连接") }
                            }
                        }
                    }
                    item {
                        Text(if (mixMode) "连接多台电脑，分别调节手机端音量" else "选择一台电脑播放", style = MaterialTheme.typography.titleSmall)
                    }
                    if (entries.isEmpty()) item {
                        Text(if (localNetworkGranted) "正在发现设备，也可以通过 IP 连接。" else "尚未发现设备", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(entries, key = { it.host }) { device ->
                        val md = devices.firstOrNull { it.host == device.host }
                        val current = !mixMode && state == MoonToneState.CONNECTED && controller.currentHost == device.host
                        DeviceCard(
                            device, if (mixMode) md else null, current, muted,
                            canConnect = canConnect,
                            onConnect = { connect(device.host) },
                            onDisconnect = { if (mixMode && md != null) multi.disconnect(md.slot) else controller.disconnect() },
                            onMute = { if (mixMode && md != null) multi.setMuted(md.slot, !md.muted) else controller.setMuted(!muted) },
                            onVolume = { if (md != null) multi.setVolume(md.slot, it) },
                            onSave = {
                                if (device.saved) SavedHosts.remove(context, device.host)
                                else SavedHosts.add(context, device.name, device.host)
                                saved = SavedHosts.load(context)
                            }
                        )
                    }
                    item {
                        Card {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("通过 IP 连接", style = MaterialTheme.typography.titleSmall)
                                OutlinedTextField(host, { host = it }, label = { Text("Sunshine 主机地址") }, singleLine = true,
                                    enabled = !busy && state != MoonToneState.CONNECTING, modifier = Modifier.fillMaxWidth())
                                Button(
                                    onClick = { connect(host) },
                                    enabled = host.isNotBlank() && canConnect,
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text(if (mixMode) "加入混音" else "连接") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceEntry, multi: MultiDeviceState?, current: Boolean, singleMuted: Boolean, canConnect: Boolean,
    onConnect: () -> Unit, onDisconnect: () -> Unit, onMute: () -> Unit, onVolume: (Float) -> Unit, onSave: () -> Unit
) {
    val connected = current || multi?.state == MultiConnState.CONNECTED
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(device.name.ifBlank { device.host }, style = MaterialTheme.typography.titleMedium)
            Text(device.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                connected -> "● 已连接"
                multi?.state == MultiConnState.CONNECTING -> multi.error ?: "正在连接"
                multi?.state == MultiConnState.ERROR -> multi.error ?: "连接失败"
                device.online -> "● 在线"
                else -> "○ 离线"
            }, style = MaterialTheme.typography.labelMedium, color = if (multi?.state == MultiConnState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (connected) TextButton(onClick = onMute) { Text(if (multi?.muted ?: singleMuted) "取消手机静音" else "手机静音") }
                if (multi != null || current) {
                    if (multi?.state == MultiConnState.ERROR) TextButton(onClick = onConnect, enabled = canConnect) { Text("重连") }
                    OutlinedButton(onClick = onDisconnect) { Text("断开") }
                } else {
                    TextButton(onClick = onSave) { Text(if (device.saved) "移除" else "保存") }
                    Button(onClick = onConnect, enabled = canConnect) { Text("连接") }
                }
            }
            if (multi?.state == MultiConnState.CONNECTED) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音量", style = MaterialTheme.typography.bodySmall)
                    Slider(value = multi.volume, onValueChange = onVolume, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NoticeCard(title: String, detail: String, action: String, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}

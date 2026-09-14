package com.halo.moontone.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.halo.moontone.MoonToneApp
import com.halo.moontone.BuildConfig
import com.halo.moontone.audio.MoonToneAudioService
import com.halo.moontone.connection.MoonToneState
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.data.DeviceDiscovery
import com.halo.moontone.data.DiscoveredHost
import com.halo.moontone.data.HostCapabilities
import com.halo.moontone.data.SavedHost
import com.halo.moontone.data.SavedHosts
import com.halo.moontone.log.MoonToneLog
import com.halo.moontone.multi.MultiConnState
import com.halo.moontone.multi.MultiController
import com.halo.moontone.update.UpdateChecker
import com.halo.moontone.ui.theme.MoonToneTheme
import com.limelight.binding.audio.AndroidAudioRenderer
import com.limelight.nvstream.jni.MoonBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A single merged device entry: discovered via mDNS and/or saved locally. */
private data class DeviceEntry(
    val name: String,
    val host: String,
    val saved: Boolean,
    val online: Boolean
)

class MainActivity : ComponentActivity() {

    private lateinit var controller: MoonToneController
    private var audioService: MoonToneAudioService? = null
    private var serviceBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            audioService = (service as MoonToneAudioService.LocalBinder).service
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            audioService = null; serviceBound = false
        }
    }

    /**
     * Queries GitHub for the latest release and pops a dialog when it is
     * newer than the installed version. Best-effort by design: network
     * failures, rate limits and "no releases yet" all silently no-op.
     */
    private fun checkForAppUpdate() {
        CoroutineScope(Dispatchers.Main).launch {
            val release = UpdateChecker.fetchLatest() ?: return@launch
            if (!UpdateChecker.isNewer(release.tagName, BuildConfig.VERSION_NAME)) {
                MoonToneLog.i("Update", "already on latest (${BuildConfig.VERSION_NAME}, release ${release.tagName})")
                return@launch
            }
            MoonToneLog.i("Update", "new release available: ${release.tagName}")
            val notes = release.notes.ifBlank { release.title }.take(1200)
            AlertDialog.Builder(this@MainActivity)
                .setTitle("发现新版本 ${release.tagName}")
                .setMessage(
                    notes + "\n\n当前版本：" + BuildConfig.VERSION_NAME +
                        "\n更新需要重新安装 APK，完成后配对与设置都会保留。"
                )
                .setPositiveButton("前往下载") { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url)))
                }
                .setNegativeButton("以后再说", null)
                .show()
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Non-blocking update check against GitHub Releases; silently no-ops
        // on any failure or when already on the latest version.
        checkForAppUpdate()

        // Edge-to-edge (ExpressAssistant style): transparent system bars in theme,
        // content can go under the bottom gesture bar while status bar icons stay visible.
        enableEdgeToEdge()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        controller = (application as MoonToneApp).controller
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MoonToneTheme {
                MoonToneUI(controller, this)
            }
        }
    }

    override fun onDestroy() {
        // The controller (and therefore the stream) survives activity destruction
        // so the debug CLI and the foreground service can keep operating.
        if (serviceBound) unbindService(serviceConnection)
        super.onDestroy()
    }

    fun startAudioService() {
        ContextCompat.startForegroundService(
            this, Intent(this, MoonToneAudioService::class.java).apply {
                action = MoonToneAudioService.ACTION_START
            }
        )
        bindService(Intent(this, MoonToneAudioService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun stopAudioService() {
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
        stopService(Intent(this, MoonToneAudioService::class.java))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoonToneUI(controller: MoonToneController, activity: MainActivity) {
    val connection = controller.connection
    val state by connection.state.collectAsState()
    val stageMessage by connection.stageMessage.collectAsState()
    val errorMessage by connection.errorMessage.collectAsState()
    val muted by controller.muted.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val multiController = remember { MultiController(context.applicationContext as MoonToneApp, controller.crypto) }
    val multiDevices by multiController.devices.collectAsState()

    var host by remember { mutableStateOf("192.168.") }
    var isConnecting by remember { mutableStateOf(false) }
    var currentPin by remember { mutableStateOf<String?>(null) }
    var pairingStatus by remember { mutableStateOf("") }

    var showManual by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var rtspUrl by remember { mutableStateOf("") }
    var riKeyHex by remember { mutableStateOf("") }
    var riKeyIdHex by remember { mutableStateOf("") }
    var showCertInfo by remember { mutableStateOf(false) }
    var moonToneCert by remember { mutableStateOf("") }
    var showLogs by remember { mutableStateOf(false) }
    var micEnabled by remember { mutableStateOf(false) }

    // Saved hosts (Moonlight-style device list)
    var savedHosts by remember { mutableStateOf(SavedHosts.load(context)) }
    var onlineMap by remember { mutableStateOf(mapOf<String, Boolean>()) }
    LaunchedEffect(savedHosts) {
        val status = savedHosts.associate { h ->
            h.host to withContext(Dispatchers.IO) { SavedHosts.isHostOnline(h.host) }
        }
        onlineMap = status
    }

    // LAN auto-discovery (mDNS) — runs automatically while the main screen is shown.
    var discoveredHosts by remember { mutableStateOf(listOf<DiscoveredHost>()) }
    val discovery = remember {
        DeviceDiscovery(context) { host ->
            discoveredHosts = (discoveredHosts + host).distinctBy { it.host }
        }
    }
    LaunchedEffect(Unit) {
        discovery.start()
    }
    DisposableEffect(Unit) {
        onDispose { discovery.stop() }
    }

    // Moonlight-style unified device list: merge saved hosts and mDNS discoveries
    // by IP so the same machine only appears once.
    val deviceEntries = remember(discoveredHosts, savedHosts, onlineMap) {
        val byHost = linkedMapOf<String, DeviceEntry>()
        savedHosts.forEach { s ->
            byHost[s.host] = DeviceEntry(
                name = s.name,
                host = s.host,
                saved = true,
                online = onlineMap[s.host] == true
            )
        }
        discoveredHosts.forEach { d ->
            val existing = byHost[d.host]
            if (existing != null) {
                byHost[d.host] = existing.copy(
                    name = if (existing.name.isBlank()) d.name else existing.name,
                    online = true
                )
            } else {
                byHost[d.host] = DeviceEntry(d.name, d.host, saved = false, online = true)
            }
        }
        byHost.values.toList()
    }

    // One-tap connect action used by both the host input and saved-host cards.
    val connectAction: (String) -> Unit = { target ->
        scope.launch {
            isConnecting = true
            try {
                if (showManual && rtspUrl.isNotBlank()) {
                    val hostT = target.trim()
                    controller.pairing.refreshServerInfo(hostT)
                    val audioRenderer = AndroidAudioRenderer(context, false)
                    connection.setAudioRenderer(audioRenderer)
                    connection.connect(
                        address = hostT,
                        appVersion = controller.pairing.serverAppVersion,
                        gfeVersion = controller.pairing.serverGfeVersion,
                        rtspSessionUrl = rtspUrl.trim(),
                        serverCodecModeSupport = controller.pairing.serverCodecModeSupport,
                        audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO.toInt(),
                        riAesKey = riKeyHex.takeIf { it.isNotBlank() }
                            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                            ?: ByteArray(16),
                        riAesIv = riKeyIdHex.takeIf { it.isNotBlank() }
                            ?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                            ?: ByteArray(16),
                        audioOnly = controller.pairing.serverAdvertisesAudioOnly ||
                            HostCapabilities.audioOnly(context, hostT, controller.pairing.serverUniqueId)
                    )
                    activity.startAudioService()
                    SavedHosts.add(context, "Mac", hostT)
                    savedHosts = SavedHosts.load(context)
                } else {
                    val hostT = target.trim()

                    if (!controller.isPaired(hostT)) {
                        val pin = controller.generatePin()
                        currentPin = pin
                        pairingStatus = "等待你在 Sunshine 网页输入 PIN..."
                        controller.startPairing(hostT, pin)
                        pairingStatus = "PIN 已确认！正在启动会话..."
                    } else {
                        pairingStatus = "已配对，正在启动会话..."
                    }

                    controller.launchAndConnect(hostT)
                    SavedHosts.add(context, "Mac", hostT)
                    savedHosts = SavedHosts.load(context)
                }
            } catch (e: Exception) {
                MoonToneLog.e("UI", "Connect failed", e)
                connection.setError("Failed: ${e.message}")
                currentPin = null
            } finally {
                isConnecting = false
                pairingStatus = ""
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("MoonTone", fontSize = 32.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
                Text("PC 音频 → 手机", fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 24.dp))

                // Connected
                AnimatedVisibility(visible = state == MoonToneState.CONNECTED,
                    enter = fadeIn(), exit = fadeOut()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Card(Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B5E20).copy(alpha = 0.3f))
                        ) {
                            Text("\u25CF  已连接", color = Color(0xFF81C784),
                                modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(12.dp))

                        // Output device hint (multi-device mixing: PC audio + phone audio)
                        OutputDeviceHint(context)

                        Spacer(Modifier.height(8.dp))

                        // Microphone uplink switch
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("麦克风上行", fontWeight = FontWeight.Medium)
                                    Text(
                                        "将手机麦克风回传到 PC（UDP 48100）",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = micEnabled,
                                    onCheckedChange = { on ->
                                        micEnabled = on
                                        if (on) {
                                            val target = controller.currentHost.ifBlank { host.trim() }
                                            if (!controller.micStart(target)) {
                                                micEnabled = false
                                            }
                                        } else {
                                            controller.micStop()
                                        }
                                    }
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Stream specs (same card/row style as audio quality)
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("串流规格", fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("状态", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("音频串流中", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("主机", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(controller.currentHost.ifBlank { "-" },
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("协议", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("GameStream / Opus", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("会话", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("已连接", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Multi-device aggregation control panel
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("多设备控制", fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "当前同时只保留一路串流，可在这里快速切换任意在线 Sunshine",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(8.dp))
                                if (deviceEntries.isEmpty()) {
                                    Text("暂无其他设备", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                deviceEntries.forEach { dev ->
                                    val isCurrent = dev.host == controller.currentHost
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(dev.name.ifBlank { dev.host }, fontWeight = FontWeight.Medium)
                                            Text(dev.host, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                if (dev.online) "● 在线" else "○ 离线",
                                                color = if (dev.online) Color(0xFF81C784) else Color(0xFF9E9E9E),
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        if (isCurrent) {
                                            Text("当前", color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelMedium)
                                        } else {
                                            TextButton(
                                                onClick = {
                                                    controller.disconnect()
                                                    connectAction(dev.host)
                                                },
                                                enabled = dev.online && !isConnecting
                                            ) { Text("切换") }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Audio stream specs (stock moonlight-android pipeline)
                        Card(
                            Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("当前音质规格", fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("下行编码", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Opus · 48000 Hz · 2 ch",
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("下行码率", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("512 kbps · 5 ms/包",
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("缓冲策略", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("动态低延时 40–600 ms（自动调参）",
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                }
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("上行麦克风", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        if (controller.micStatus()) {
                                            "64 kbps · 运行中"
                                        } else {
                                            "未开启"
                                        },
                                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            controller.disconnect()
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
                        ) { Text("断开连接") }
                    }
                }

                // Error
                errorMessage?.let { msg ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFB71C1C).copy(alpha = 0.3f))
                    ) {
                        Text(msg, color = Color(0xFFEF9A9A), modifier = Modifier.padding(16.dp), fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // In-app log viewer (same data as the debug CLI `logs` command)
                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(if (showLogs) "隐藏日志" else "显示日志")
                }
                if (showLogs) {
                    Card(Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
                    ) {
                        Text(
                            MoonToneLog.tail(150),
                            color = Color(0xFF9FB6D4),
                            modifier = Modifier.padding(12.dp),
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // PIN display (shown during pairing)
                currentPin?.let { p ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0).copy(alpha = 0.3f))
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("PIN: $p", color = Color(0xFF90CAF9),
                                fontWeight = FontWeight.Bold, fontSize = 28.sp)
                            Spacer(Modifier.height(8.dp))
                            Text(pairingStatus, color = Color(0xFFE3F2FD), fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Text("1. 打开: http://${host.trim()}:47990\n2. 点击 PIN 标签\n3. 输入上方 PIN",
                                color = Color(0xFFBBDEFB), fontSize = 13.sp)
                        }
                    }
                }

                // Connecting progress
                if (state == MoonToneState.CONNECTING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 16.dp),
                        color = MaterialTheme.colorScheme.primary)
                    Text(stageMessage, fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
                }

                // Unified device list (Moonlight-style): mDNS + saved merged by IP
                if (state != MoonToneState.CONNECTED && state != MoonToneState.CONNECTING) {
                    Text("可连接设备",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onBackground)
                    if (deviceEntries.isEmpty()) {
                        Text("正在扫描…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    deviceEntries.forEach { dev ->
                        Card(
                            Modifier.fillMaxWidth().padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(dev.name.ifBlank { dev.host }, fontWeight = FontWeight.Medium)
                                    Text(dev.host, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row {
                                        Text(
                                            if (dev.online) "● 在线" else "○ 离线",
                                            color = if (dev.online) Color(0xFF81C784) else Color(0xFF9E9E9E),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                        if (dev.saved) {
                                            Text("  ·  已保存",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        host = dev.host
                                        currentPin = null
                                        connectAction(dev.host)
                                    },
                                    enabled = !isConnecting
                                ) { Text("连接") }
                                if (dev.saved) {
                                    TextButton(
                                        onClick = {
                                            SavedHosts.remove(context, dev.host)
                                            savedHosts = SavedHosts.load(context)
                                        }
                                    ) { Text("删除") }
                                } else {
                                    TextButton(
                                        onClick = {
                                            SavedHosts.add(context, dev.name.ifBlank { "Mac" }, dev.host)
                                            savedHosts = SavedHosts.load(context)
                                        }
                                    ) { Text("添加") }
                                }
                            }
                        }
                    }
                    if (deviceEntries.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // Multi-device simultaneous streaming panel
                if (state != MoonToneState.CONNECTED && state != MoonToneState.CONNECTING) {
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text("多设备同时串流", fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "可同时连接多台 Sunshine，手机端混音输出（当前最多 32 路）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                            if (deviceEntries.isEmpty()) {
                                Text("暂无可连接设备", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            deviceEntries.forEach { dev ->
                                val md = multiDevices.firstOrNull { it.host == dev.host }
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(dev.name.ifBlank { dev.host }, fontWeight = FontWeight.Medium)
                                            Text(dev.host, style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(
                                                when {
                                                    md == null -> if (dev.online) "● 在线" else "○ 离线"
                                                    md.state == MultiConnState.CONNECTED -> "● 已连接"
                                                    md.state == MultiConnState.CONNECTING -> "◐ 连接中"
                                                    else -> "● 错误：${md.error ?: "未知"}"
                                                },
                                                color = when {
                                                    md?.state == MultiConnState.CONNECTED -> Color(0xFF81C784)
                                                    md?.state == MultiConnState.ERROR -> Color(0xFFEF9A9A)
                                                    else -> if (dev.online) Color(0xFF81C784) else Color(0xFF9E9E9E)
                                                },
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                        if (md == null) {
                                            TextButton(
                                                onClick = { multiController.connect(dev.host) },
                                                enabled = dev.online
                                            ) { Text("连接") }
                                        } else {
                                            if (md.state == MultiConnState.CONNECTED) {
                                                TextButton(
                                                    onClick = { multiController.setMuted(md.slot, !md.muted) }
                                                ) { Text(if (md.muted) "取消静音" else "静音") }
                                            }
                                            if (md.state == MultiConnState.ERROR) {
                                                TextButton(
                                                    onClick = {
                                                        multiController.disconnect(md.slot)
                                                        multiController.connect(dev.host)
                                                    }
                                                ) { Text("重连") }
                                            }
                                            TextButton(
                                                onClick = { multiController.disconnect(md.slot) }
                                            ) { Text("断开") }
                                        }
                                    }
                                    if (md?.state == MultiConnState.CONNECTED) {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("音量", style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Slider(
                                                value = md.volume,
                                                onValueChange = { multiController.setVolume(md.slot, it) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Input form
                if (state != MoonToneState.CONNECTED && state != MoonToneState.CONNECTING) {
                    OutlinedTextField(
                        value = host, onValueChange = { host = it; currentPin = null },
                        label = { Text("Sunshine 主机 IP") },
                        placeholder = { Text("192.168.1.100") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))

                    TextButton(onClick = { showMore = !showMore }) {
                        Text(if (showMore) "收起更多" else "更多")
                    }
                    if (showMore) {
                        TextButton(onClick = { showManual = !showManual }) {
                            Text(if (showManual) "收起手动模式" else "手动模式（RTSP + 密钥）")
                        }

                        // Cert export
                        TextButton(onClick = {
                            val crypto = controller.crypto
                            crypto.getClientCertificate() // force generation
                            val pem = crypto.getPemEncodedClientCertificate()
                            if (pem != null) {
                                val dir = context.getExternalFilesDir(null)!!
                                val f = java.io.File(dir, "moontone_client.pem")
                                f.writeBytes(pem)
                                moonToneCert = "证书已保存到: ${f.absolutePath}"
                                // Also copy to clipboard
                                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("MoonTone Cert", String(pem)))
                                moonToneCert += "\n已复制到剪贴板！"
                            } else {
                                moonToneCert = "证书未就绪，请重试。"
                            }
                        }) {
                            Text("导出证书")
                        }
                        if (moonToneCert.isNotBlank()) {
                            Text(moonToneCert, fontSize = 11.sp, color = Color(0xFF81C784))
                            Spacer(Modifier.height(8.dp))
                        }
                        if (showManual) {
                            OutlinedTextField(value = rtspUrl, onValueChange = { rtspUrl = it },
                                label = { Text("RTSP 会话地址") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = riKeyHex, onValueChange = { riKeyHex = it },
                                label = { Text("riKey（十六进制）") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = riKeyIdHex, onValueChange = { riKeyIdHex = it },
                                label = { Text("riKeyId（十六进制）") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { connectAction(host) },
                        enabled = host.isNotBlank() && !isConnecting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isConnecting) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("连接")
                    }
                }
            }

            if (state == MoonToneState.CONNECTED) {
                TextButton(
                    onClick = { controller.disconnect() },
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)
                ) { Text("← 返回设备") }

                FloatingActionButton(
                    onClick = { controller.setMuted(!muted) },
                    modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        if (muted) "▶" else "❚❚",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        }
}

@Composable
private fun OutputDeviceHint(context: Context) {
    var label = "本机扬声器 / 默认输出"
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasBt = outputs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
        val hasUsb = outputs.any {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }
        label = when {
            hasBt -> "蓝牙音箱 / 耳机（PC 音频 + 手机音频混合输出）"
            hasUsb -> "USB DAC / 耳机"
            else -> "本机扬声器 / 默认输出"
        }
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            "输出设备：$label",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

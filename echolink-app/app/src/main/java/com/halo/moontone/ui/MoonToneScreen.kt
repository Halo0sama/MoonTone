@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.halo.moontone.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import com.halo.moontone.MoonToneApp
import com.halo.moontone.connection.MoonToneState
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.data.*
import com.halo.moontone.multi.MultiConnState
import com.halo.moontone.multi.MultiDeviceState
import com.halo.moontone.ui.components.AnimatedWaveform
import com.halo.moontone.ui.components.IconBadge
import com.halo.moontone.ui.components.MoonToneSectionHeader
import com.halo.moontone.ui.components.StatusPill
import com.halo.moontone.ui.theme.MoonToneShape
import kotlinx.coroutines.*

internal object AppPage {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val HOST_AUDIO = "host_audio"
    const val MANUAL = "manual"
    const val LOGS = "logs"
    const val AUDIO_INFO = "audio_info"
}

internal data class DeviceEntry(val name: String, val host: String, val saved: Boolean, val online: Boolean)

private fun pageTitle(page: String): String = when (page) {
    AppPage.SETTINGS -> "设置"
    AppPage.HOST_AUDIO -> "设备声音"
    AppPage.MANUAL -> "高级连接"
    AppPage.LOGS -> "运行日志"
    AppPage.AUDIO_INFO -> "音频信息"
    else -> "MoonTone"
}

private fun pageSubtitle(page: String): String? = when (page) {
    AppPage.SETTINGS -> "外观、播放、权限与诊断"
    AppPage.HOST_AUDIO -> "按电脑单独覆盖主机播放设置"
    AppPage.MANUAL -> "使用已有 RTSP 会话直接连接"
    AppPage.LOGS -> "最近的连接与音频记录"
    AppPage.AUDIO_INFO -> "编码、缓冲与系统输出"
    else -> null
}

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

    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = topAppBarState)
    LaunchedEffect(page) {
        topAppBarState.heightOffset = 0f
        topAppBarState.contentOffset = 0f
    }

    val statusText = when {
        busy || state == MoonToneState.CONNECTING ->
            "正在连接 · " + stage.ifBlank { pairingText.ifBlank { "准备会话" } }
        state == MoonToneState.CONNECTED -> "已连接 · " + controller.currentHost
        mixMode && devices.any { it.state == MultiConnState.CONNECTED } ->
            "多设备混音 · ${devices.count { it.state == MultiConnState.CONNECTED }} 台"
        localNetworkGranted -> "未连接 · 正在发现附近的电脑"
        else -> "未连接 · 需要局域网权限"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MoonToneTopBar(
                page = page,
                statusText = statusText,
                scrollBehavior = scrollBehavior,
                onBack = back,
                onSettings = { page = AppPage.SETTINGS },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    (fadeIn(tween(240)) + scaleIn(initialScale = 0.97f, animationSpec = tween(240)))
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "page",
            ) { currentPage ->
                val contentModifier = Modifier.fillMaxSize()
                when (currentPage) {
                    AppPage.SETTINGS -> SettingsScreen(
                        contentModifier, activity, settingsHosts, localNetworkGranted,
                        micRunning, state == MoonToneState.CONNECTED, controller.currentHost,
                        onMicChange = toggleMic, onNavigate = { page = it }, controller = controller
                    )
                    AppPage.HOST_AUDIO -> HostAudioSettingsScreen(contentModifier, settingsHosts)
                    AppPage.MANUAL -> ManualConnectionScreen(contentModifier, controller, host) {
                        saved = SavedHosts.load(context)
                        mixMode = false
                        page = AppPage.HOME
                    }
                    AppPage.LOGS -> LogsScreen(contentModifier)
                    AppPage.AUDIO_INFO -> AudioInfoScreen(contentModifier, micRunning)
                    else -> HomeContent(
                        modifier = contentModifier,
                        scrollBehavior = scrollBehavior,
                        localNetworkGranted = localNetworkGranted,
                        state = state,
                        busy = busy,
                        stage = stage,
                        pairingText = pairingText,
                        pin = pin,
                        error = error,
                        mixMode = mixMode,
                        devices = devices,
                        entries = entries,
                        singleMuted = muted,
                        micRunning = micRunning,
                        currentHost = controller.currentHost,
                        ipHost = host,
                        canConnect = canConnect,
                        onIpHostChange = { host = it },
                        onConnect = connect,
                        onCancelConnect = { connectJob?.cancel(); controller.disconnect(); pin = null; busy = false },
                        onSelectMode = { mixMode = it },
                        onNavigateSettings = { page = AppPage.SETTINGS },
                        onToggleMute = { controller.setMuted(!muted) },
                        onToggleMic = toggleMic,
                        onSaveToggle = { device ->
                            if (device.saved) SavedHosts.remove(context, device.host)
                            else SavedHosts.add(context, device.name, device.host)
                            saved = SavedHosts.load(context)
                        },
                        onMultiStopAll = { multi.stopAll() },
                        onSingleDisconnect = { controller.disconnect() },
                        onMultiConnect = { target -> connect(target) },
                        onMultiDisconnect = { slot -> multi.disconnect(slot) },
                        onMultiMute = { slot, value -> multi.setMuted(slot, value) },
                        onMultiVolume = { slot, value -> multi.setVolume(slot, value) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Top app bar
// ---------------------------------------------------------------------------

@Composable
private fun MoonToneTopBar(
    page: String,
    statusText: String,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        subtitleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (page == AppPage.HOME) {
        LargeFlexibleTopAppBar(
            title = {
                Text(
                    text = "MoonTone",
                    style = MaterialTheme.typography.headlineLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            actions = {
                FilledTonalIconButton(
                    onClick = onSettings,
                    modifier = Modifier.padding(end = 12.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = "打开设置")
                }
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    } else {
        MediumFlexibleTopAppBar(
            title = {
                Text(
                    text = pageTitle(page),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = pageSubtitle(page)?.let { subtitle ->
                {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                FilledTonalIconButton(
                    onClick = onBack,
                    modifier = Modifier.padding(start = 12.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
            },
            colors = colors,
            scrollBehavior = scrollBehavior,
        )
    }
}

// ---------------------------------------------------------------------------
// Home
// ---------------------------------------------------------------------------

@Composable
private fun HomeContent(
    modifier: Modifier,
    scrollBehavior: TopAppBarScrollBehavior,
    localNetworkGranted: Boolean,
    state: MoonToneState,
    busy: Boolean,
    stage: String,
    pairingText: String,
    pin: String?,
    error: String?,
    mixMode: Boolean,
    devices: List<MultiDeviceState>,
    entries: List<DeviceEntry>,
    singleMuted: Boolean,
    micRunning: Boolean,
    currentHost: String,
    ipHost: String,
    canConnect: Boolean,
    onIpHostChange: (String) -> Unit,
    onConnect: (String) -> Unit,
    onCancelConnect: () -> Unit,
    onSelectMode: (Boolean) -> Unit,
    onNavigateSettings: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onSaveToggle: (DeviceEntry) -> Unit,
    onMultiStopAll: () -> Unit,
    onSingleDisconnect: () -> Unit,
    onMultiConnect: (String) -> Unit,
    onMultiDisconnect: (Int) -> Unit,
    onMultiMute: (Int, Boolean) -> Unit,
    onMultiVolume: (Int, Float) -> Unit,
) {
    // Online / active devices first; offline devices are grouped into a collapsible section.
    val activeHosts = remember(devices, state, currentHost) {
        buildSet {
            devices.forEach { device -> if (device.state != MultiConnState.IDLE) add(device.host) }
            if (state == MoonToneState.CONNECTED && currentHost.isNotBlank()) add(currentHost)
        }
    }
    val sortedEntries = remember(entries, activeHosts) {
        entries.sortedWith(
            compareByDescending<DeviceEntry> { it.host in activeHosts }
                .thenByDescending { it.online }
        )
    }
    val onlineEntries = remember(sortedEntries, activeHosts) {
        sortedEntries.filter { it.host in activeHosts || it.online }
    }
    val offlineEntries = remember(sortedEntries, activeHosts) {
        sortedEntries.filterNot { it.host in activeHosts || it.online }
    }
    var offlineExpanded by rememberSaveable { mutableStateOf(false) }
    // Never hide the only devices the user has: reveal the offline group only when the first
    // discovery probe settles without a single online host (avoids a flash-expand before the
    // first online map arrives).
    LaunchedEffect(onlineEntries.size, offlineEntries.size) {
        if (onlineEntries.isEmpty() && offlineEntries.isNotEmpty()) {
            delay(3_000)
            offlineExpanded = true
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!localNetworkGranted) {
            item {
                NoticeCard(
                    title = "需要局域网权限",
                    detail = "允许访问本地网络后，才能发现和连接电脑。",
                    action = "前往设置",
                    icon = Icons.Rounded.WifiOff,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    onAction = onNavigateSettings,
                )
            }
        }

        item {
            val connectedCount = devices.count { it.state == MultiConnState.CONNECTED }
            when {
                busy || state == MoonToneState.CONNECTING -> ConnectingHero(stage, pairingText, pin, ipHost, onCancelConnect)
                mixMode && connectedCount > 0 -> MixingHero(connectedCount) { onMultiStopAll() }
                state == MoonToneState.CONNECTED -> ConnectedHero(
                    name = entries.firstOrNull { it.host == currentHost }?.name?.ifBlank { currentHost } ?: currentHost,
                    host = currentHost,
                    muted = singleMuted,
                    micRunning = micRunning,
                    onToggleMute = onToggleMute,
                    onToggleMic = onToggleMic,
                    onDisconnect = onSingleDisconnect,
                )
                else -> IdleHero()
            }
        }

        error?.takeIf { it.isNotBlank() }?.let { message ->
            item {
                NoticeCard(
                    title = "连接失败",
                    detail = localizedConnectionError(message),
                    icon = Icons.Rounded.Error,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        if (!mixMode && devices.isNotEmpty()) {
            item {
                NoticeCard(
                    title = "存在混音会话",
                    detail = "结束混音会话后可使用单设备播放。",
                    action = "停止混音",
                    icon = Icons.Rounded.GraphicEq,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) { onMultiStopAll() }
            }
        }
        if (mixMode && (state == MoonToneState.CONNECTED || state == MoonToneState.CONNECTING)) {
            item {
                NoticeCard(
                    title = "单设备播放正在运行",
                    detail = "断开当前播放后才能连接多台电脑。",
                    action = "断开单设备播放",
                    icon = Icons.Rounded.Cast,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                ) { onSingleDisconnect() }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MoonToneSectionHeader("播放模式")
                ModeSelector(
                    mixMode = mixMode,
                    enabled = !busy && state != MoonToneState.CONNECTING,
                    onSelect = onSelectMode,
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MoonToneSectionHeader(
                    title = "附近的电脑",
                    modifier = Modifier.weight(1f),
                    trailing = {
                        Text(
                            text = when {
                                entries.isEmpty() -> "寻找中"
                                onlineEntries.isNotEmpty() -> "${onlineEntries.size} 台在线"
                                else -> "${entries.size} 台"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        if (entries.isEmpty()) {
            item { EmptyDevicesCard(localNetworkGranted, onNavigateSettings) }
        }

        items(onlineEntries, key = { it.host }) { device ->
            DeviceListCard(
                device = device,
                devices = devices,
                mixMode = mixMode,
                state = state,
                currentHost = currentHost,
                singleMuted = singleMuted,
                canConnect = canConnect,
                onConnect = onConnect,
                onMultiConnect = onMultiConnect,
                onSingleDisconnect = onSingleDisconnect,
                onMultiDisconnect = onMultiDisconnect,
                onToggleMute = onToggleMute,
                onMultiMute = onMultiMute,
                onMultiVolume = onMultiVolume,
                onSaveToggle = onSaveToggle,
            )
        }

        if (offlineEntries.isNotEmpty()) {
            item {
                OfflineGroupHeader(
                    count = offlineEntries.size,
                    expanded = offlineExpanded,
                    onToggle = { offlineExpanded = !offlineExpanded },
                )
            }
            if (offlineExpanded) {
                items(offlineEntries, key = { it.host }) { device ->
                    DeviceListCard(
                        device = device,
                        devices = devices,
                        mixMode = mixMode,
                        state = state,
                        currentHost = currentHost,
                        singleMuted = singleMuted,
                        canConnect = canConnect,
                        onConnect = onConnect,
                        onMultiConnect = onMultiConnect,
                        onSingleDisconnect = onSingleDisconnect,
                        onMultiDisconnect = onMultiDisconnect,
                        onToggleMute = onToggleMute,
                        onMultiMute = onMultiMute,
                        onMultiVolume = onMultiVolume,
                        onSaveToggle = onSaveToggle,
                    )
                }
            }
        }

        item {
            IpConnectCard(
                host = ipHost,
                mixMode = mixMode,
                enabled = canConnect,
                onHostChange = onIpHostChange,
                onConnect = { onConnect(ipHost) },
            )
        }
    }
}

/**
 * User-facing Chinese text for a technical connection error. The raw cause stays in the run
 * log (运行日志) for diagnostics; the UI must never surface raw English stack/RTSP text.
 */
private fun localizedConnectionError(raw: String): String {
    val text = raw.trim()
    return when {
        text.contains("refused", ignoreCase = true) ->
            "电脑拒绝了连接，请确认 Sunshine 已启动且未被防火墙拦截。"
        text.contains("timeout", ignoreCase = true) || text.contains("timed out", ignoreCase = true) ||
            text.contains("failed to connect", ignoreCase = true) ->
            "连接超时，请确认电脑已开机、Sunshine 正在运行，且手机与电脑在同一局域网。"
        text.contains("resolve", ignoreCase = true) || text.contains("UnknownHost", ignoreCase = true) ->
            "找不到这台电脑，请检查 IP 地址或局域网连接。"
        text.contains("certificate", ignoreCase = true) || text.contains("handshake", ignoreCase = true) ||
            text.contains("tls", ignoreCase = true) ->
            "安全握手失败，请重新配对这台电脑。"
        text.contains("cancel", ignoreCase = true) -> "连接已取消。"
        else -> "连接失败，请重试。"
    }
}

// ---------------------------------------------------------------------------
// Hero states
// ---------------------------------------------------------------------------

@Composable
private fun HeroSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MoonToneShape.hero,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            content = content,
        )
    }
}

@Composable
private fun IdleHero() {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    HeroSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(
                    text = "准备就绪",
                    containerColor = contentColor.copy(alpha = 0.12f),
                    contentColor = contentColor,
                )
                Text(
                    text = "听见电脑的声音",
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                )
                Text(
                    text = "选择下方电脑开始串流，或通过 IP 手动连接。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
            MoonGlyph(size = 88.dp)
        }
        AnimatedWaveform(
            active = false,
            color = contentColor.copy(alpha = 0.7f),
            barCount = 13,
            height = 34.dp,
            expand = true,
        )
    }
}

@Composable
internal fun ConnectedHero(
    name: String,
    host: String,
    muted: Boolean,
    micRunning: Boolean,
    onToggleMute: () -> Unit,
    onToggleMic: (Boolean) -> Unit,
    onDisconnect: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    HeroSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    text = "串流中",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leading = { StatusDot(MaterialTheme.colorScheme.onSecondaryContainer) },
                )
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
            IconBadge(
                icon = Icons.Rounded.GraphicEq,
                containerColor = contentColor.copy(alpha = 0.12f),
                contentColor = contentColor,
                size = 56.dp,
            )
        }
        AnimatedWaveform(
            active = true,
            color = contentColor,
            barCount = 15,
            height = 44.dp,
            expand = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusPill("Opus", containerColor = contentColor.copy(alpha = 0.10f), contentColor = contentColor)
            StatusPill("48 kHz", containerColor = contentColor.copy(alpha = 0.10f), contentColor = contentColor)
            StatusPill("自动缓冲", containerColor = contentColor.copy(alpha = 0.10f), contentColor = contentColor)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            QuickActionButton(
                icon = if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                label = if (muted) "取消静音" else "手机静音",
                active = muted,
                onClick = onToggleMute,
            )
            QuickActionButton(
                icon = if (micRunning) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                label = if (micRunning) "关闭麦克风" else "开启麦克风",
                active = micRunning,
                onClick = { onToggleMic(!micRunning) },
            )
            Spacer(Modifier.weight(1f))
            FilledTonalButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("断开")
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                contentColor.copy(alpha = 0.12f)
            },
            contentColor = if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                contentColor
            },
        ),
    ) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
internal fun MixingHero(connectedCount: Int, onStopAll: () -> Unit) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    HeroSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(
                    text = "多设备混音",
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    text = "$connectedCount 台电脑同时播放",
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                )
                Text(
                    text = "在下方设备卡片中分别调节音量与静音。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
            IconBadge(
                icon = Icons.Rounded.SpeakerGroup,
                containerColor = contentColor.copy(alpha = 0.12f),
                contentColor = contentColor,
                size = 56.dp,
            )
        }
        AnimatedWaveform(
            active = connectedCount > 0,
            color = contentColor,
            barCount = 15,
            height = 40.dp,
            expand = true,
        )
        FilledTonalButton(
            onClick = onStopAll,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) {
            Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("全部断开")
        }
    }
}

@Composable
internal fun ConnectingHero(
    stage: String,
    pairingText: String,
    pin: String?,
    host: String,
    onCancel: () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    HeroSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ContainedLoadingIndicator(
                modifier = Modifier.size(56.dp),
                containerColor = contentColor.copy(alpha = 0.12f),
                indicatorColor = contentColor,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("正在连接", style = MaterialTheme.typography.titleLargeEmphasized)
                Text(
                    text = if (pin != null) {
                        "先完成配对，才能开始串流"
                    } else {
                        pairingText.ifBlank { stage.ifBlank { "正在启动会话" } }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                )
            }
        }
        if (pin != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "在 Sunshine 网页输入配对 PIN",
                    style = MaterialTheme.typography.labelLargeEmphasized,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pin.forEach { digit ->
                        Surface(
                            shape = MoonToneShape.badge,
                            color = contentColor.copy(alpha = 0.12f),
                            contentColor = contentColor,
                        ) {
                            Text(
                                text = digit.toString(),
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.displaySmallEmphasized,
                            )
                        }
                    }
                }
                Text(
                    text = "打开 http://${host.ifBlank { "主机地址" }}:47990",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                )
            }
        }
        TextButton(
            onClick = onCancel,
            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        ) {
            Text("取消连接")
        }
    }
}

@Composable
private fun NoticeCard(
    title: String,
    detail: String,
    action: String? = null,
    icon: ImageVector = Icons.Rounded.Info,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MoonToneShape.card,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmallEmphasized)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
                if (action != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    ) { Text(action) }
                }
            }
        }
    }
}

@Composable
private fun EmptyDevicesCard(localNetworkGranted: Boolean, onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MoonToneShape.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconBadge(
                icon = Icons.Rounded.Computer,
                size = 64.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (localNetworkGranted) "正在发现设备" else "尚未获得局域网权限",
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = if (localNetworkGranted) {
                    "在电脑上启动 Sunshine 后会自动出现在这里，也可以直接输入 IP 地址。"
                } else {
                    "允许本地网络访问后即可自动发现电脑。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (!localNetworkGranted) {
                TextButton(onClick = onOpenSettings) { Text("前往设置") }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Controls
// ---------------------------------------------------------------------------

@Composable
private fun ModeSelector(mixMode: Boolean, enabled: Boolean, onSelect: (Boolean) -> Unit) {
    ButtonGroup(
        overflowIndicator = {},
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        toggleableItem(
            checked = !mixMode,
            label = "单设备播放",
            onCheckedChange = { checked -> if (checked) onSelect(false) },
            icon = { Icon(Icons.Rounded.Smartphone, contentDescription = null) },
            weight = 1f,
            enabled = enabled,
        )
        toggleableItem(
            checked = mixMode,
            label = "多设备混音",
            onCheckedChange = { checked -> if (checked) onSelect(true) },
            icon = { Icon(Icons.Rounded.SpeakerGroup, contentDescription = null) },
            weight = 1f,
            enabled = enabled,
        )
    }
}

@Composable
private fun DeviceListCard(
    device: DeviceEntry,
    devices: List<MultiDeviceState>,
    mixMode: Boolean,
    state: MoonToneState,
    currentHost: String,
    singleMuted: Boolean,
    canConnect: Boolean,
    onConnect: (String) -> Unit,
    onMultiConnect: (String) -> Unit,
    onSingleDisconnect: () -> Unit,
    onMultiDisconnect: (Int) -> Unit,
    onToggleMute: () -> Unit,
    onMultiMute: (Int, Boolean) -> Unit,
    onMultiVolume: (Int, Float) -> Unit,
    onSaveToggle: (DeviceEntry) -> Unit,
) {
    val multi = devices.firstOrNull { it.host == device.host }
    val current = !mixMode && state == MoonToneState.CONNECTED && currentHost == device.host
    DeviceRowCard(
        device = device,
        multi = multi,
        current = current,
        singleMuted = singleMuted,
        mixMode = mixMode,
        canConnect = canConnect,
        onConnect = {
            if (mixMode) onMultiConnect(device.host) else onConnect(device.host)
        },
        onDisconnect = {
            if (mixMode && multi != null) onMultiDisconnect(multi.slot) else onSingleDisconnect()
        },
        onMute = {
            if (mixMode && multi != null) onMultiMute(multi.slot, !multi.muted) else onToggleMute()
        },
        onVolume = { if (multi != null) onMultiVolume(multi.slot, it) },
        onSave = { onSaveToggle(device) },
    )
}

@Composable
private fun OfflineGroupHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "offlineChevron",
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MoonToneShape.card)
            .clickable(onClick = onToggle),
        shape = MoonToneShape.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(
                icon = Icons.Rounded.WifiOff,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 40.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("未在线设备", style = MaterialTheme.typography.titleSmallEmphasized)
                Text(
                    text = if (expanded) {
                        "点按收起 · $count 台"
                    } else {
                        "$count 台设备当前未在线，点按展开"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起未在线设备" else "展开未在线设备",
                modifier = Modifier.rotate(rotation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun DeviceRowCard(
    device: DeviceEntry,
    multi: MultiDeviceState?,
    current: Boolean,
    singleMuted: Boolean,
    mixMode: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onMute: () -> Unit,
    onVolume: (Float) -> Unit,
    onSave: () -> Unit,
) {
    val connected = current || multi?.state == MultiConnState.CONNECTED
    val connecting = multi?.state == MultiConnState.CONNECTING
    val failed = multi?.state == MultiConnState.ERROR
    var showErrorDetail by remember(device.host) { mutableStateOf(false) }
    val muted = multi?.muted ?: singleMuted
    val cardColor = if (connected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val cardContent = if (connected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MoonToneShape.card,
        color = cardColor,
        contentColor = cardContent,
        border = if (connected) null else androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconBadge(
                    icon = Icons.Rounded.Computer,
                    containerColor = if (connected) {
                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (connected) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    size = 44.dp,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = device.name.ifBlank { device.host },
                        style = MaterialTheme.typography.titleMediumEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = device.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (connected) {
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val dotColor = when {
                            connected -> MaterialTheme.colorScheme.onSecondaryContainer
                            connecting -> MaterialTheme.colorScheme.tertiary
                            failed -> MaterialTheme.colorScheme.error
                            device.online -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.outline
                        }
                        StatusDot(dotColor)
                        Text(
                            text = when {
                                connected -> "已连接"
                                connecting -> multi?.error ?: "正在连接"
                                failed -> "连接失败"
                                device.online -> "在线"
                                else -> "离线"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (failed) {
                                MaterialTheme.colorScheme.error
                            } else if (connected) {
                                MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (connected) {
                    FilledTonalIconButton(
                        onClick = onMute,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f),
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    ) {
                        Icon(
                            if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                            contentDescription = if (muted) "取消手机静音" else "手机静音",
                        )
                    }
                } else if (connecting) {
                    ContainedLoadingIndicator(
                        modifier = Modifier.size(30.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    )
                } else if (failed) {
                    FilledTonalButton(onClick = onConnect, enabled = canConnect) { Text("重连") }
                } else {
                    FilledTonalButton(onClick = onConnect, enabled = canConnect) {
                        Text(if (mixMode) "加入混音" else "连接")
                    }
                }
            }

            if (connected) {
                if (multi != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Rounded.VolumeUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp),
                        )
                        Slider(
                            value = multi.volume,
                            onValueChange = onVolume,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                activeTrackColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                inactiveTrackColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.24f),
                            ),
                        )
                    }
                } else {
                    Text(
                        text = "音量跟随系统",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = if (multi != null) "混音会话" else "单设备会话",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.4f),
                        ),
                    ) { Text("断开") }
                }
            } else if (connecting) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "正在建立混音会话…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDisconnect) { Text("断开") }
                }
            } else if (failed) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = localizedConnectionError(multi?.error ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (showErrorDetail && !multi?.error.isNullOrBlank()) {
                        Text(
                            text = multi.error.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(onClick = { showErrorDetail = !showErrorDetail }) {
                            Text(if (showErrorDetail) "收起详情" else "查看详情")
                        }
                        Spacer(Modifier.weight(1f))
                        OutlinedButton(onClick = onDisconnect) { Text("断开") }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (device.saved) "已保存到设备列表" else "发现于局域网",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onSave) {
                        Icon(
                            if (device.saved) Icons.Rounded.BookmarkRemove else Icons.Rounded.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (device.saved) "移除" else "保存")
                    }
                }
            }
        }
    }
}

@Composable
private fun IpConnectCard(
    host: String,
    mixMode: Boolean,
    enabled: Boolean,
    onHostChange: (String) -> Unit,
    onConnect: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "ipChevron",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MoonToneShape.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconBadge(
                    icon = Icons.Rounded.Link,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    size = 44.dp,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("通过 IP 连接", style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(
                        "手动输入 Sunshine 主机地址",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
                exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = onHostChange,
                        label = { Text("Sunshine 主机地址") },
                        singleLine = true,
                        enabled = enabled,
                        shape = MoonToneShape.input,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = onConnect,
                        enabled = host.isNotBlank() && enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = MoonToneShape.pill,
                    ) {
                        Text(if (mixMode) "加入混音" else "连接")
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Small decorative helpers
// ---------------------------------------------------------------------------

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Crescent moon with two tiny stars, drawn from the theme's tonal colours. */
@Composable
private fun MoonGlyph(size: androidx.compose.ui.unit.Dp) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val glow = if (dark) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.secondaryContainer
    val cutout = MaterialTheme.colorScheme.primaryContainer
    val sparkle = if (dark) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.tertiaryContainer
    Canvas(modifier = Modifier.size(size)) {
        val min = this.size.minDimension
        val r = min / 2f
        drawCircle(color = glow, radius = r, center = center)
        drawCircle(
            color = cutout,
            radius = r * 0.84f,
            center = Offset(center.x + r * 0.44f, center.y - r * 0.20f),
        )
        drawCircle(
            color = sparkle,
            radius = r * 0.075f,
            center = Offset(this.size.width * 0.22f, this.size.height * 0.24f),
        )
        drawCircle(
            color = sparkle,
            radius = r * 0.045f,
            center = Offset(this.size.width * 0.16f, this.size.height * 0.42f),
        )
    }
}

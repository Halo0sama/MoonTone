package com.halo.moontone.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SyncAlt
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.Update
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.halo.moontone.BuildConfig
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.data.AppPreferences
import com.halo.moontone.data.HostAudioPreferences
import com.halo.moontone.data.SavedHost
import com.halo.moontone.ui.components.*
import com.halo.moontone.ui.theme.MoonToneShape
import com.halo.moontone.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings: appearance (theme mode / dynamic colour), playback, permissions, diagnostics and app
 * info. Following the "Aurora Night" contract the page is a sequence of generous tonal cards with
 * one bold intro block, expressive type and a tonal icon badge per row.
 *
 * Behaviour is unchanged from the previous M3 version: preferences are read through
 * [preferenceRevision] (which listens to both the app and host-audio preference files), the mic
 * toggle mirrors the live controller state, certificate export writes to external storage and the
 * clipboard, and update checks stay delegated to [MainActivity].
 */
@Composable
internal fun SettingsScreen(
    modifier: Modifier,
    activity: MainActivity,
    hosts: List<SavedHost>,
    localNetworkGranted: Boolean,
    micRunning: Boolean,
    singleConnected: Boolean,
    currentHost: String,
    onMicChange: (Boolean) -> Unit,
    onNavigate: (String) -> Unit,
    controller: MoonToneController,
) {
    val context = LocalContext.current
    val revision = preferenceRevision(context)
    val defaultHostAudio = remember(revision) { AppPreferences.playOnHostByDefault(context) }
    val checkUpdates = remember(revision) { AppPreferences.checkUpdatesOnLaunch(context) }
    val overrides = remember(revision, hosts) { HostAudioPreferences.preferences(context).all.size }
    val themeMode = remember(revision) { ThemeMode.fromStorage(AppPreferences.themeMode(context)) }
    val dynamicColor = remember(revision) { AppPreferences.dynamicColor(context) }
    val permissionRevision = activity.permissionRevision
    val notificationsGranted = remember(permissionRevision) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf(false) }
    Box(modifier) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item { SettingsIntro() }
            item {
                SettingsGroup("外观") {
                    ThemeModeSelector(selected = themeMode) { mode ->
                        AppPreferences.setThemeMode(context, mode.storageValue)
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        CardDivider()
                        SettingsToggle(
                            title = "跟随壁纸动态取色",
                            detail = "Android 12+ 使用系统壁纸配色；关闭时使用 MoonTone 品牌配色。",
                            checked = dynamicColor,
                            icon = Icons.Rounded.Wallpaper,
                            iconTint = MaterialTheme.colorScheme.primaryContainer,
                            iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ) { AppPreferences.setDynamicColor(context, it) }
                    }
                }
            }
            item {
                SettingsGroup("播放") {
                    SettingsToggle(
                        title = "主机同时播放声音",
                        detail = "未单独设置的电脑使用此选项；连接或重连后生效。",
                        checked = defaultHostAudio,
                        icon = Icons.AutoMirrored.Rounded.VolumeUp,
                    ) { AppPreferences.setPlayOnHostByDefault(context, it) }
                    SettingsDivider()
                    SettingsAction(
                        title = "设备声音例外",
                        detail = if (overrides == 0) "所有电脑均跟随默认设置" else "$overrides 台电脑已单独设置",
                        icon = Icons.Rounded.GraphicEq,
                    ) { onNavigate(AppPage.HOST_AUDIO) }
                    SettingsDivider()
                    SettingsToggle(
                        title = "麦克风上行",
                        detail = when {
                            micRunning ->
                                if (currentHost.isBlank()) "正在回传手机麦克风" else "正在将手机麦克风发送到 $currentHost"
                            singleConnected -> "发送到 $currentHost；开启时申请麦克风权限。"
                            else -> "单设备连接后可开启，回传到当前电脑。"
                        },
                        checked = micRunning,
                        enabled = micRunning || singleConnected,
                        icon = Icons.Rounded.Mic,
                    ) { onMicChange(it) }
                    SettingsDivider()
                    SettingsAction(
                        title = "音频信息",
                        detail = "编码、缓冲策略与系统输出",
                        icon = Icons.Rounded.Equalizer,
                    ) { onNavigate(AppPage.AUDIO_INFO) }
                }
            }
            item {
                SettingsGroup("连接与权限") {
                    if (Build.VERSION.SDK_INT >= 37) {
                        SettingsAction(
                            title = "局域网访问",
                            detail = if (localNetworkGranted) "已允许" else "未允许，点击申请",
                            icon = Icons.Rounded.Shield,
                        ) {
                            if (localNetworkGranted) openAppSettings(context) else activity.requestLocalNetworkPermission()
                        }
                        SettingsDivider()
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        SettingsAction(
                            title = "播放通知",
                            detail = if (notificationsGranted) "已允许" else "未允许，点击申请",
                            icon = Icons.Rounded.Notifications,
                        ) {
                            if (notificationsGranted) openAppSettings(context) else activity.requestNotificationPermission()
                        }
                        SettingsDivider()
                    }
                    SettingsAction(
                        title = "系统应用设置",
                        detail = "管理权限、通知和后台运行",
                        icon = Icons.Rounded.Settings,
                    ) { openAppSettings(context) }
                }
            }
            item {
                SettingsGroup("高级与诊断") {
                    SettingsAction(
                        title = "高级连接",
                        detail = "使用已有的 RTSP 会话地址与密钥",
                        icon = Icons.Rounded.Link,
                    ) { onNavigate(AppPage.MANUAL) }
                    SettingsDivider()
                    SettingsAction(
                        title = "运行日志",
                        detail = "查看最近的连接和音频记录",
                        icon = Icons.AutoMirrored.Rounded.Article,
                    ) { onNavigate(AppPage.LOGS) }
                    SettingsDivider()
                    SettingsAction(
                        title = if (exporting) "正在导出证书" else "导出客户端证书",
                        detail = "导出公钥证书并复制到剪贴板，不包含私钥。",
                        icon = Icons.Rounded.Verified,
                        enabled = !exporting,
                    ) {
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
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("MoonTone 公钥证书", exported.second))
                                "证书已复制，文件已保存到 ${exported.first}"
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.message ?: "导出失败"
                            } finally {
                                exporting = false
                            }
                            snackbar.showSnackbar(message)
                        }
                    }
                }
            }
            item {
                SettingsGroup("应用") {
                    SettingsToggle(
                        title = "启动时检查更新",
                        detail = "有新版本时提示，不会自动下载安装。",
                        checked = checkUpdates,
                        icon = Icons.Rounded.Update,
                        iconTint = MaterialTheme.colorScheme.secondaryContainer,
                        iconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) { AppPreferences.setCheckUpdatesOnLaunch(context, it) }
                    SettingsDivider()
                    SettingsAction(
                        title = if (activity.checkingUpdate) "正在检查更新" else "检查更新",
                        detail = "从 GitHub Releases 获取版本信息",
                        icon = Icons.Rounded.SystemUpdateAlt,
                        enabled = !activity.checkingUpdate,
                    ) { activity.checkForAppUpdate(reportResult = true) }
                    SettingsDivider()
                    VersionFooter()
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

/** One bold block at the top of Settings so the screen has a clear expressive hero. */
@Composable
private fun SettingsIntro() {
    MoonToneCard(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(
                icon = Icons.Rounded.Palette,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                size = 48.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "外观与播放",
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/**
 * Three expressive pills acting as a radio group for the app theme, laid out horizontally
 * (icon + label) to match Home's [androidx.compose.material3.ButtonGroup] mode selector.
 *
 * A custom row is used instead of ButtonGroup itself because ButtonGroup items use the
 * `ButtonWithIconContentPadding` (16/24 dp) and overflow a 360 dp screen, silently moving the third
 * option into the overflow menu. Here the pills use 6 dp inner padding: at 360 dp the 280 dp control
 * row gives ~88 dp per pill and the widest label ("跟随系统": 18 dp icon + 6 dp gap + 48 dp at 12 sp
 * + 12 dp padding ≈ 84 dp) still fits.
 */
@Composable
private fun ThemeModeSelector(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ThemeMode.entries.forEach { mode ->
                ThemeModePill(
                    mode = mode,
                    selected = mode == selected,
                    modifier = Modifier.weight(1f),
                ) { onSelect(mode) }
            }
        }

    }
}

@Composable
private fun ThemeModePill(
    mode: ThemeMode,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "themeModeContainer",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "themeModeContent",
    )
    Surface(
        modifier = modifier.pressScale(interactionSource),
        shape = MoonToneShape.pill,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(horizontal = 6.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = mode.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "跟随系统"
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
    }

private val ThemeMode.icon: ImageVector
    get() = when (this) {
        ThemeMode.SYSTEM -> Icons.Rounded.BrightnessAuto
        ThemeMode.LIGHT -> Icons.Rounded.LightMode
        ThemeMode.DARK -> Icons.Rounded.DarkMode
    }

/** Divider inset for content that is not a [SettingsAction]/[SettingsToggle] row. */
@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** App identity footer with a tonal version badge instead of a plain text block. */
@Composable
private fun VersionFooter() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconBadge(
            icon = Icons.Rounded.MusicNote,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            size = 40.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("MoonTone", style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                text = "电脑音频 → 手机播放",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(
            text = "版本 ${BuildConfig.VERSION_NAME}",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/**
 * Per-host audio override screen. The global default is shown as a tinted summary card; every saved
 * (or previously overridden) host becomes a tappable card that opens a modal bottom sheet with the
 * three override options.
 */
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
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { DefaultHostAudioCard(defaultOn = defaultOn) }
        if (allHosts.isEmpty()) {
            item { EmptyHostsCard() }
        } else {
            item {
                MoonToneSectionHeader("按电脑单独设置", Modifier.padding(start = 4.dp, top = 6.dp))
            }
            items(allHosts, key = { it.host }) { host ->
                val override = remember(host.host, revision) {
                    HostAudioPreferences.overrideForHost(context, host.host)
                }
                HostAudioCard(host = host, override = override) { editing = host }
            }
        }
    }
    editing?.let { host ->
        val current = HostAudioPreferences.overrideForHost(context, host.host)
        HostAudioOptionSheet(
            host = host,
            current = current,
            defaultOn = defaultOn,
            onSelect = { value ->
                if (value == null) {
                    HostAudioPreferences.followDefault(context, host.host)
                } else {
                    HostAudioPreferences.setPlayOnHost(context, host.host, value)
                }
            },
            onDismiss = { editing = null },
        )
    }
}

/** Global default summary. Header copy intentionally identical to the pre-redesign screen. */
@Composable
private fun DefaultHostAudioCard(defaultOn: Boolean) {
    val containerColor =
        if (defaultOn) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
    val contentColor =
        if (defaultOn) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface
    val detailColor =
        if (defaultOn) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    MoonToneCard(containerColor = containerColor, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(
                icon = Icons.Rounded.Speaker,
                containerColor =
                    if (defaultOn) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor =
                    if (defaultOn) MaterialTheme.colorScheme.onSecondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 48.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "默认：${if (defaultOn) "主机与手机同时播放" else "只在手机播放"}",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = contentColor,
                )
                Text(
                    text = "单独设置会覆盖默认，连接或重连后应用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = detailColor,
                )
            }
        }
    }
}

@Composable
private fun EmptyHostsCard() {
    MoonToneCard(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(
                icon = Icons.Rounded.Computer,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 44.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("暂无设备", style = MaterialTheme.typography.bodyLargeEmphasized)
                Text(
                    text = "保存电脑后可在这里设置例外。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Tappable host card with the current override shown as a colour-coded status pill. */
@Composable
private fun HostAudioCard(host: SavedHost, override: Boolean?, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pillContainer = when (override) {
        true -> MaterialTheme.colorScheme.tertiaryContainer
        false -> MaterialTheme.colorScheme.secondaryContainer
        null -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val pillContent = when (override) {
        true -> MaterialTheme.colorScheme.onTertiaryContainer
        false -> MaterialTheme.colorScheme.onSecondaryContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val pillIcon = when (override) {
        true -> Icons.Rounded.Speaker
        false -> Icons.Rounded.Smartphone
        null -> Icons.Rounded.SyncAlt
    }
    val pillText = when (override) {
        true -> "主机与手机同时播放"
        false -> "只在手机播放"
        null -> "跟随默认"
    }
    MoonToneCard(
        modifier = Modifier
            .pressScale(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(
                icon = host.icon,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                size = 44.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = host.name.ifBlank { host.host },
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = host.host,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusPill(
            text = pillText,
            containerColor = pillContainer,
            contentColor = pillContent,
            leading = {
                Icon(
                    imageVector = pillIcon,
                    contentDescription = null,
                    tint = pillContent,
                    modifier = Modifier.size(14.dp),
                )
            },
        )
    }
}

/** Computer vs TV badge: purely decorative, guessed from the saved display name. */
private val SavedHost.icon: ImageVector
    get() {
        val looksLikeTv = name.contains("TV", ignoreCase = true) || name.contains("电视")
        return if (looksLikeTv) Icons.Rounded.Tv else Icons.Rounded.Computer
    }

/**
 * Modal bottom sheet with the three per-host options. In material3 1.5.0-alpha29
 * `rememberModalBottomSheetState` is deprecated; [rememberBottomSheetState] is the replacement and
 * its default enabled values match the old `skipPartiallyExpanded = false` behaviour (partially
 * expanded stays available).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostAudioOptionSheet(
    host: SavedHost,
    current: Boolean?,
    defaultOn: Boolean,
    onSelect: (Boolean?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
    val dismissWith: (Boolean?) -> Unit = { value ->
        onSelect(value)
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MoonToneShape.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconBadge(
                    icon = host.icon,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    size = 48.dp,
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = host.name.ifBlank { host.host },
                        style = MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = host.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = "这台电脑的播放方式",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            HostAudioOptionRow(
                selected = current == null,
                icon = Icons.Rounded.SyncAlt,
                title = "跟随默认",
                description = "使用全局默认：${if (defaultOn) "主机与手机同时播放" else "只在手机播放"}",
            ) { dismissWith(null) }
            HostAudioOptionRow(
                selected = current == true,
                icon = Icons.Rounded.Speaker,
                title = "主机与手机同时播放",
                description = "电脑和手机同时出声，适合跟听。",
            ) { dismissWith(true) }
            HostAudioOptionRow(
                selected = current == false,
                icon = Icons.Rounded.Smartphone,
                title = "只在手机播放",
                description = "电脑保持静音，只从手机出声。",
            ) { dismissWith(false) }
        }
    }
}

@Composable
private fun HostAudioOptionRow(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue =
            if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "hostAudioOptionContainer",
    )
    val titleColor =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface
    val detailColor =
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interactionSource),
        shape = MoonToneShape.card,
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            IconBadge(
                icon = icon,
                containerColor =
                    if (selected) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor =
                    if (selected) MaterialTheme.colorScheme.onSecondary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 40.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(text = title, style = MaterialTheme.typography.bodyLargeEmphasized, color = titleColor)
                Text(text = description, style = MaterialTheme.typography.bodySmall, color = detailColor)
            }
            Spacer(Modifier.width(2.dp))
            HostAudioSelectionIndicator(selected = selected)
        }
    }
}

/**
 * Bold radio-style selection indicator for the host-audio sheet.
 *
 * Material's [RadioButton] draws a fixed 20 dp / 2 dp ring that reads very thin once a screenshot is
 * downscaled (it measured poorly against the pale row in visual QA even though the role colour
 * `onSurfaceVariant` is 8.5:1 on `surfaceContainerLow`). This variant keeps the same semantics — the
 * row is a `Role.RadioButton` selectable — but draws a 26 dp / 2.5 dp ring in
 * [MaterialTheme.colorScheme.onSurfaceVariant] and a filled `primary` disc with an `onPrimary` check
 * when selected, so both states read clearly in light and dark and match the primary selected
 * treatment of Home's ButtonGroup and the 外观 pills.
 */
@Composable
private fun HostAudioSelectionIndicator(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(26.dp)
            .then(
                if (selected) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = CircleShape,
                    )
                } else {
                    Modifier.border(
                        width = 2.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        shape = CircleShape,
                    )
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Recomputes on any app/host-audio preference change, including theme mode and dynamic colour. */
@Composable
private fun preferenceRevision(context: Context): Int {
    val app = remember(context) { AppPreferences.preferences(context) }
    val host = remember(context) { HostAudioPreferences.preferences(context) }
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(app, host) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> revision++ }
        app.registerOnSharedPreferenceChangeListener(listener)
        host.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            app.unregisterOnSharedPreferenceChangeListener(listener)
            host.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    return revision
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ),
    )
}

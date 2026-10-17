package com.halo.moontone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.halo.moontone.control.MoonToneController
import com.halo.moontone.log.MoonToneLog
import com.halo.moontone.ui.components.IconBadge
import com.halo.moontone.ui.components.MoonToneCard
import com.halo.moontone.ui.components.MoonToneSectionHeader
import com.halo.moontone.ui.components.StatusPill
import com.halo.moontone.ui.components.pressScale
import com.halo.moontone.ui.theme.MoonToneShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Advanced / diagnostic screens: manual session connection, run log viewer, audio info.
 *
 * All three follow the MoonTone Expressive language: flat tonal cards (28 dp), one loud element per
 * screen, pill actions and emphasized type. Shared building blocks come from
 * `com.halo.moontone.ui.components`; see docs/UI-REDESIGN.md.
 */

/** Parses a 32-character hex session key. Empty input means "all zero" (Sunshine default). */
internal fun parseSessionKey(value: String, field: String): ByteArray {
    val hex = value.trim()
    if (hex.isEmpty()) return ByteArray(16)
    require(hex.matches(Regex("[0-9a-fA-F]{32}"))) { "$field 需要 32 位十六进制字符" }
    return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

// ---------------------------------------------------------------------------------------------
// Manual connection
// ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ManualConnectionScreen(
    modifier: Modifier,
    controller: MoonToneController,
    initialHost: String,
    onConnected: () -> Unit,
) {
    var host by rememberSaveable { mutableStateOf(initialHost) }
    // Session secrets remain only in memory and are cleared when this page leaves.
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var iv by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        ManualHero()

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoonToneSectionHeader("主机")
            MoonToneCard {
                MoonToneTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = "Sunshine 主机地址",
                    supporting = "局域网 IP 或电脑名，例如 192.168.1.20",
                    enabled = !busy,
                    keyboardType = KeyboardType.Uri,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoonToneSectionHeader("会话参数")
            MoonToneCard {
                MoonToneTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = "RTSP 会话地址",
                    supporting = "由发起会话的工具提供，可留空自动获取",
                    enabled = !busy,
                    keyboardType = KeyboardType.Uri,
                )
                MoonToneTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = "riKey",
                    supporting = "32 位十六进制字符，可留空",
                    enabled = !busy,
                    password = true,
                )
                MoonToneTextField(
                    value = iv,
                    onValueChange = { iv = it },
                    label = "riKeyId",
                    supporting = "32 位十六进制字符，可留空",
                    enabled = !busy,
                    password = true,
                )
            }
        }

        error?.let { message -> InlineErrorCard(message) }

        Button(
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        val sessionKey = parseSessionKey(key, "riKey")
                        val sessionIv = parseSessionKey(iv, "riKeyId")
                        controller.connectManually(host.trim(), url.trim(), sessionKey, sessionIv)
                        key = ""; iv = ""
                        onConnected()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        error = e.message ?: "连接失败"
                    } finally {
                        busy = false
                    }
                }
            },
            enabled = !busy && host.isNotBlank() && url.isNotBlank(),
            shape = MoonToneShape.pill,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        ) {
            if (busy) {
                ContainedLoadingIndicator(
                    modifier = Modifier.size(22.dp),
                    containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                    indicatorColor = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text = if (busy) "正在连接" else "连接手动会话",
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** Violet tonal intro block: what this page is for in two short lines. */
@Composable
private fun ManualHero() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MoonToneShape.hero,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IconBadge(
                    icon = Icons.Rounded.Link,
                    contentDescription = null,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    size = 44.dp,
                )
                Text(
                    text = "连接已有会话",
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = "连接由其他工具启动的 Sunshine 会话。普通连接请使用首页设备列表。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = "主机声音由发起会话的工具设置，页面关闭后不会保留会话密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
            )
        }
    }
}

/** Rounded expressive outlined input with supporting text, matching the 20 dp input shape. */
@Composable
private fun MoonToneTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String,
    enabled: Boolean,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled,
        singleLine = true,
        label = { Text(label) },
        supportingText = { Text(supporting) },
        shape = MoonToneShape.input,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (password) KeyboardType.Password else keyboardType,
        ),
        visualTransformation =
            if (password) PasswordVisualTransformation() else VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outlineVariant,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            cursorColor = scheme.primary,
            focusedSupportingTextColor = scheme.onSurfaceVariant,
            unfocusedSupportingTextColor = scheme.onSurfaceVariant.copy(alpha = 0.8f),
        ),
    )
}

/** Inline error block: error-container tone instead of red text on the page background. */
@Composable
private fun InlineErrorCard(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MoonToneShape.input,
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Run log viewer
// ---------------------------------------------------------------------------------------------

@Composable
internal fun LogsScreen(modifier: Modifier) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(MoonToneLog.tail(150)) }
    val lineCount = remember(logs) { if (logs.isBlank()) 0 else logs.lines().size }

    val refresh: () -> Unit = { logs = MoonToneLog.tail(150) }
    val copyLogs: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MoonTone 日志", logs))
        Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "最近日志",
                    style = MaterialTheme.typography.titleMediumEmphasized,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusPill(
                        text = "$lineCount 行",
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "最多保留 150 行",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Content-adaptive panel: short logs get a compact card, long logs cap at 420 dp and
            // scroll internally. fill = false lets the Surface shrink below the remaining space.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 160.dp, max = 420.dp),
                shape = MoonToneShape.card,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                ),
            ) {
                if (logs.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Article,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                text = "暂无日志",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                } else {
                    SelectionContainer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 24.dp),
                    ) {
                        Text(
                            text = logs,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Floating pill toolbar, bottom-center: quick 刷新 / 复制 without leaving the log panel.
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
            shape = MoonToneShape.pill,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LogToolbarAction(
                    icon = Icons.Rounded.Refresh,
                    label = "刷新日志",
                    onClick = refresh,
                )
                LogToolbarAction(
                    icon = Icons.Rounded.ContentCopy,
                    label = "复制日志",
                    emphasized = true,
                    onClick = copyLogs,
                )
            }
        }
    }
}

/** One pill action inside the floating log toolbar. */
@Composable
private fun LogToolbarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    emphasized: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val container = if (emphasized) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        Color.Transparent
    }
    val contentColor = if (emphasized) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .pressScale(interaction)
            .clip(MoonToneShape.pill)
            .background(container)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Audio info
// ---------------------------------------------------------------------------------------------

@Composable
internal fun AudioInfoScreen(modifier: Modifier, micRunning: Boolean) {
    val context = LocalContext.current
    fun outputs(): String {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .map { it.productName.toString() }
            .distinct()
            .joinToString("、")
            .ifBlank { "系统默认输出" }
    }

    var available by remember { mutableStateOf(outputs()) }
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoonToneSectionHeader("音频串流")

            // 2x2 expressive metric grid. Rows use IntrinsicSize.Min so both tiles match height.
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AudioMetricTile(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Rounded.GraphicEq,
                        label = "解码",
                        value = "Opus",
                        detail = "实时音频解码",
                        containerColor = scheme.primaryContainer,
                        contentColor = scheme.onPrimaryContainer,
                        badgeContainerColor = scheme.secondaryContainer,
                        badgeContentColor = scheme.onSecondaryContainer,
                    )
                    AudioMetricTile(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Rounded.MusicNote,
                        label = "PCM 格式",
                        value = "48 kHz",
                        detail = "双声道 · 16 bit",
                        containerColor = scheme.secondaryContainer,
                        contentColor = scheme.onSecondaryContainer,
                        badgeContainerColor = scheme.primaryContainer,
                        badgeContentColor = scheme.onPrimaryContainer,
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AudioMetricTile(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = Icons.Rounded.Speed,
                        label = "缓冲策略",
                        value = "20–600 ms",
                        detail = "自动适应网络抖动",
                        containerColor = scheme.tertiaryContainer,
                        contentColor = scheme.onTertiaryContainer,
                        badgeContainerColor = scheme.primaryContainer,
                        badgeContentColor = scheme.onPrimaryContainer,
                    )
                    AudioMetricTile(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        icon = if (micRunning) Icons.Rounded.Mic else Icons.Rounded.MicOff,
                        label = "麦克风上行",
                        value = if (micRunning) "运行中" else "未开启",
                        detail = if (micRunning) "正在采集手机麦克风" else "可在设置中开启",
                        containerColor = if (micRunning) {
                            scheme.secondaryContainer
                        } else {
                            scheme.surfaceContainerHigh
                        },
                        contentColor = if (micRunning) {
                            scheme.onSecondaryContainer
                        } else {
                            scheme.onSurfaceVariant
                        },
                        badgeContainerColor = if (micRunning) {
                            scheme.tertiaryContainer
                        } else {
                            scheme.primaryContainer
                        },
                        badgeContentColor = if (micRunning) {
                            scheme.onTertiaryContainer
                        } else {
                            scheme.onPrimaryContainer
                        },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoonToneSectionHeader("系统输出")
            MoonToneCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    IconBadge(
                        icon = Icons.Rounded.Headphones,
                        contentDescription = null,
                        containerColor = scheme.secondaryContainer,
                        contentColor = scheme.onSecondaryContainer,
                        size = 44.dp,
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = "检测到的输出设备",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            text = available,
                            style = MaterialTheme.typography.bodyLargeEmphasized,
                        )
                    }
                }
                Text(
                    text = "实际输出跟随系统，可在手机的音量面板中切换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    FilledTonalButton(
                        onClick = { available = outputs() },
                        shape = MoonToneShape.pill,
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("刷新输出信息")
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

/** One tonal metric tile: icon badge, quiet label, loud emphasized value + detail. */
@Composable
private fun AudioMetricTile(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    detail: String,
    containerColor: Color,
    contentColor: Color,
    badgeContainerColor: Color,
    badgeContentColor: Color,
) {
    Surface(
        modifier = modifier,
        shape = MoonToneShape.card,
        color = containerColor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconBadge(
                    icon = icon,
                    contentDescription = null,
                    containerColor = badgeContainerColor,
                    contentColor = badgeContentColor,
                    size = 32.dp,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMediumEmphasized,
                    color = contentColor.copy(alpha = 0.75f),
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmallEmphasized,
                color = contentColor,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.72f),
            )
        }
    }
}

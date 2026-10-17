package com.halo.moontone.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.halo.moontone.ui.theme.MoonToneShape
import kotlin.math.abs
import kotlin.math.sin

/**
 * Shared Material 3 Expressive building blocks for MoonTone.
 *
 * Visual contract: bold rounded surfaces (28 dp cards / 36 dp hero), tonal colour blocks instead
 * of borders/elevation, emphasized type for the few loud elements, expressive spring motion for
 * press feedback. See docs/UI-REDESIGN.md.
 */

/** Section label above a group of cards, e.g. "播放", "附近的电脑". */
@Composable
internal fun MoonToneSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke(this)
    }
}

/** Flat tonal card used for almost every content block. */
@Composable
internal fun MoonToneCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    shape: androidx.compose.ui.graphics.Shape = MoonToneShape.card,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = containerColor,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/** Small squircle icon tile. Uses a Material 3 Expressive "cookie" shape by default. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    size: Dp = 44.dp,
    roundness: Boolean = false,
) {
    val shape = if (roundness) MoonToneShape.pill else MaterialShapes.Cookie4Sided.toShape()
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(size * 0.52f),
        )
    }
}

/** Pill-shaped status/metadata chip, e.g. "已连接", "Opus", "48 kHz". */
@Composable
internal fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(modifier = modifier, shape = MoonToneShape.pill, color = containerColor) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            leading?.invoke()
            Text(
                text = text,
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Animated equalizer bars used by the Home hero card. Idle bars rest low; when [active] the bars
 * breathe with a sine wave. Colour is passed by the caller to match the hero colour block.
 */
@Composable
internal fun AnimatedWaveform(
    active: Boolean,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    barCount: Int = 5,
    barWidth: Dp = 5.dp,
    gap: Dp = 5.dp,
    height: Dp = 34.dp,
    expand: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    val activeFactor by animateFloatAsState(
        targetValue = if (active) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
        label = "active",
    )
    val canvasModifier = if (expand) {
        modifier.fillMaxWidth().height(height)
    } else {
        modifier.size(
            width = barWidth * barCount + gap * (barCount - 1),
            height = height,
        )
    }
    Canvas(modifier = canvasModifier) {
        val gapPx = gap.toPx()
        val widthPx = if (expand) {
            ((size.width - gapPx * (barCount - 1)) / barCount).coerceAtLeast(1f)
        } else {
            barWidth.toPx()
        }
        val heightPx = size.height
        repeat(barCount) { index ->
            val wave = abs(sin(phase + index * 0.95f))
            val level = 0.22f + 0.78f * wave
            // Idle bars keep a varied "equalizer at rest" silhouette instead of a flat line.
            val idle = 0.14f + 0.34f * abs(sin(index * 1.15f + phase * 0.15f))
            val barHeight = heightPx * (idle + (level - idle) * activeFactor)
            val x = index * (widthPx + gapPx)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, (heightPx - barHeight) / 2f),
                size = Size(widthPx, barHeight),
                cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f),
            )
        }
    }
}

/** Section wrapper with a label + rounded tonal card, used by Settings and Audio info. */
@Composable
internal fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MoonToneSectionHeader(title)
        Surface(
            shape = MoonToneShape.card,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
        ) {
            Column(content = content)
        }
    }
}

/** Inset divider matching the 70 dp icon gutter of [SettingsAction]. */
@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 70.dp, end = 20.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** Tappable settings row with leading tonal icon badge and chevron. */
@Composable
internal fun SettingsAction(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.secondaryContainer,
    iconColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    trailingText: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            IconBadge(
                icon = icon,
                containerColor = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
                contentColor = if (enabled) iconColor else iconColor.copy(alpha = 0.5f),
                size = 40.dp,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f),
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.labelMediumEmphasized,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f),
        )
    }
}

/** Settings row with a trailing [Switch]. */
@Composable
internal fun SettingsToggle(
    title: String,
    detail: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.tertiaryContainer,
    iconColor: Color = MaterialTheme.colorScheme.onTertiaryContainer,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            IconBadge(
                icon = icon,
                containerColor = if (enabled) iconTint else iconTint.copy(alpha = 0.4f),
                contentColor = if (enabled) iconColor else iconColor.copy(alpha = 0.5f),
                size = 40.dp,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.6f),
            )
        }
        Spacer(Modifier.width(2.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** Read-only label/value row (Audio info). */
@Composable
internal fun SettingsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) {
            IconBadge(icon = icon, size = 40.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLargeEmphasized)
        }
    }
}

/**
 * Springy press feedback for custom surfaces: shrinks slightly while pressed using the
 * expressive fast-spatial motion spec. Pass the same interaction source to `clickable`.
 */
@Composable
internal fun Modifier.pressScale(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.96f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Convenience: rounded tonal surface for one-off blocks. */
@Composable
internal fun MoonToneTonalSurface(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier, shape = shape, color = containerColor) {
        Box(modifier = Modifier.padding(contentPadding)) { content() }
    }
}

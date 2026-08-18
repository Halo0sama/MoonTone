package com.halo.moontone.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val MoonToneDarkColors = darkColorScheme(
    primary = MoonTonePrimaryDark,
    onPrimary = MoonToneOnPrimaryDark,
    primaryContainer = MoonTonePrimaryContainerDark,
    onPrimaryContainer = MoonToneOnPrimaryContainerDark,
    secondary = MoonToneSecondaryDark,
    onSecondary = MoonToneOnSecondaryDark,
    secondaryContainer = MoonToneSecondaryContainerDark,
    onSecondaryContainer = MoonToneOnSecondaryContainerDark,
    tertiary = MoonToneTertiaryDark,
    onTertiary = MoonToneOnTertiaryDark,
    tertiaryContainer = MoonToneTertiaryContainerDark,
    onTertiaryContainer = MoonToneOnTertiaryContainerDark,
    error = MoonToneErrorDark,
    onError = MoonToneOnErrorDark,
    errorContainer = MoonToneErrorContainerDark,
    onErrorContainer = MoonToneOnErrorContainerDark,
    background = MoonToneBackgroundDark,
    onBackground = MoonToneOnBackgroundDark,
    surface = MoonToneSurfaceDark,
    onSurface = MoonToneOnSurfaceDark,
    surfaceVariant = MoonToneSurfaceVariantDark,
    onSurfaceVariant = MoonToneOnSurfaceVariantDark,
    outline = MoonToneOutlineDark
)

private val MoonToneLightColors = lightColorScheme(
    primary = MoonTonePrimaryLight,
    onPrimary = MoonToneOnPrimaryLight,
    primaryContainer = MoonTonePrimaryContainerLight,
    onPrimaryContainer = MoonToneOnPrimaryContainerLight,
    secondary = MoonToneSecondaryLight,
    onSecondary = MoonToneOnSecondaryLight,
    secondaryContainer = MoonToneSecondaryContainerLight,
    onSecondaryContainer = MoonToneOnSecondaryContainerLight,
    tertiary = MoonToneTertiaryLight,
    onTertiary = MoonToneOnTertiaryLight,
    tertiaryContainer = MoonToneTertiaryContainerLight,
    onTertiaryContainer = MoonToneOnTertiaryContainerLight,
    error = MoonToneErrorLight,
    onError = MoonToneOnErrorLight,
    errorContainer = MoonToneErrorContainerLight,
    onErrorContainer = MoonToneOnErrorContainerLight,
    background = MoonToneBackgroundLight,
    onBackground = MoonToneOnBackgroundLight,
    surface = MoonToneSurfaceLight,
    onSurface = MoonToneOnSurfaceLight,
    surfaceVariant = MoonToneSurfaceVariantLight,
    onSurfaceVariant = MoonToneOnSurfaceVariantLight,
    outline = MoonToneOutlineLight
)

@Composable
fun MoonToneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> MoonToneDarkColors
        else -> MoonToneLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MoonToneTypography,
        content = content
    )
}

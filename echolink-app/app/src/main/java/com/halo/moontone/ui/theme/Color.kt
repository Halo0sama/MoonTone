package com.halo.moontone.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * MoonTone "Aurora Night" brand palette for Material 3 Expressive.
 *
 * Roles were generated with Material Color Utilities (expressive variant, 2025 spec) from
 * three brand seeds and then normalised so every role keeps a comfortable contrast pair:
 *
 *  - primary   violet  (#6C5CE7 seed)  — moonlight / brand
 *  - secondary sky blue (#4FC3F7 seed) — audio signal / success (no green accents)
 *  - tertiary  coral   (#FF8A65 seed)  — warmth / alerts, host audio exceptions
 *  - neutrals          violet-tinted lavender greys
 *
 * The palette stays deliberately bold: containers are saturated so large blocks of colour
 * carry the expressive hierarchy. Dynamic colour (Material You) can replace this scheme at
 * runtime from Settings.
 */

// ---- Light ----------------------------------------------------------------

val MoonToneLightColorScheme = lightColorScheme(
    primary = Color(0xFF5C54A8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC4BDFF),
    onPrimaryContainer = Color(0xFF2E2378),
    inversePrimary = Color(0xFFC9C2FF),
    secondary = Color(0xFF205FA8),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9CC2FF),
    onSecondaryContainer = Color(0xFF002F53),
    tertiary = Color(0xFF9C4528),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFAD94),
    onTertiaryContainer = Color(0xFF6E2309),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1730),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1730),
    surfaceVariant = Color(0xFFEADDFF),
    onSurfaceVariant = Color(0xFF4C4163),
    surfaceTint = Color(0xFF5C54A8),
    inverseSurface = Color(0xFF322B44),
    inverseOnSurface = Color(0xFFF6EDFC),
    error = Color(0xFFAC3149),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFD9E1),
    onErrorContainer = Color(0xFF40000F),
    outline = Color(0xFF7D7295),
    outlineVariant = Color(0xFFCFC4E8),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFEF7FF),
    surfaceDim = Color(0xFFE6D9F8),
    surfaceContainer = Color(0xFFF3EAFB),
    surfaceContainerHigh = Color(0xFFEDE3F7),
    surfaceContainerHighest = Color(0xFFE7DCF4),
    surfaceContainerLow = Color(0xFFF8F1FE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    primaryFixed = Color(0xFFE4DEFF),
    primaryFixedDim = Color(0xFFC9C2FF),
    onPrimaryFixed = Color(0xFF24176D),
    onPrimaryFixedVariant = Color(0xFF42398C),
    secondaryFixed = Color(0xFFCFE3FF),
    secondaryFixedDim = Color(0xFF9CC2FF),
    onSecondaryFixed = Color(0xFF001C33),
    onSecondaryFixedVariant = Color(0xFF1C4A7D),
    tertiaryFixed = Color(0xFFFFDAD0),
    tertiaryFixedDim = Color(0xFFFFAD94),
    onTertiaryFixed = Color(0xFF3B0900),
    onTertiaryFixedVariant = Color(0xFF6E2309),
)

// ---- Dark -----------------------------------------------------------------

val MoonToneDarkColorScheme = darkColorScheme(
    primary = Color(0xFFC9C2FF),
    onPrimary = Color(0xFF30276B),
    primaryContainer = Color(0xFF4A3F91),
    onPrimaryContainer = Color(0xFFE4DEFF),
    inversePrimary = Color(0xFF5C54A8),
    secondary = Color(0xFFA9C9FF),
    onSecondary = Color(0xFF0E2F55),
    secondaryContainer = Color(0xFF1C4A7D),
    onSecondaryContainer = Color(0xFFC9E0FF),
    tertiary = Color(0xFFFFB59E),
    onTertiary = Color(0xFF5A1904),
    tertiaryContainer = Color(0xFF7A2C12),
    onTertiaryContainer = Color(0xFFFFDAD0),
    background = Color(0xFF0F0C16),
    onBackground = Color(0xFFE9E2F5),
    surface = Color(0xFF0F0C16),
    onSurface = Color(0xFFE9E2F5),
    surfaceVariant = Color(0xFF2B2440),
    onSurfaceVariant = Color(0xFFCAC1DC),
    surfaceTint = Color(0xFFC9C2FF),
    inverseSurface = Color(0xFFE9E2F5),
    inverseOnSurface = Color(0xFF332C45),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF948BA9),
    outlineVariant = Color(0xFF4A4360),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF382F4C),
    surfaceDim = Color(0xFF0F0C16),
    surfaceContainer = Color(0xFF1B1526),
    surfaceContainerHigh = Color(0xFF221C2E),
    surfaceContainerHighest = Color(0xFF2A2338),
    surfaceContainerLow = Color(0xFF161120),
    surfaceContainerLowest = Color(0xFF0A0712),
    primaryFixed = Color(0xFFE4DEFF),
    primaryFixedDim = Color(0xFFC9C2FF),
    onPrimaryFixed = Color(0xFF24176D),
    onPrimaryFixedVariant = Color(0xFF42398C),
    secondaryFixed = Color(0xFFCFE3FF),
    secondaryFixedDim = Color(0xFF9CC2FF),
    onSecondaryFixed = Color(0xFF001C33),
    onSecondaryFixedVariant = Color(0xFF1C4A7D),
    tertiaryFixed = Color(0xFFFFDAD0),
    tertiaryFixedDim = Color(0xFFFFAD94),
    onTertiaryFixed = Color(0xFF3B0900),
    onTertiaryFixedVariant = Color(0xFF6E2309),
)

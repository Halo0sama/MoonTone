package com.halo.moontone.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

/**
 * Material 3 Expressive typography.
 *
 * `Typography()` (the no-argument call) resolves to material3's binary-compatibility
 * constructor that mirrors the baseline styles into the emphasized slots — the emphasized
 * variants then render identically to the regular ones. Building the scale through the
 * `FontFamily` constructor instead lets every `*Emphasized` slot fall back to the real
 * 2025 expressive type tokens (heavier weight, tighter tracking), which is what the redesign
 * uses for hero headlines, device names, PIN digits and section labels:
 *
 * ```
 * Text(style = MaterialTheme.typography.headlineSmallEmphasized)
 * Text(style = MaterialTheme.typography.titleSmallEmphasized)
 * ```
 *
 * The app UI is Chinese-first; Roboto falls back to the system CJK font automatically, so we
 * avoid bundling a Latin-only variable font and keep the APK lean.
 */
val MoonToneTypography = Typography(fontFamily = FontFamily.Default)

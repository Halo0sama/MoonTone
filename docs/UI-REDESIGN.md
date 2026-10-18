# MoonTone UI Redesign — Material 3 Expressive

> Status: implemented 2026-10-18, brand light/dark + dynamic colour verified on the
> `moontone37` emulator (Android 17, 1080×2400); independent visual QA pass completed (no blockers).
> Scope: Android app in `echolink-app/` (Jetpack Compose, material3 1.5.0-alpha29).
> Ownership: see **File ownership** at the bottom so parallel work never touches the same file.

## 1. Concept — “Aurora Night / 极光夜曲”

MoonTone is a PC→phone audio streaming app. The redesign shifts it from a settings-like
utility list to a **music-product feel**: one bold status block, oversized emphasized type,
violet/blue colour blocking, very round shapes and springy motion. The app is used at night,
so dark mode is treated as a first-class look and the brand palette is tuned for both themes.

Design pillars (from Material 3 Expressive):

1. **Emphasis before density** — one hero element per screen; everything else recedes.
2. **Vibrant tonal blocks** — saturated containers instead of borders/elevation.
3. **Shape as state** — pills for actions, squircle/cookie badges for icons, 28–36 dp cards.
4. **Spring physics** — expressive motion scheme; press-scale and width/height animations.
5. **Expressive type** — Roboto `*Emphasized` styles for headlines, device names, PIN, numbers.

## 2. Colour — “Aurora Night” palette

Seeds: violet `#6C5CE7` (primary/moonlight), blue `#205FA8` (secondary/audio signal —
no green accents), coral `#FF8A65` (tertiary/warmth). Neutral surfaces are violet-tinted lavender.

Key roles (full table in `ui/theme/Color.kt`):

| role | light | dark |
| --- | --- | --- |
| primary | `#5C54A8` | `#C9C2FF` |
| primaryContainer (hero) | `#C4BDFF` | `#4A3F91` |
| onPrimaryContainer | `#2E2378` | `#E4DEFF` |
| secondaryContainer (signal/blue) | `#9CC2FF` | `#1C4A7D` |
| tertiaryContainer (warm/coral) | `#FFAD94` | `#7A2C12` |
| surface | `#FEF7FF` | `#0F0C16` |
| surfaceContainerLow (cards) | `#F8F1FE` | `#161120` |
| onSurface | `#1D1730` | `#E9E2F5` |
| onSurfaceVariant | `#4C4163` | `#CAC1DC` |
| outlineVariant | `#CFC4E8` | `#4A4360` |

Rules:
- Large blocks use `primaryContainer` / `secondaryContainer` / `tertiaryContainer` with their
  matching `on*Container` content colour. Never put `onSurface` text on a saturated container.
- Cards use `surfaceContainerLow`; sheets/dialogs `surfaceContainerHigh`; inputs `surfaceContainer`.
- Status colour language: blue = connected/online/positive; coral/tertiary = attention
  (host audio exception, mic uplink); error roles only for real errors.
- Dynamic colour (Material You) replaces the whole scheme and is **on by default**;
  it can be switched off in Settings → 外观 to use the brand palette.

## 3. Type

Use `MaterialTheme.typography.*` and its `*Emphasized` variants (heavier weight, tighter
tracking, part of material3 1.5):
- App bar expanded title: `headlineLargeEmphasized` (via `LargeFlexibleTopAppBar`).
- Hero headline (device name / status): `headlineMediumEmphasized` → `headlineSmallEmphasized`.
- PIN digits / big values: `displaySmallEmphasized` with `letterSpacing = 4.sp`.
- Section headers: `titleSmallEmphasized`, colour `primary`.
- Row titles: `bodyLargeEmphasized`; details `bodySmall`.
- Chips/pills: `labelMediumEmphasized`; buttons `labelLarge`.
- CJK is the primary language — avoid negative letter-spacing on Chinese text; keep copy short.

## 4. Shape & motion

`MoonToneShapes` (theme) — extraSmall 10, small 14, medium 20, large 26, largeIncreased 28,
extraLarge 34, extraLargeIncreased 38, extraExtraLarge 44.
`MoonToneShape` named shapes: `hero` 36 dp, `card` 28 dp, `badge` 18 dp, `input` 20 dp,
`pill` (50 %), `sheet` (34 dp top corners).

Motion: `MaterialExpressiveTheme` with `MotionScheme.expressive()`. Use
`MaterialTheme.motionScheme.fastSpatialSpec()` for press/expand, `defaultSpatialSpec()` for
layout changes, `AnimatedContent` + `fadeIn/fadeOut/scaleIn` for page transitions, and the
`Modifier.pressScale(interactionSource)` helper from components for custom surfaces.
Icons from `Icons.Rounded.*` (material-icons-extended).

## 5. Shared components (`ui/components/MoonToneComponents.kt`)

All `internal`, package `com.halo.moontone.ui.components`:

```kotlin
MoonToneSectionHeader(title, modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null)
MoonToneCard(modifier, containerColor = surfaceContainerLow, contentPadding = PaddingValues(20.dp),
             shape = MoonToneShape.card, verticalArrangement = spacedBy(12.dp), content: ColumnScope.() -> Unit)
IconBadge(icon: ImageVector, modifier, contentDescription: String? = null,
          containerColor = secondaryContainer, contentColor = onSecondaryContainer,
          size = 44.dp, roundness = false)      // MaterialShapes.Cookie4Sided by default
StatusPill(text, modifier, containerColor, contentColor, leading: (@Composable () -> Unit)? = null)
AnimatedWaveform(active: Boolean, modifier, color, barCount = 5, barWidth = 5.dp, gap = 5.dp, height = 34.dp)
SettingsGroup(title, modifier, content: ColumnScope.() -> Unit)
SettingsDivider()
SettingsAction(title, detail: String? = null, modifier, icon: ImageVector? = null,
               iconTint = secondaryContainer, iconColor = onSecondaryContainer,
               trailingText: String? = null, enabled = true, onClick)
SettingsToggle(title, detail, checked, modifier, icon: ImageVector? = null,
               iconTint = tertiaryContainer, iconColor = onTertiaryContainer,
               enabled = true, onChange: (Boolean) -> Unit)
SettingsInfoRow(label, value, modifier, icon: ImageVector? = null)
Modifier.pressScale(interactionSource: MutableInteractionSource, pressedScale = 0.96f)
MoonToneTonalSurface(modifier, containerColor, shape = RoundedCornerShape(24.dp),
                     contentPadding = PaddingValues(16.dp), content)
```

Screens may define screen-specific composables locally (device card, metric tile, hero), but
must reuse the components above for section headers, cards, icon badges, settings rows.

## 6. App bar / page chrome

`MoonToneScreen.kt` owns the scaffold and top bar:
- Home: `LargeFlexibleTopAppBar` (title "MoonTone", subtitle = live status, tonal settings
  action) + `TopAppBarDefaults.exitUntilCollapsedScrollBehavior`.
- Home device list: connected/connecting/error sessions and online devices are sorted to the
  top (`N 台在线` count); devices that fail the online probe are collected into a collapsible
  "未在线设备" group (collapsed by default, auto-expanded after the first discovery probe when
  nothing is online so the user is never left with an empty list).
- Sub-pages: same bar component collapsed-style with back icon and page title
  (设置 / 设备声音 / 高级连接 / 运行日志 / 音频信息).
- Page transitions: `AnimatedContent` with expressive fade+scale; `BackHandler` unchanged.
- Content padding: horizontal 20 dp, bottom 24 dp + navigation bar insets; vertical 14–16 dp.

## 7. Theme & settings additions

- `MoonToneTheme(themeMode: ThemeMode = SYSTEM, dynamicColor: Boolean = false)`.
- `ThemeMode` enum: SYSTEM / LIGHT / DARK (`storageValue` = system/light/dark).
- `AppPreferences`: `themeMode(context)`, `setThemeMode(context, storageValue)`,
  `dynamicColor(context)`, `setDynamicColor(context, enabled)`.
- Settings → 外观 group (implemented by ui-settings): theme mode choice
  (跟随系统 / 浅色 / 深色) + 跟随壁纸动态取色 switch (Android 12+ only, on by default).

## 8. File ownership (avoid overlapping writes)

| File | Owner |
| --- | --- |
| `ui/theme/*` (Color, Type, Shape, Theme) | Lead |
| `ui/components/MoonToneComponents.kt` | Lead |
| `ui/MoonToneScreen.kt` | Lead |
| `ui/MainActivity.kt`, `data/AppPreferences.kt`, `app/build.gradle.kts` | Lead |
| `ui/SettingsScreen.kt` | ui-settings |
| `ui/AdvancedScreens.kt`, `res/drawable/`, `res/mipmap-anydpi-v26/`, `res/values/colors.xml` | ui-advanced |
| `docs/UI-REDESIGN.md` | Lead |

## 9. Acceptance / verification

- `./gradlew :app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleDebug` pass.
- Installed on the `moontone37` emulator (Android 17, 1080×2400): all 6 pages render in light
  and dark, no clipping/overlap, status bar icons legible, CJK copy intact.
- Home states: idle (no devices / devices list), connecting/PIN, connected (motion), error.
- Settings: theme switching works live, host-audio bottom sheet, toggles persist.
- Existing behaviour preserved (connect/disconnect, multi-device mixing, PIN pairing, mic,
  cert export, update check, permissions).

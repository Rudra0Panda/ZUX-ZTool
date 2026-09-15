# Material 3 Expressive Frontend Enhancement Plan

## Objective

Enhance the frontend presentation of `FrontendStyle.Material3Expressive` to more closely match the Google Material 3 / Material 3 Expressive design paradigm, without affecting the existing Miuix branch.

Key enhancements include:

- Clear segmented spacing and hierarchy within cards.
- Replacing form-style dropdowns in settings screens with popup menus.
- Adding icon hints to settings items to improve scannability.
- Supporting Material palette mode selection: 2021 Mode vs. 2025 Mode.

## Design Principles

- Keep Material3 Expressive and Miuix branches separate to avoid polluting Miuix visual rules with Material styling.
- Refactor theme state, settings item models, and shared components first to reduce per-screen duplication.
- Option pickers in settings should resemble system settings menus rather than form inputs.
- High-risk business logic remains untouched: no changes to Hooks, Root/Shell, Magisk, or SharedPreferences business keys.

## 1. Add Material Palette Modes

### New Model

In `app/src/main/java/com/qimian233/ztool/ui/theme/ZToolThemeSettings.kt`, add:

```kotlin
enum class MaterialPaletteMode {
    MaterialYou2021,
    Expressive2025
}
```

And extend `ZToolThemeSettings`:

```kotlin
val materialPaletteMode: MaterialPaletteMode = MaterialPaletteMode.Expressive2025
```

### Semantic Definitions

- `MaterialYou2021`: Resembles the soft tonal palette introduced in Android 12 / Material You, lower in saturation and calmer.
- `Expressive2025`: Resembles Material 3 Expressive, elevating primary/tertiary presence and actively utilizing `surfaceContainerHigh`, `surfaceContainerHighest` hierarchy colors.

### Preference Persistence

In `app/src/main/java/com/qimian233/ztool/data/theme/ThemePreferencesRepository.kt`, add:

- `KEY_MATERIAL_PALETTE_MODE`
- `saveMaterialPaletteMode(mode: MaterialPaletteMode)`
- Read enum in `loadSettings()`
- Include key in `THEME_KEYS`

### ViewModel Support

In `app/src/main/java/com/qimian233/ztool/viewmodel/SettingsViewModel.kt`, add:

```kotlin
fun setMaterialPaletteMode(mode: MaterialPaletteMode)
```

And update `SettingsUiState.themeSettings`.

## 2. Adjust Theme Resolution Logic

In `app/src/main/java/com/qimian233/ztool/ui/theme/ZToolTheme.kt`, let `resolveZToolColorScheme()` select strategies based on `materialPaletteMode`.

Recommended strategies:

- When dynamic colors are enabled: Still prioritize system dynamic colors, but map tertiary and surface container levels more actively in 2025 mode.
- When manual colors are enabled: Add `paletteMode` parameter to `manualColorScheme()`.
- When default colors are enabled (dynamic off): Add `paletteMode` parameter to `defaultColorScheme()`.

Proposed split:

```kotlin
private fun defaultMaterial2021ColorScheme(darkTheme: Boolean): ColorScheme
private fun defaultExpressive2025ColorScheme(darkTheme: Boolean): ColorScheme
private fun manualColorScheme(
    seedColor: Color,
    darkTheme: Boolean,
    paletteMode: MaterialPaletteMode
): ColorScheme
```

2021 mode should feel softer, while 2025 mode retains and enhances current `Md3eLightColors` / `Md3eDarkColors` expressions.

## 3. Segmented Card Spacing

Currently `ZToolSettingsSection` wraps all settings items in a single large `ZToolCard`, separating items primarily with `ZToolSettingsDivider()`.

Under Material3 Expressive, transition towards grouped-list visuals:

- Each item gets an independent surface level, e.g. `surfaceContainerHigh`.
- 6-8dp spacing between items, or clearer inset separators.
- First and last items use distinct shapes: rounded top, rounded bottom, with rectangular middle items.
- Section title separated from content groups with lighter visual weight.
- Miuix branch preserves current design using Miuix `BasicComponent` and dividers.

Primary files involved:

- `app/src/main/java/com/qimian233/ztool/ui/components/ZToolSettingsModel.kt`
- `app/src/main/java/com/qimian233/ztool/ui/components/ZToolSettings.kt`
- `app/src/main/java/com/qimian233/ztool/ui/components/ZToolSurfaces.kt`

Recommended new internal component:

```kotlin
@Composable
private fun MaterialExpressiveSettingsItemSurface(
    index: Int,
    count: Int,
    content: @Composable () -> Unit
)
```

Handles item spacing, shapes, container colors, and pressed states consistently.

## 4. Replace Dropdowns with Popup Menus

Currently `ZToolDropdownField` uses `ExposedDropdownMenuBox + OutlinedTextField`. For fixed options in settings screens, this feels too much like form input rather than system settings.

Recommend adding `ZToolPopupMenuField`:

```kotlin
@Composable
fun <T> ZToolPopupMenuField(
    value: String,
    options: List<T>,
    optionLabel: (T) -> String,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
)
```

Under Material3 Expressive:

- Triggers use `FilledTonalButton`, `OutlinedButton`, or compact trailing fields.
- Menus use `DropdownMenu` + `DropdownMenuItem`.
- Selected item displays a check icon.
- Can be extended to support option leading icons and supporting labels.

Miuix Branch:

- Retain existing `MiuixTextField + ExposedDropdownMenu`.
- Or implement Miuix-style popup menus separately in the future.

Prioritize replacing in settings screens:

- Frontend style
- Theme mode
- Material palette mode

## 5. Add Icon Hints to Settings Items

Currently `SettingItem.Entry` and `SettingItem.Action` support `leadingContent`, but `Switch`, `Dropdown`, `Slider`, and `TextInput` lack uniform icon properties.

Recommend adding to `SettingItem` data classes:

```kotlin
val icon: ImageVector? = null
```

Rendered consistently by shared components.

Prioritize icons for the global settings screen:

- Frontend style: `Palette` or `DashboardCustomize`
- Theme mode: `DarkMode`
- Dynamic color: `AutoAwesome`
- Manual theme color: `FormatColorFill`
- Material palette mode: `Tune`
- AMOLED pitch black: `Contrast`
- Logging service: `Article` or `Terminal`
- Backup: `Backup`
- Restore: `RestorePage`
- About: `Info`

Use existing Material Icons dependencies without adding new icon libraries.

## 6. Settings Screen Adjustments

In `ThemeSettingsSection` in `app/src/main/java/com/qimian233/ztool/SettingsRoute.kt`, add palette mode selection:

```kotlin
DropdownSettingRow(
    title = stringResource(R.string.material_palette_mode_title),
    value = paletteModeOptions.first { it.value == settings.materialPaletteMode }.label,
    options = paletteModeOptions,
    optionLabel = { it.label },
    onOptionSelected = { onMaterialPaletteModeChanged(it.value) }
)
```

In practice, use the new popup menu component rather than legacy `DropdownSettingRow`.

Required string resources:

- `material_palette_mode_title`
- `material_palette_mode_2021`
- `material_palette_mode_2025`
- `material_palette_mode_summary` (if summary needed)

## 7. Recommended Implementation Order

1. Add `MaterialPaletteMode`, theme settings fields, and preference persistence.
2. Add ViewModel setter and integrate palette mode selection in settings.
3. Adjust `ZToolTheme.resolveZToolColorScheme()` to apply 2021/2025 modes to default colors and manual seed generation.
4. Add popup menu component and replace theme-related dropdowns in settings.
5. Enhance segmented visuals for `ZToolSettingsSection` in Material3 Expressive.
6. Extend settings item icon model and add icons to global settings.
7. Run `./gradlew assembleDebug` to verify.

## Verification Checklist

- Run `./gradlew assembleDebug`.
- Inspect settings screen under both Material3 Expressive and Miuix frontend styles.
- Verify combinations of Light, Dark, AMOLED, Dynamic, Manual color, and 2021/2025 palette modes.
- Confirm legacy preference migrations do not crash; default to `Expressive2025` when unset.
- Verify popup menus, icons, titles, and summaries do not overlap on narrow displays.

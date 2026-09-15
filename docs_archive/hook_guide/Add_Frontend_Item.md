# Guide to Adding Frontend Settings for Hooks

> This guide focuses on adding configuration items within the **app-side Compose settings screens**. For backend Hook implementation, see **[Add_New_Hook_Module.md](./Add_New_Hook_Module.md)**; for centralized preference keys, see **[Add_New_Preference_Key_zh-CN.md](../preference_key/Add_New_Preference_Key_zh-CN.md)**.
>
> Preference key management is centralized: all preference keys must be registered in `PreferenceKeys.kt` first and referenced via `PreferenceKeys.CONSTANT_NAME.name`. Target package names are managed centrally by `ScopeKeys` and referenced via `ScopeKeys.CONSTANT.packageName`. Manually written strings are deprecated.

This document describes recommended practices for adding frontend configuration items for new Hooks in ZUX-ZTool, covering SharedPreferences, toggle switches, and other custom UI controls.

## Core Principles

1. Never read or write SharedPreferences directly inside Composable functions. Data persistence belongs in `data/**/**Repository.kt`. UI screens should only consume `UiState` and invoke ViewModel functions.
2. Hook-related configurations must use `ModulePreferencesUtils` targeting `xposed_module_config`. Do not create separate SharedPreferences files for Hook toggles.
3. Added configuration keys must strictly match the keys read by the Hook side, including casing. Never rename existing keys.
4. Default values must align between the frontend and the Hook side. If the frontend defaults to false but the Hook defaults to true, inconsistencies will occur before the user opens the settings page.
5. After modifying feature UI, verify with `./gradlew assembleDebug`.

## Working with Shared Preferences

App-side Hook configurations use:

```kotlin
private val prefsUtils = ModulePreferencesUtils(context)
```

`ModulePreferencesUtils` targets `xposed_module_config` under the module package `com.qimian233.ztool` by default and provides common helper methods:

```kotlin
prefsUtils.loadBooleanSetting(KEY, false)
prefsUtils.saveBooleanSetting(KEY, enabled)

prefsUtils.loadStringSetting(KEY, "")
prefsUtils.saveStringSetting(KEY, value)

prefsUtils.loadIntegerSetting(KEY, defaultValue)
prefsUtils.saveIntegerSetting(KEY, value)

prefsUtils.loadFloatSetting(KEY, defaultValue)
prefsUtils.saveFloatSetting(KEY, value)
```

**Key names must be referenced via `PreferenceKeys` constants** instead of hardcoded strings. Register new keys in `PreferenceKeys.kt` first, then reference them in the Repository's `companion object`:

```kotlin
class ExampleSettingsRepository(
    context: Context
) {
    private val prefsUtils = ModulePreferencesUtils(context)

    fun loadState(): ExampleSettingsUiState {
        return ExampleSettingsUiState(
            newHookEnabled = prefsUtils.loadBooleanSetting(KEY_NEW_HOOK_ENABLED, false),
            customLevel = prefsUtils.loadIntegerSetting(KEY_CUSTOM_LEVEL, DEFAULT_LEVEL)
        )
    }

    fun saveNewHookEnabled(enabled: Boolean) {
        prefsUtils.saveBooleanSetting(KEY_NEW_HOOK_ENABLED, enabled)
    }

    fun saveCustomLevel(level: Int) {
        prefsUtils.saveIntegerSetting(KEY_CUSTOM_LEVEL, level.coerceIn(LEVEL_MIN, LEVEL_MAX))
    }

    companion object {
        const val LEVEL_MIN = 0
        const val LEVEL_MAX = 10
        private const val DEFAULT_LEVEL = 5

        // Reference PreferenceKeys constants rather than hardcoded strings
        private val KEY_NEW_HOOK_ENABLED = PreferenceKeys.NEW_HOOK_ENABLED.name
        private val KEY_CUSTOM_LEVEL = PreferenceKeys.NEW_HOOK_LEVEL.name
    }
}
```

> **Note:** Because `PreferenceKeys.CONSTANT.name` is not a compile-time constant, companion object declarations must use `val` instead of `const val`.

The Hook side (Kotlin) reads using the same constant:

```kotlin
val prefs = remotePreferences
val enabled = prefs.getBoolean(PreferenceKeys.NEW_HOOK_ENABLED.name, PreferenceKeys.NEW_HOOK_ENABLED.default)
val level = prefs.getInt(PreferenceKeys.NEW_HOOK_LEVEL.name, PreferenceKeys.NEW_HOOK_LEVEL.default)
```

## Adding a Switch Setting

Adding a standard Hook switch typically involves four places: Repository, UiState, ViewModel, and the screen section.

### 1. Repository

Add load and save methods in the corresponding scope Repository. For example, security center features go to `data/safecenter/SafeCenterSettingsRepository.kt`, launcher features to `data/launcher/LauncherSettingsRepository.kt`.

```kotlin
fun loadState(): ExampleSettingsUiState {
    return ExampleSettingsUiState(
        newHookEnabled = prefsUtils.loadBooleanSetting(KEY_NEW_HOOK_ENABLED, false)
    )
}

fun saveNewHookEnabled(enabled: Boolean) {
    prefsUtils.saveBooleanSetting(KEY_NEW_HOOK_ENABLED, enabled)
}
```

### 2. UiState

Add state properties in the UiState data class inside the ViewModel file:

```kotlin
data class ExampleSettingsUiState(
    val newHookEnabled: Boolean = false
)
```

### 3. ViewModel

The ViewModel updates state in memory first, then writes to the Repository:

```kotlin
fun setNewHookEnabled(enabled: Boolean) {
    _uiState.value = _uiState.value.copy(newHookEnabled = enabled)
    repository.saveNewHookEnabled(enabled)
}
```

### 4. Compose Screen

Use `SettingItem.Switch` on the UI screen. Never call `prefsUtils` directly.

```kotlin
SettingItem.Switch(
    title = stringResource(R.string.new_hook_title),
    summary = stringResource(R.string.new_hook_summary),
    checked = state.newHookEnabled,
    onCheckedChange = onNewHookEnabledChanged,
    key = "new_hook"
)
```

`key` is a **mandatory parameter** in `SettingItem`, used for search result highlighting and index reconciliation:

- Visible items must also be registered as a `SearchEntry` in `search/SearchIndex.kt`, where `id` matches this `key` string **identically** (usually reusing the `PreferenceKeys` constant name, such as `new_hook`).
- Purely decorative/header rows (exempt from search) use the `deco_` prefix; dynamically generated detail rows at runtime use the `dyn_` prefix. Both are exempted in debug index audits (`SearchIndexAudit`).
- Debug builds check for index drift between registered entries and rendered items, logging warnings via tag `SearchIndexAudit`.

Pass event handlers down through the Composable parameters:

```kotlin
onNewHookEnabledChanged = viewModel::setNewHookEnabled
```

## Adding Other Custom Controls

The project provides the `SettingItem` model. Use shared components to avoid repeating styling across feature screens.

### Dropdown Selection

Ideal for mode selection, policies, or style selection. Use `SettingItem.Dropdown` or existing `ZToolPopupMenuSettingRow`.

```kotlin
enum class NewHookMode {
    Default,
    Aggressive,
    Compatibility
}
```

When saving as a string in the Repository, convert explicitly to safeguard against future enum renames:

```kotlin
fun saveMode(mode: NewHookMode) {
    prefsUtils.saveStringSetting(KEY_MODE, mode.name)
}

private fun loadMode(): NewHookMode {
    val raw = prefsUtils.loadStringSetting(KEY_MODE, NewHookMode.Default.name)
    return NewHookMode.entries.firstOrNull { it.name == raw } ?: NewHookMode.Default
}
```

UI:

```kotlin
SettingItem.Dropdown(
    label = stringResource(R.string.new_hook_mode_title),
    value = modeLabel(state.mode),
    options = NewHookMode.entries,
    optionLabel = { modeLabel(it) },
    onOptionSelected = onModeChanged
)
```

### Slider

Suitable for numeric configuration within a bounded range (e.g. grid counts, dimensions, thresholds). Enforce boundaries in Repository and ViewModel.

```kotlin
SettingItem.Slider(
    title = stringResource(R.string.new_hook_level_title),
    summary = stringResource(R.string.new_hook_level_summary),
    value = state.customLevel.toFloat(),
    valueText = state.customLevel.toString(),
    valueRange = 0f..10f,
    steps = 9,
    onValueChange = { onCustomLevelChanged(it.toInt()) }
)
```

Repository:

```kotlin
fun saveCustomLevel(level: Int) {
    prefsUtils.saveIntegerSetting(KEY_CUSTOM_LEVEL, level.coerceIn(LEVEL_MIN, LEVEL_MAX))
}
```

### Text Input

Suitable for pattern strings, package names, API endpoints, or whitelists. Use `SettingItem.TextInput`, trimming and validating input before saving.

```kotlin
SettingItem.TextInput(
    title = stringResource(R.string.new_hook_pattern_title),
    summary = stringResource(R.string.new_hook_pattern_summary),
    label = stringResource(R.string.new_hook_pattern_label),
    value = state.pattern,
    onValueChange = onPatternChanged,
    singleLine = true
)
```

Repository:

```kotlin
fun savePattern(pattern: String) {
    prefsUtils.saveStringSetting(KEY_PATTERN, pattern.trim())
}
```

### Conditionally Displayed Child Items

When a parent switch is off, associated sub-settings should typically be hidden or disabled. Common pattern with `buildList`:

```kotlin
val items = buildList {
    add(
        SettingItem.Switch(
            title = stringResource(R.string.new_hook_title),
            checked = state.newHookEnabled,
            onCheckedChange = onNewHookEnabledChanged
        )
    )

    if (state.newHookEnabled) {
        add(
            SettingItem.Slider(
                title = stringResource(R.string.new_hook_level_title),
                value = state.customLevel.toFloat(),
                onValueChange = { onCustomLevelChanged(it.toInt()) },
                valueRange = 0f..10f,
                steps = 9
            )
        )
    }
}
```

### Fully Custom Rows

Use `SettingItem.Custom` when standard models cannot accommodate complex interactions (e.g. app pickers, composite sliders). Always use `MaterialTheme` and shared components.

```kotlin
SettingItem.Custom(
    content = {
        CustomHookConfigRow(
            value = state.value,
            onClick = onOpenPicker
        )
    }
)
```

## Strings and Localization

Add all UI strings to `app/src/main/res/values/strings.xml` (and `values-en-rUS/` / `values-zh-rCN/`), referencing them via `stringResource(R.string.xxx)`. Do not hardcode strings in Composables.

Recommended naming:

```xml
<string name="new_hook_title">New Hook Feature</string>
<string name="new_hook_summary">Explains the system behavior affected and requirements.</string>
```

Clear documentation should state:
1. Which app or system area is affected.
2. Whether restarting the target app, SystemUI, or device is required.
3. Whether Root, LSPosed scope, or specific OS versions are prerequisites.

## Restart and Confirmation Prompts

Most Hook configurations do not immediately apply to already running processes. Screens provide a restart FAB in the bottom right, triggering `am force-stop` or similar commands via the Repository.

If a new Hook belongs to an existing scope screen, reuse that screen's existing restart FAB and confirmation dialog. Do not prompt toasts or restart the app automatically on every switch toggle.

If a new Hook requires a custom restart target:
1. Encapsulate shell or root operations in the Repository.
2. Expose `showRestartConfirmDialog`, `dismissRestartConfirmDialog`, and action functions in the ViewModel.
3. The Composable is only responsible for rendering the confirmation dialog and showing toast feedback.

## New Screen vs. Existing Screen

Prefer adding new Hooks to existing detail screens by scope:

| Hook Scope | Recommended Screen / Repository |
|---|---|
| `com.android.systemui` | `SystemUiSettingsRoute`, Status Bar, Control Center, or Lockscreen screens |
| `com.zui.launcher` | `LauncherSettingsRoute` / `LauncherSettingsRepository` |
| `com.lenovo.safecenter` / DocumentsUI | `SafeCenterSettingsRoute` / `SafeCenterSettingsRepository` |
| `com.android.settings` | `SettingsDetailRoute` / `SettingsDetailRepository` |
| `android` System Framework | `FrameworkSettingsRoute` / `FrameworkSettingsRepository` |
| Package Installer | `PackageInstallerSettingsRoute` / `PackageInstallerSettingsRepository` |
| Game Assistant | `GameToolSettingsRoute` / `GameToolSettingsRepository` |
| OTA | `OtaSettingsRoute` / `OtaSettingsRepository` |

Only create a new Route when the feature involves an independent multi-step flow that cannot fit existing pages.

## Scope Management

Target package names are **managed centrally by `ScopeKeys`** (`app/src/main/java/com/qimian233/ztool/data/keys/ScopeKeys.kt`). Avoid hardcoding package strings.

- `ScopeUtils.getScopes()` defines scopes per feature entry (package name + recommended restart method) using `ScopeKeys`.
- Each `Scope` registers `HowToRestart` (`AmStop` / `KillAll` / `Reboot`), used by `ScopeUtils.restartScope()`.
- Backend Hooks must reference `ScopeKeys.CONSTANT.packageName` in `getTargetPackages()`.

When adding a new target package:
1. Register in `ScopeKeys.kt` with its restart strategy.
2. Reference `ScopeKeys.CONSTANT.packageName` in backend Hook `getTargetPackages()`.
3. Add the package to build-time resource `scope.list` (LSPosed injection declaration).

> Build-time files like `module.prop` and `scope.list` remain hardcoded. `scope.list` is a build-time declaration independent of `ScopeKeys`; both must be maintained.

## Common Mistakes

1. Calling `context.getSharedPreferences(...)` directly in Composables: move to Repository.
2. Using `apply()` on the frontend while the Hook reads immediately, resulting in race conditions: `ModulePreferencesUtils` uses `commit()`.
3. Casing mismatches: `CustomGridSize` and `custom_grid_size` are different keys.
4. Storing Hook configuration in theme or UI preferences: Hook configs must go through `ModulePreferencesUtils`.
5. Updating UiState without saving to Repository: settings revert upon exiting screen.
6. Saving to Repository without updating UiState: UI switch doesn't respond smoothly.
7. Running Root/Shell commands directly in Composables: encapsulate in Repository.

## Minimal Integration Checklist

1. Define configuration key, type, and default value, matching the Hook side.
2. Add `loadState()` and save methods in the corresponding Repository.
3. Add fields to `UiState`.
4. Add `setXxx(...)` methods to ViewModel.
5. Add `SettingItem.Switch`, `Dropdown`, `Slider`, `TextInput`, or `Custom` to the screen's `SettingSection`.
6. Register preference keys in `PreferenceKeys.kt` and reference via `PreferenceKeys.CONSTANT_NAME.name`.
7. If the target package is new: register in `ScopeKeys.kt`, reference in `getTargetPackages()`, and add to `scope.list`.
8. Add title and summary strings to `strings.xml`.
9. Reuse or add restart confirmation flows if needed.
10. Verify via `./gradlew assembleDebug`.

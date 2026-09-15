# Preference Key Management System

This document outlines ZUX-ZTool's centralized architecture for managing Preference Keys and details how to add new preference keys across different application scenarios.

## Architectural Overview

All Hooks and application settings share the single SharedPreferences file `xposed_module_config`. The **name**, **data type**, and **default value** of preference keys are unified in a single source of truth:

```
app/src/main/java/com/qimian233/ztool/data/keys/PreferenceKeys.kt    ← Authoritative definition of all keys
app/src/main/java/com/qimian233/ztool/utils/ModulePreferencesUtils.kt  ← App-side read/write utilities
```

### Typed Key Models

Four typed key classes are defined in `PreferenceKeys.kt`:

| Type    | Kotlin Class               | Example                                            |
|---------|----------------------------|----------------------------------------------------|
| Boolean | `BoolKey(name, default)`   | `BoolKey("disable_force_stop", false)`             |
| Int     | `IntKey(name, default)`    | `IntKey("CustomLauncherRow", 4)`                   |
| Float   | `FloatKey(name, default)`  | `FloatKey("Custom_StatusBarClockTextSize", 16.0f)` |
| String  | `StringKey(name, default)` | `StringKey("ForceStopWhiteList", "")`              |

Each key is exposed as a `val` constant within the `PreferenceKeys` object, accessed in Kotlin via `PreferenceKeys.CONSTANT_NAME.name`.

### Automatic Type Inference

When performing backups or restores, `ModulePreferencesUtils.writeConfigToSharedPrefs()` iterates through typed lists in `PreferenceKeys` (`booleanKeys`, `intKeys`, `floatKeys`) to infer data types automatically. **As long as a new key is registered in the corresponding list in `PreferenceKeys.kt`, backup and restore handle it correctly without extra code.**

---

## Workflow for Adding a New Preference Key

### Step 1: Register in PreferenceKeys.kt

Open `app/src/main/java/com/qimian233/ztool/data/keys/PreferenceKeys.kt`, locate the corresponding section by data type, define the constant, and append it to its typed list.

#### Boolean Keys (Most Common: Hook Toggles, Sub-feature Switches)

```kotlin
// Add in the Boolean section under the appropriate scope grouping:
val NEW_FEATURE_ENABLED = BoolKey("new_feature_enabled", false)

// Then append NEW_FEATURE_ENABLED to the booleanKeys list
```

**Boolean keys also act as Hook module enable/disable switches**: If the key name matches the string returned by a Hook module's `getModuleName()`, toggling this switch in the frontend enables that Hook automatically without boilerplate code. If the key is a sub-feature toggle (not matching module name), read it manually within the Hook implementation.

#### Int Keys

```kotlin
val NEW_FEATURE_LEVEL = IntKey("new_feature_level", 5)

// Append to intKeys list
```

#### Float Keys

```kotlin
val NEW_FEATURE_SCALE = FloatKey("new_feature_scale", 1.0f)

// Append to floatKeys list
```

#### String Keys

```kotlin
val NEW_FEATURE_PATTERN = StringKey("new_feature_pattern", "")

// Append to stringKeys list
```

**Critical Rules:**
- The default value must match what is used across both Hook and Repository implementations.
- You must register the new key in the appropriate list (`booleanKeys`, `intKeys`, `floatKeys`, `stringKeys`), otherwise backup/restore cannot recognize it.

---

### Step 2: Use in Repository

Repositories reference `PreferenceKeys` constants in their `companion object` rather than hardcoding string literals:

```kotlin
import com.qimian233.ztool.data.keys.PreferenceKeys

class ExampleSettingsRepository(
    private val context: Context
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

        // Use PreferenceKeys constants instead of string literals
        private val KEY_NEW_HOOK_ENABLED = PreferenceKeys.NEW_FEATURE_ENABLED.name
        private val KEY_CUSTOM_LEVEL = PreferenceKeys.NEW_FEATURE_LEVEL.name
    }
}
```

**Note:** Because `PreferenceKeys.CONSTANT.name` is not a compile-time constant, companion object declarations must use `val` instead of `const val`.

---

### Step 3: Use in Kotlin Hooks

In Kotlin Hooks, read preference keys via `PreferenceKeys` constants:

```kotlin
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface

class NewFeatureHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.NEW_FEATURE_ENABLED.name
    override fun getTargetPackages(): Array<String> = arrayOf("com.android.systemui")

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        // Read main switch (isEnabled() already verifies getModuleName(),
        // but hooks may need to read additional sub-feature switches)
        val prefs = xposed.getRemotePreferences("xposed_module_config")

        // Boolean sub-feature
        val subFeatureEnabled = prefs.getBoolean(
            PreferenceKeys.SUB_FEATURE_ENABLED.name,
            PreferenceKeys.SUB_FEATURE_ENABLED.default
        )

        // Int setting
        val level = prefs.getInt(
            PreferenceKeys.NEW_FEATURE_LEVEL.name,
            PreferenceKeys.NEW_FEATURE_LEVEL.default
        )

        // Float setting
        val scale = prefs.getFloat(
            PreferenceKeys.NEW_FEATURE_SCALE.name,
            PreferenceKeys.NEW_FEATURE_SCALE.default
        )

        // String setting
        val pattern = prefs.getString(
            PreferenceKeys.NEW_FEATURE_PATTERN.name,
            PreferenceKeys.NEW_FEATURE_PATTERN.default
        ) ?: ""

        // ... Hook logic ...
    }
}
```

**Key Points:**
- The string returned by `getModuleName()` doubles as the Boolean toggle key in `xposed_module_config`, checked automatically by `BaseHookModule.isEnabled()`.
- If the Hook has no sub-feature toggles (controlled entirely by the module name), no extra preference reading is needed.
- Sub-feature keys obtain their key string via `PreferenceKeys.CONSTANT_NAME.name` and their default value via `PreferenceKeys.CONSTANT_NAME.default`.

---

## Critical Rules

1. **All keys in `xposed_module_config` must be registered in `PreferenceKeys.kt` first** before being referenced in Repositories or Hooks.
2. **Key names must never be renamed**. Existing key names must remain preserved to avoid breaking user configurations.
3. **Default values must remain consistent**: Defaults defined in `PreferenceKeys` must match the defaults used in Repositories and Hooks.
4. **Types must match**: Boolean keys belong in `booleanKeys`, Int keys in `intKeys`, and so on. Mismatches corrupt backup/restore operations.
5. **Never hardcode key strings**. Always use `PreferenceKeys.CONSTANT_NAME.name` to guarantee consistent spelling and casing.
6. **Constant naming convention**: `val` constants in `PreferenceKeys` use `SCREAMING_SNAKE_CASE`, distinguishing them from the `BoolKey` `name` parameter (which may be historical `snake_case` or `PascalCase`).

---

## File Manifest

| File | Purpose |
|---|---|
| `data/keys/PreferenceKeys.kt` | Authoritative single source of truth for all keys, grouped by typed lists |
| `utils/ModulePreferencesUtils.kt` | App-side SharedPreferences read/write helper |
| `data/**/*Repository.kt` | Repositories referencing keys via `PreferenceKeys.CONST.name` |
| `hook/modules/**/` | Hook implementations referencing keys via `PreferenceKeys.CONST.name` |

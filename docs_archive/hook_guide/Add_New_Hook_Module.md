# Backend Hook Module Implementation Guide

> This guide focuses on **backend Hook implementation** (Xposed module side). For frontend settings integration, see **[Add_Frontend_Item.md](./Add_Frontend_Item.md)**; for centralized preference keys, see **[Add_New_Preference_Key_zh-CN.md](../preference_key/Add_New_Preference_Key_zh-CN.md)**.
>
> All preference keys must be registered in `PreferenceKeys.kt` first and referenced via `PreferenceKeys.CONSTANT_NAME.name`. All scope package names are centrally managed by `ScopeKeys` and referenced via `ScopeKeys.CONSTANT.packageName`. Never hardcode key names or package strings.

## 1. Creating a Kotlin Class

New Hooks must be written in **Kotlin** (`.kt`); **no new Java code is permitted**. Java is reserved for urgent fixes to legacy infrastructure (e.g. `BaseHookModule.java`, `HookManager.java`).

- Location: `app/src/main/java/com/qimian233/ztool/hook/modules/<scope>/`, organized by target application sub-packages (e.g. `systemui/`, `launcher/`, `setting/`).
- File encoding: Explicit UTF-8.
- Test Hooks: When `getModuleName()` returns `"hook_test"` or `"test_hook"`, it is permanently enabled without requiring frontend switches.

## 2. Inheriting AppHookModule or SystemHookModule

Both base classes are Java abstract classes; Kotlin classes inherit directly:

| Base Class | Target Scenario | Required Implementations |
|---|---|---|
| `AppHookModule` | Standard app package Hooks (SystemUI, Launcher, Settings, etc.) | `handleLoadPackage(...)` |
| `SystemHookModule` | System framework Hooks (`android` / `system`) | `handleSystemServerStarting(...)` (`handleLoadPackage` optional) |

```kotlin
class ExampleHook : AppHookModule() {
    // ...
}
```

## 3. Implementing getModuleName() and getTargetPackages()

### getModuleName() — Using PreferenceKeys

Return a `PreferenceKeys` constant name. This key serves as the Boolean toggle in `xposed_module_config`, checked automatically by `BaseHookModule.isEnabled()`:

```kotlin
override fun getModuleName(): String = PreferenceKeys.EXAMPLE_HOOK_ENABLED.name
```

- Defaults are defined upon registration in `PreferenceKeys.kt`. Both frontend switches and Hook lookups share this single key.
- Sub-feature toggles or numeric configs are retrieved inside `handleLoadPackage` via `remotePreferences` (see Section 4).

### getTargetPackages() — Using ScopeKeys

Return an array of package names referencing `ScopeKeys` constants:

```kotlin
override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)
```

If the target package is not yet registered:

1. Register the new `Scope` (with its recommended restart strategy `HowToRestart`) in `app/src/main/java/com/qimian233/ztool/data/keys/ScopeKeys.kt`.
2. Reference `ScopeKeys.CONSTANT.packageName` in `getTargetPackages()`.
3. Add the package to build-time resource `app/src/main/resources/META-INF/xposed/scope.list` (LSPosed injection declaration; independent of `ScopeKeys`, both must be maintained).

## 4. Implementing handleLoadPackage(...)

Base class `BaseHookModule` provides helper methods for logging, hook installation, reflection, and preference reading.

### logger — Unified Logging

`protected ModuleLog logger` provides Log4j-style logging: `trace` / `debug` / `info` / `warn` / `error` / `fatal`.

```kotlin
logger.info("Starting ExampleHook installation")
logger.debug("Modifying field: $fieldName")
logger.warn("Method not found, falling back to default")
logger.error("Hook installation failed, breaking chain", throwable)
```

For logging level guidelines, see **[docs_archive/new_log_system/migrate_and_use_new_logging_system.md](../new_log_system/migrate_and_use_new_logging_system.md)**.

### hookWithId — Installing Hooks

A protected instance method:

```kotlin
protected HookHandle hookWithId(Executable target, String id, Hooker hooker)
```

Equivalent to `xposed.hook(target).setId(id).intercept(hooker)`. The `id` must be stable and unique within the module. During hot reload, new Hooks with identical IDs atomically replace old Hooks on the same executable.

```kotlin
hookWithId(targetMethod, "example_hook_method") { chain ->
    logger.debug("Target method intercepted")
    chain.proceed()
}
```

### findMethod / findField — Reflection Utilities

Public static helpers that search recursively up the class inheritance hierarchy, automatically applying `setAccessible(true)`:

```kotlin
fun findMethod(startClass: Class<*>, name: String, vararg parameterTypes: Class<*>): Method
fun findField(startClass: Class<*>, name: String): Field
```

- `findMethod` supports parameter type signatures to handle method overloads across Android versions.
- Throws `NoSuchMethodException` / `NoSuchFieldException` if not found; callers must handle exceptions.

### remotePreferences — Reading Remote Config (Read-Only)

Configurations from `xposed_module_config` are read-only:

```kotlin
// Recommended: BaseHookModule synthetic property
val prefs = remotePreferences

// Direct: XposedInterface instance method
val prefs = xposed.getRemotePreferences("xposed_module_config")
```

Read values by type using `PreferenceKeys` constants:

```kotlin
val enabled = prefs.getBoolean(PreferenceKeys.EXAMPLE_HOOK_ENABLED.name, false)
val level = prefs.getInt(PreferenceKeys.EXAMPLE_HOOK_LEVEL.name, 0)
```

## 5. Registering with HookManager

Register inside `registerAllModules()` in `app/src/main/java/com/qimian233/ztool/hook/base/HookManager.java`:

```java
registerHookModule(new ExampleHook());
```

- If class names collide across packages, use fully qualified names.
- Registration allows `HookInit` to dispatch the hook during load events.

## 6. Scope Declaration (Build-Time)

- `getTargetPackages()` must be a subset covered by `scope.list`; remember to add new packages to `scope.list`.
- `module.prop` and `scope.list` remain hardcoded.
- Users must also check the module scope within the LSPosed Manager.

## 7. Post-Implementation Checklist

1. Hook class registered in `HookManager.registerAllModules()`.
2. `getTargetPackages()` references `ScopeKeys`, and package is listed in build-time `scope.list`.
3. Frontend SharedPreferences key exactly matches Hook key (same `PreferenceKeys` constant).
4. Frontend default value matches `PreferenceKeys` registered default.
5. Serialization formats (strings, numbers, lists) match between frontend and Hook parser.
6. Target application reflects configuration after restart (per `HowToRestart` in `ScopeKeys`).

## Complete Example

```kotlin
package com.qimian233.ztool.hook.modules.example

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class ExampleHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.EXAMPLE_HOOK_ENABLED.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        // Read sub-feature configuration (read-only, keys/defaults from PreferenceKeys)
        val level = remotePreferences.getInt(
            PreferenceKeys.EXAMPLE_HOOK_LEVEL.name,
            PreferenceKeys.EXAMPLE_HOOK_LEVEL.default
        )

        logger.info("Installing ExampleHook, level=$level")
        try {
            val targetClass = classLoader.loadClass("com.example.TargetClass")

            // Find method by signature (recursive search with setAccessible)
            val targetMethod = findMethod(
                targetClass, "targetMethod", String::class.java, Int::class.javaPrimitiveType
            )
            hookWithId(targetMethod, "example_target") { chain ->
                logger.debug("Intercepted targetMethod")
                val thisObject = chain.thisObject
                val targetField = findField(targetClass, "targetField")
                targetField.setInt(thisObject, level)
                chain.proceed()
            }
            logger.info("ExampleHook installation complete")
        } catch (t: Throwable) {
            logger.error("ExampleHook installation failed", t)
        }
    }
}
```

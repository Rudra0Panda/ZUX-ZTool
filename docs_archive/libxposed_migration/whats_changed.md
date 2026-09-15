# libxposed API 102 Upgrade & Migration Notes

This document provides a concise overview of the upgrade for developers familiar with legacy Xposed APIs.

If you only develop specific Hooks, focus on Sections 4 and 6, and consult the existing Hook implementations in the project.

For general libxposed documentation, refer to: https://libxposed.github.io/api/ or the official GitHub repository.

---

## 1. Dependencies and Build Changes

### 1. Gradle Dependencies (`gradle/libs.versions.toml` + `app/build.gradle.kts`)

| Legacy | New |
|---|---|
| `de.robv.android.xposed:api:82` (compileOnly) | `io.github.libxposed:api:102.0.0` (compileOnly) |
| (None) | `io.github.libxposed:service:102.0.0` (implementation, new) |

- Upgraded from API version **82** to **102**.
- Added `libxposed-service` runtime dependency for app-side communication with the framework via `XposedService`.

### 2. Removal of Legacy API JAR

- `app/libs/XposedBridgeAPI-82.jar` was removed (0-byte stub).

---

## 2. Module Declaration Overhaul

### Legacy: `<meta-data>` in `AndroidManifest.xml`

Removed all legacy Xposed meta-data tags:
```xml
<meta-data android:name="xposedmodule" android:value="true" />
<meta-data android:name="xposeddescription" ... />
<meta-data android:name="xposedminversion" android:value="93" />
<meta-data android:name="xposedsharedprefs" android:value="true" />
<meta-data android:name="xposedscope" android:resource="@array/xposed_scope" />
```

### New: Standard META-INF Files

Added three standard libxposed configuration files:

- **`META-INF/xposed/java_init.list`** — Declares entry class:
  ```
  com.qimian233.ztool.hook.HookInit
  ```
- **`META-INF/xposed/module.prop`** — Declares API versions and scope mode:
  ```properties
  minApiVersion=102
  targetApiVersion=102
  staticScope=false
  ```
- **`META-INF/xposed/scope.list`** — Explicit list of target packages (previously embedded via `@array/xposed_scope`).
- Note: **`native_init.list`** is available if native hooks are introduced later.

---

## 3. Hook Entry Class Migration (`HookInit.java`)

| Legacy API | New API |
|---|---|
| Implement `IXposedHookLoadPackage` | Extend `XposedModule` abstract class |
| Override `handleLoadPackage(XC_LoadPackage.LoadPackageParam)` | Override `onModuleLoaded(...)`, `onPackageLoaded(...)`, `onSystemServerStarting(...)` lifecycle callbacks |
| Hook own process `isModuleActive()` with `XC_MethodReplacement.returnConstant(true)` | **No longer hook own process** (prohibited by libxposed); listen for binder activation via `XposedServiceHelper` |
| `de.robv.android.xposed.*` imports | `io.github.libxposed.api.*` imports |

---

## 4. Base Hook Class Migration (`BaseHookModule.java`)

### Parameter Types

| Legacy | New |
|---|---|
| `handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam)` | `handleLoadPackage(XposedModuleInterface.PackageLoadedParam param)` |
| (None) | `handleSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param)` |

> [!IMPORTANT]
> System framework hooks previously relying on `handleLoadPackage()` callbacks must now use `handleSystemServerStarting()`. Ensure framework hook classes leave `handleLoadPackage()` as an empty no-op and implement their logic within `handleSystemServerStarting()`.

### Configuration Retrieval

| Legacy | New |
|---|---|
| `ModuleConfig.isModuleEnabled(name)` via `XSharedPreferences` + `reload()` | `this.xposed.getRemotePreferences("xposed_module_config")` via `XposedInterface`, **no manual `reload()` needed** |
| Standalone `ModuleConfig.java` singleton | Inlined into `BaseHookModule` via injected `xposed` interface |

### Hook Operations

| Legacy | New |
|---|---|
| `XposedHelpers.findAndHookMethod(cls, "methodName", XC_MethodReplacement...)` | `this.xposed.hook(method).intercept(chain -> result)` lambda style |
| `XposedHelpers.findClass(name, classLoader)` | `classLoader.loadClass(name)` standard reflection |
| `Log.i/e(TAG, msg)` | `this.xposed.log(level, TAG, msg)` via unified logger |

> [!NOTE]
> Within Hook modules, using `logger.<level>()` is recommended for structured logging.

### System Server Hook Dispatch

- Added `safeHandleSystemServerStarting()` dispatcher to dispatch tasks within `system_server`.

---

## 5. Module Activation Detection (`ModuleActivationProbe.kt`)

| Legacy Approach | New Approach |
|---|---|
| Hook `isModuleActive()` in own app process to return `true` | Use `XposedServiceHelper.registerListener()` to monitor `XposedService` bind/die events |
| Self-hooking (unsupported in libxposed) | Binder callbacks confirm framework status: `onServiceBind` → active=true, `onServiceDied` → active=false |

---

## 6. Hook Manager Refactoring (`HookManager.java`)

- `initialize()` accepts `XposedInterface xposed`, injected into every registered `BaseHookModule`.
- Added `handleSystemServerStarting()` to iterate and dispatch system server callbacks across all modules.
- Modules clearly grouped by target packages.
- Added `HookTestModule` (`hook_test`) for validating libxposed callbacks.

---

## 7. PreferenceHelper Simplification (`PreferenceHelper.java`)

| Legacy | New |
|---|---|
| Based on `XSharedPreferences`, requiring `reload()` prior to every read | Based on `SharedPreferences` (via `XposedModule.getRemotePreferences()`), **no reload required** |
| Singleton pattern holding `XSharedPreferences` instance | Factory methods `wrap(XposedModule)` / `wrap(SharedPreferences)` |
| Complex handling for reload failures and cache invalidation | Lightweight wrapper with caching managed by framework |

---

## 8. App-side Preferences Access (`ModulePreferencesUtils.java`)

- Prefers reading remote configuration via `ModuleActivationProbe.currentService.getRemotePreferences()` when active.
- Fallback avoids deprecated `MODE_WORLD_READABLE`, using `MODE_PRIVATE`.

---

## 9. Removal of `ModuleConfig.java`

`app/src/main/java/com/qimian233/ztool/config/ModuleConfig.java` was deleted. Responsibilities shifted to:
- `BaseHookModule.isEnabled()`
- `PreferenceHelper.wrap()`

---

## 10. Mass Migration of Hook Modules (~50 Files)

Uniform migration pattern applied across all Hook modules:

1. `import de.robv.android.xposed.*` → `import io.github.libxposed.api.*`
2. `XC_LoadPackage.LoadPackageParam lpparam` → `XposedModuleInterface.PackageLoadedParam param`
3. `lpparam.classLoader` → `param.getDefaultClassLoader()`
4. `XposedHelpers.findClass(name, classLoader)` → `classLoader.loadClass(name)`
5. `XposedHelpers.findAndHookMethod(cls, "method", args...)` → `xposed.hook(method).intercept(chain -> ...)`
6. Added explicit no-arg constructors (`public ClassName() {}`)
7. Target `"android"` updated to `"system"` (libxposed semantics: `system` represents `system_server`)

---

## Summary of Changes

1. **Dependencies**: Switched from `de.robv.android.xposed:api:82` to `io.github.libxposed:api:102` + `service`.
2. **Declarations**: Migrated from Manifest `<meta-data>` to `META-INF/xposed/` files.
3. **Entry Class**: Migrated from `IXposedHookLoadPackage` to `XposedModule` lifecycle callbacks.
4. **Interception**: Replaced `findAndHookMethod` with `xposed.hook().intercept(chain -> ...)` lambdas.
5. **Preferences**: Replaced `XSharedPreferences.reload()` with `getRemotePreferences()`.
6. **Activation**: Replaced self-hooking with `XposedServiceHelper` binder listeners.
7. **System Server**: Added dedicated `handleSystemServerStarting` lifecycle support.
8. **Modules**: All Hook modules updated to new signatures, classloading, and logging.

## Notes

### For Hook Developers

1. `XposedHelpers` is replaced by standard reflection and `findMethod` / `findField` helpers in `BaseHookModule`.
2. Reflection objects require accessible permissions (`setAccessible(true)` handled automatically by helpers).
3. Interceptions follow an *OkHttp-style interceptor chain*.

### For Platform Developers

1. Scopes can be updated dynamically.
2. Framework name and version can be inspected via the libxposed service interface.
3. `RemotePreferences` standardizes preferences access across processes.
4. Hot reload is supported via API 102+, eliminating unnecessary scope restarts.
# libxposed Basic Guide (ZTool Edition)

This project uses the libxposed API rather than traditional Rovo89 Xposed; their syntaxes are substantially different. This document outlines the fundamental approach to authoring Hooks within the ZTool project. Familiarity with Java reflection and Xposed concepts is assumed.

## Hook Classes

### Choosing a Base Class
Depending on your Hook target, your Hook class must inherit from one of two base classes:
- `AppHookModule`: For non-framework apps (e.g. `SystemUI (com.android.systemui)`, Launcher, Settings)
- `SystemHookModule`: For system framework hooks targeting `android` and `system`

Directly subclassing `BaseHookModule` is prohibited.

```kotlin
// Inheriting AppHookModule
class YourAppHooker : AppHookModule() {
    // implement methods here...
}
```

```kotlin
// Inheriting SystemHookModule
class YourSystemServerHooker : SystemHookModule() {
    // implement methods here...
}
```

### Implementing Abstract Methods
Hook classes implement three key methods:
- `getModuleName(): String`: Returns the unique identifier of the module. This identifier must be pre-registered in `PreferenceKeys.kt`. Do not hardcode strings:
  ```kotlin
  override fun getModuleName(): String = PreferenceKeys.KEY_VAL_NAME.name
  ```
  For preference key registration, see [Add_New_Preference_Key_zh-CN.md](../preference_key/Add_New_Preference_Key_zh-CN.md).
  > [!TIP]
  > For temporary testing of a Hook under project infrastructure, returning `PreferenceKeys.TEST_HOOK.name` causes the base class to permit execution automatically without UI changes. Production Hooks must not use this key.

- `getTargetPackages(): Array<String>`: Returns target host packages. Must be pre-registered in `ScopeKeys.kt`:
  ```kotlin
  override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SCOPE1.packageName)
  ```
  > [!NOTE] Handling Multiple Hosts
  > If a feature spans both system framework and user apps, do not combine them in a single class. Instead:
  > - Create a class inheriting `SystemHookModule` under `com.qimian233.ztool.hook.modules.systemframework`
  > - Create an `AppHookModule` class under `com.qimian233.ztool.hook.modules.<app-category>`
  > Prefer returning a single target scope per class, using the same preference key to toggle both.

- `handleLoadPackage(param: PackageLoadedParam)` (`AppHookModule`) or `handleSystemServerStarting(param: SystemServerStartingParam)` (`SystemHookModule`).

### ClassLoader
For `AppHookModule`, use `param.defaultClassLoader`.
For `SystemHookModule`, use `param.classLoader`.

## Reflection & Lookup Helpers
libxposed requires manual reflection to locate targets. `BaseHookModule` provides lookup helpers that search up the inheritance hierarchy:

- `findField(startClass: Class<*>?, name: String): Field`: Traverses inheritance hierarchy, sets accessible, and returns the field (throws `NoSuchFieldException` if not found).
- `findMethod(startClass: Class<*>?, name: String, vararg parameterTypes: Class<*>?): Method`: Traverses inheritance hierarchy and returns the method (throws `NoSuchMethodException` if not found).

Examples:

```kotlin
// Finding a method (see AllowRelativeAppLaunch.kt)
val classLoader: ClassLoader = param.classLoader
val securityBinderClass: Class<*> = classLoader.loadClass(
    "com.android.server.ZuiSecurityService$ZuiSecurityServiceBinder"
)
val getStatusMethod: Method = findMethod(
    securityBinderClass, "getRelativeAppStatus",
    String::class.java, String::class.java
)
```

```kotlin
// Finding fields (see CustomGridSize.kt)
val numColsField = findField(gridOptionClass, "numColumns")
numColsField.set(thisObject, CUSTOM_COLUMNS)
val numRowsField = findField(gridOptionClass, "numRows")
numRowsField.set(thisObject, CUSTOM_ROWS)
```

## Hooking Located Methods
Use `BaseHookModule.hookWithId()`:

```kotlin
fun hookWithId(
    target: Executable,                                   // Located method/constructor
    id: String,                                           // Unique ID for atomic replacement
    hooker: Hooker,                                       // libxposed Chain SAM
    priority: Int = PRIORITY_DEFAULT,                     // Execution priority (default 50)
    exceptionMode: ExceptionMode = ExceptionMode.DEFAULT // Exception mode (default follows global)
): XposedInterface.HookHandle
```

IDs must be unique within each scope:

```kotlin
// In com.android.settings scope
hookWithId(method1, "id1", SAM)
hookWithId(method2, "id2", SAM)

// In com.android.systemui scope
hookWithId(method1, "id1", SAM)
```

> If duplicate IDs are used on the same method in the same scope, the new Hook **atomically replaces** the previous Hook (used by hot reloading).

### priority: Handling Competing Hooks

When multiple Hooks target the **same method**, the framework organizes them by descending priority: highest priority executes first.
- Range: `Int.MIN_VALUE` (`PRIORITY_LOWEST`) to `Int.MAX_VALUE` (`PRIORITY_HIGHEST`); default `PRIORITY_DEFAULT` is 50.
- Explicitly set priority when ordering relative to other hooks is required.

### exceptionMode: Exception Handling

- `DEFAULT`: Follows global exception mode in `module.prop` (`PROTECTIVE` by default).
- `PROTECTIVE`: Catches and logs Hooker exceptions, continuing execution as if unhooked. Recommended to prevent system crashes.
- `PASSTHROUGH`: Propagates exceptions to callers. Recommended for debugging.

## libxposed Chain SAM

The `Chain` functional interface drives hook interceptors:

```kotlin
// Replacing beforeHookedMethod:
{ chain -> 
    // do something before method executes
    chain.proceed() // let original method execute
}
```

```kotlin
// Replacing afterHookedMethod:
{ chain ->
    val result = chain.proceed() // let original method execute first
    // do something after
    result
}
```

```kotlin
// Combining before and after:
{ chain ->
    // do something before
    val result = chain.proceed()
    // do something after
    result
}
```

```kotlin
// Replacing method completely:
{ chain ->
    // return custom value without calling chain.proceed()
    customReturnValue
}
```

```kotlin
// Early exit:
{ chain ->
    val result = chain.proceed()
    if (!isEnabled()) {
        return@hookWithId result
    }
}
```

### Chain Properties and Methods
- `chain.thisObject`: Instance pointer (`null` for static methods).
- `chain.args`: Argument array, e.g. `chain.args[0]`.
- `chain.proceed()`: Advances execution. Pass argument arrays to modify parameters:
  ```kotlin
  hookWithId(targetMethod, "target_2") { chain ->
      try {
          val argList = chain.args.toMutableList()
          if (argList[1] != 0) {
              argList[1] = 0
              logger.debug("Modified red dot count to 0!")
          }
          chain.proceed(argList.toTypedArray())
      } catch (th: Throwable) {
          logger.error("Failed to set red dot count to 0!", th)
          chain.proceed()
      }
  }
  ```
- `chain.proceedWith(newThis, newArgs)`: Replaces `this` pointer or parameters.

## Logging System
See [migrate_and_use_new_logging_system.md](../new_log_system/migrate_and_use_new_logging_system.md) for logging conventions and levels.

## Cross-Process Preferences (Read-Only)

Read configuration values via `remotePreferences` (read-only):

```kotlin
val prefs = remotePreferences
CUSTOM_ROWS = prefs.getInt(PreferenceKeys.CUSTOM_LAUNCHER_ROW.name, 4)
CUSTOM_COLUMNS = prefs.getInt(PreferenceKeys.CUSTOM_LAUNCHER_COLUMN.name, 6)
```

## Anti-Obfuscation
DexKit is supported via the offline indexer system. Do not execute synchronous native DexKit calls inside Hook loading paths to maintain hot-reload safety. See [README.md](../dex_index/README.md).
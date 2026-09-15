# PLAN: Offline DexKit Index (Decoupling DexKit to Lift Hot-Reload Restrictions)

> Implementation plan for agents. Status: **Implemented**.
> Design decisions: Storage = libxposed **Remote Files**; Indexer architecture = **One per scope**; Scope = Framework + all 7 modules.

## 1. Background and Objectives

Current state: 7 Hook modules synchronously executed DexKit queries in `handleLoadPackage` (inside the target process via `DexKitBridge.create`, loading native libraries and mmapping APKs), preventing processes loading these Hooks from safely hot reloading (hot reload replays create bridge instances on the same APK repeatedly, causing native state conflicts).

Objective: Move DexKit queries from the "hook loading path" to "module app-side precomputation":
After installation, update, or target APK changes, the app process scans the target APK with DexKit, writing the results to **configuration files in the module's private directory**; during hook loading, results are read via **libxposed Remote Files** (`openRemoteFile`). The loading path is zero-DexKit and zero-native.

## 2. Key Mechanisms

- libxposed API 102 `XposedInterface`: `listRemoteFiles()` / `openRemoteFile(name)`.
- LSPosed service implementation (`LSPInjectedModuleService` + `ConfigFileManager`):
  `openRemoteFile` resolves to `/data/user/<userId>/<pkg>/files/<name>` —
  **the module app's own `filesDir`**. The daemon reads on its behalf with elevated privileges, **without requiring chmod, preserving privacy**.
- File name constraints: No `/`, `\`, `.`, `..` (`com.zui.launcher.json` is valid).
- Capability flag: `PROP_CAP_REMOTE` (`1L << 1`); embedded/legacy frameworks throw `UnsupportedOperationException` / `FileNotFoundException` / `AbstractMethodError`.
- The writing side uses normal `context.filesDir` writes, immediately readable without server-side caching.

## 3. Architecture

```
Stage A (Module app process):
  Trigger (Receiver / startup fingerprint check / Settings manual refresh)
    → DexIndexManager
        ├ For each scope package: sourceDir + splitSourceDirs → DexKitBridge.create(...)
        ├ Run the unique Indexer for that scope (migrated lookup logic)
        └ Atomically write files/dex_index/<scopePkg>.json (including APK fingerprints)

Stage B (Target process, during hook loading):
  handleLoadPackage
    → DexIndexStore.lookup(xposed, scopePkg)          // openRemoteFile + gson
        ├ Capability check PROP_CAP_REMOTE + try-catch
        ├ In-process caching (read once per process)
        └ Any failure → null → caller falls back to hardcoded defaults (original semantics preserved)
```

## 4. File Manifest

### App-side (Pure Kotlin, **libxposed dependency prohibited**)

| File | Responsibility |
|---|---|
| `dexindex/DexIndexer.kt` | Interface: `scopePackage` + `index(bridge, context): JsonObject` |
| `dexindex/LauncherDexIndexer.kt` | Scope `ScopeKeys.LAUNCHER`: queries for CleanGlobalSearch + DisableForceStop + ZuiLauncherHotseatHook |
| `dexindex/SystemUiDexIndexer.kt` | Scope `ScopeKeys.SYSTEM_UI`: queries for NoChargeAnimation + SystemUINetworkSpeeddoublelayerHook |
| `dexindex/MobileDesktopDexIndexer.kt` | Scope `ScopeKeys.MOBILE_DESKTOP`: queries for BypassShareWarningHook + DisableNearbyShareAutoOffHook |
| `dexindex/DexIndexRegistry.kt` | `val indexers: List<DexIndexer>` (unique mapping of scope → indexer) |
| `dexindex/DexIndexManager.kt` | Execution entry: APK paths (splits), bridge lifecycle, indexer execution, atomic write, fingerprinting, exception isolation |
| `dexindex/DexIndexConstants.kt` | Directory name `dex_index`, schemaVersion, file name rules, JSON key constants |

### Hook-side (Only component allowed to depend on libxposed)

| File | Responsibility |
|---|---|
| `hook/base/DexIndexStore.kt` | `lookup(xposed, scopePkg): JsonObject?` + `string(...)`; capability check, openRemoteFile, gson parsing, in-process caching |

### Trigger and UI

| File | Responsibility |
|---|---|
| `dexindex/DexIndexReceiver.kt` | `ACTION_MY_PACKAGE_REPLACED` + `ACTION_PACKAGE_ADDED` (matching module package) triggers scan |
| Settings Screen (`SettingsRoute` + ViewModel/Repository) | Manual "Refresh Index" entry + displays last indexing timestamp and status |

### Modified Files

| File | Changes |
|---|---|
| `AndroidManifest.xml` | Register `DexIndexReceiver` |
| `ZToolApplication.kt` | Compare APK fingerprints on launch; rescan in background on mismatch (covers OTA target app updates) |
| 7 Hook files | Remove DexKitHelper/bridge queries in `handleLoadPackage`; switch to `DexIndexStore` lookup + keep hardcoded fallbacks |
| `docs_archive/dex_index/` | Integration guides; update `AGENTS.md` Hook architecture section |

### Removed Files

| File | Notes |
|---|---|
| `hook/base/DexKitHelper.kt` | No longer referenced on hook side after migration; dexkit dependency retained for app-side indexers |

## 5. JSON Schema (One file per scope)

`files/dex_index/com.zui.launcher.json`

```json
{
  "schemaVersion": 1,
  "generatedAt": 1730000000000,
  "apk": { "path": "/system/priv-app/.../base.apk", "lastUpdateTime": 123, "signatureHash": "sha256-hex" },
  "modules": {
    "CleanGlobalSearch": { "hotwordInitMethod": "K0", "hotwordDataMethod": "E0" },
    "DisableForceStop":   { "forceStopMethod": "c" },
    "ZuiLauncherHotseatHook": { "loaderCursorBMethod": "b" }
  }
}
```

- Module keys use `getModuleName()` (matching PreferenceKeys).
- Existing query logic inside Indexers is preserved intact (including usingFields, field type fallbacks, method traversals), changing only "take first match" to "serialize result".
- Fingerprints used for invalidation: `lastUpdateTime` + SHA-256 of `PackageInfo.signatures[0]` (minSdk 27 uses GET_SIGNATURES).
- Atomic writing: write `.tmp-<pkg>.json` first, then rename.

## 6. Indexer Output Keys (Migration Mapping)

| Scope | Module | Output Key | Source Method |
|---|---|---|---|
| launcher | CleanGlobalSearch | `hotwordInitMethod` / `hotwordDataMethod` | `discoverInitMethods` / `discoverE0Method` |
| launcher | DisableForceStop | `forceStopMethod` | `findCMethodName` |
| launcher | ZuiLauncherHotseatHook | `loaderCursorBMethod` | `findBMethodName` |
| systemui | NoChargeAnimation | `handlerFieldName` | findClass+FieldsMatcher (with fallback logic) |
| systemui | SystemUINetworkSpeeddoublelayerHook | `handlerInnerClass` | `findHandlerInnerClass` DexKit section |
| mobiledesktop | BypassShareWarningHook | `managerClass` / `managerFactoryMethod` / `managerSetMethod` / `dialogMethod` / `tileRefreshMethod` | Respective findClass/findMethod calls |
| mobiledesktop | DisableNearbyShareAutoOffHook | `targetClass` / `targetMethod` | findClass(methods) |

## 7. Hook Migration Pattern

```kotlin
// Before
val bridge = DexKitHelper.getBridgeForApp(param.applicationInfo)
val name = discoverInitMethods(bridge).firstOrNull() ?: "K0"

// After
val idx = DexIndexStore.lookup(xposed, ScopeKeys.LAUNCHER.packageName)
val name = idx?.getAsJsonObject(moduleName)?.get("hotwordInitMethod")?.asString ?: "K0"
```

- Failed reads (legacy framework / not indexed / missing file) silently fall back to hardcoded defaults without regression.
- **Strictly prohibit** performing IO inside `hookWithId` lambdas—configurations must be resolved during `handleLoadPackage`.

## 8. Implementation Steps

1. Create `dexindex` framework (constants/interface/registry/Manager/Store) + register Receiver in Manifest
2. `LauncherDexIndexer.kt` (migrate 3 module queries)
3. `SystemUiDexIndexer.kt` (migrate 2 module queries)
4. `MobileDesktopDexIndexer.kt` (migrate 2 module queries)
5. Triggers: Receiver + `ZToolApplication` fingerprint check + Settings manual refresh
6. Migrate 7 Hooks, remove `DexKitHelper.kt`
7. Documentation (`docs_archive/dex_index/` + AGENTS.md)
8. Verification: `assembleDebug`; `git diff --check`; device checklist

## 9. Verification Plan

- Compilation: `./gradlew assembleDebug`
- Static: `git diff --check`; confirm no residual DexKit imports across the 7 Hooks
- Real Device:
  1. Open ZTool upon first installation → `files/dex_index/*.json` generated
  2. Hooks fall back safely before index is generated
  3. Restart target process after indexing → hooks take effect
  4. Target app OTA (updating lastUpdateTime/signature) → rescan triggered
  5. Hot reload: processes loading DexKit-dependent Hooks can reload cleanly (replay has no DexKit invocations)
  6. Legacy/embedded frameworks (no remote files) → silent fallback

## 10. Risks and Mitigations

- First installation startup race condition: target process may start before indexing completes → fallback protects against failure
- Legacy frameworks lacking `openRemoteFile` throw `AbstractMethodError` → protected with try-catch + `getApiVersion()` guard
- Index invalidation on target app updates → fingerprint checking triggers re-indexing (requires restarting target process to take effect)
- Single indexer failure → exception isolation ensures other indexers succeed; missing fields fall back safely on Hook side

# Offline DexKit Index

> Resolves the issue where DexKit queries break LSPosed hot reloading: moves DexKit method lookups from the "hook loading path" to "module app-side precomputation", allowing hooks to read precomputed result files at load time.

## Background

Synchronously executing `DexKitBridge.create()` inside `handleLoadPackage` (within the target process) loads native libraries and mmaps APKs. When hot reloading replays across the same APK with repeated `create()` calls, native state conflicts occur, preventing target processes from safely hot reloading. This mechanism extracts lookups completely out of the hook loading path.

## Architecture

```
Stage A (Module app process):
  Trigger (DexIndexReceiver / ZToolApplication launch fingerprint check / Settings manual refresh)
    → DexIndexManager
        ├ For each target scope package: sourceDir + splitSourceDirs → DexKitBridge.create(...)
        ├ Run the unique Indexer for that scope (original lookup logic)
        └ Atomically write filesDir/<scopePackage>.json (including APK fingerprints, located at the root of the module's filesDir)

Stage B (Target process, during hook loading):
  handleLoadPackage
    → DexIndexStore.lookup / string          // openRemoteFile + gson, in-process caching
        ├ Capability check PROP_CAP_REMOTE + try-catch
        └ Any failure → null → caller falls back to hardcoded defaults (semantic behavior preserved)
```

## Key Mechanism: libxposed Remote Files

- The hook process reads files under the **module's private root directory** (`filesDir`) via `XposedInterface.openRemoteFile(name)`. The LSPosed daemon handles privileged reading on its behalf, **without needing chmod, remaining completely private**.
- File names must be simple names (no `/`, `\`, `.`, `..`). Furthermore, **the Remote Files root is `filesDir` itself and does not support subdirectories**—index files must be written directly in the root of `filesDir` (`<scopePackage>.json`).
- If legacy or embedded frameworks do not support this, they throw `UnsupportedOperationException`, `FileNotFoundException`, or `AbstractMethodError` → `DexIndexStore` silently returns null, and the hook falls back to hardcoded values.

## Directory & File Structure

```text
com.qimian233.ztool.dexindex/
  DexIndexConstants.kt       // Directory name / schema version / module key / field key constants
  DexIndexer.kt              // Interface: scopePackage + index(bridge, context): JsonObject
  LauncherDexIndexer.kt      // com.zui.launcher scope (3 module queries)
  SystemUiDexIndexer.kt      // com.android.systemui scope (2 module queries)
  MobileDesktopDexIndexer.kt // com.motorola.mobiledesktop scope (2 module queries)
  DexIndexRegistry.kt        // Indexers registry (scope → Indexer unique mapping)
  DexIndexManager.kt         // App-side runner: bridge / atomic write / fingerprinting / exception isolation
  DexIndexReceiver.kt        // Module install / update triggers

com.qimian233.ztool.hook.base/
  DexIndexStore.kt           // Hook-side read-only utility (the only index class depending on libxposed)
```

## Adding a Hook That Uses DexKit

1. If the target package already has a scope Indexer, migrate the query code into the corresponding Indexer (wrapped in a try-catch block); otherwise create a new `XxxDexIndexer` and register it in `DexIndexRegistry` (scopePackage references `ScopeKeys`).
2. Add output field keys to `DexIndexConstants.Keys`; if the module key does not exist, add it to `ModuleKeys` (must match the Hook's `getModuleName()`).
3. **Indexers do not write fallback values**: if a query fails, simply do not write the key, and let the Hook side fall back to hardcoded defaults.
4. On the Hook side (during the `handleLoadPackage` callback stage; do not perform IO inside lambdas):
   ```kotlin
   val name = DexIndexStore.string(
       xposed, ScopeKeys.XXX.packageName,
       DexIndexConstants.ModuleKeys.MODULE, // or PreferenceKeys.MODULE_NAME.name
       DexIndexConstants.Keys.FIELD
   ) ?: "hardcoded_fallback"
   ```
5. Remove original DexKit-related imports/code in the Hook; no longer reference `DexKitHelper` (removed).

## Configuration File Format

`filesDir/com.zui.launcher.json` (module filesDir root)

```json
{
  "schemaVersion": 1,
  "generatedAt": 1730000000000,
  "apk": { "path": "...", "lastUpdateTime": 123, "signatureHash": "sha256-hex" },
  "modules": {
    "clean_global_search": { "hotwordInitMethod": "K0", "hotwordDataMethod": "E0" }
  }
}
```

- `apk` fingerprint (path + PackageInfo.lastUpdateTime + signature SHA-256) is used for invalidation detection:
  when the target app updates (OTA), `ZToolApplication` automatically rescans on startup.
- Writes are atomic (tmp + rename) to avoid the hook side reading incomplete JSON.

## Notes

- After indexing is complete, the **target process must be restarted or hot reloaded** to take effect (`handleLoadPackage` runs only once at process startup).
- On first installation, the target process may start before indexing completes → falls back to hardcoded defaults (identical to pre-refactor behavior).
- Never read index files or perform IO inside hook lambdas (`hookWithId` callbacks); resolve everything during the `handleLoadPackage` stage.
- `DexIndexManager` depends on the native dexkit library (app side); indexing failures do not impact app usability.

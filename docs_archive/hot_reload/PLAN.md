# Module Hot Reload Implementation Plan

## Overview

Based on investigations into libxposed API 102 hot reload examples (`reference/example`), ZTool had not implemented hot reload lifecycle callbacks, causing hot reload requests to be rejected.

This document records the phased implementation plan: Phases 1–2 are fully completed, and Phase 3 is under evaluation.

---

## Phase 1 — Enabling Hot Reload + Basic Hook Re-attachment ✅ Completed

> Commit: `a4412967`

### Objective

Prevent hot reload rejections and allow updated code to reinstall all Hooks.

### Modified Files

| # | File | Changes |
|---|------|------|
| 1 | `HookManager.java` | Split `initialize` → `registerAllModules()`; added `savedPackageParams`/`savedSystemServerParam` caches; added `reinitializeForHotReload()` + `replayAllHooks()` |
| 2 | `HookInit.java` | Override `onHotReloading` → `return true`; override `onHotReloaded` → reinitialize + replay + unhook old |

### Hot Reload Lifecycle

```
onHotReloaded:
  1. HookManager.reinitializeForHotReload(this)   ← Clear old modules, register all new modules
  2. HookManager.replayAllHooks()                  ← Replay cached package/systemServer parameters
  3. oldHookHandles.forEach(unhook)                ← Remove old Hooks
```

### Known Limitations

Old Hooks were unhooked after new Hooks installed, creating a brief vacuum window → resolved in Phase 2.

---

## Phase 2 — Atomic Replacement: Eliminating Hook Vacuum Windows ✅ Completed

> P0: `26357fb` | P1: `749b9b1d` | P2+: `e6a24c2e`

### Principle

Built into libxposed API: on the same module and executable, new Hooks with identical `setId()` **automatically and atomically replace** old Hooks.

### Infrastructure

| File | Changes |
|---|---|
| `BaseHookModule.java` | Added `hookWithId(Executable, String id, Hooker)` method |

### Coverage

| Batch | Directory | Files | Hooks | Method |
|---|---|:---:|:---:|---|
| P0 | `systemFramework/` | 8 | 14 | Manual per-file |
| P1 | `systemui/` + `setting/` | 31 | 101 | Python batch script |
| P2+ | `launcher/`, `gametool/`, `ota/`, `packageinstaller/`, `wallpaper/`, `documentsui/`, `safecenter/`, `mobiledesktop/` | 32 | 103 | Python batch script |
| **Total** | **All 16 module directories** | **71** | **218** | |

### ID Naming Conventions

Unique within each module, descriptive lowercase + underscore. Automatically generated from method variable names (camelCase → snake_case). Duplicate IDs within the same file receive `_2`, `_3` suffixes.

### Hot Reload Lifecycle (Phase 2 Enhanced)

```
onHotReloaded:
  1. reinitializeForHotReload(this)     ← Register all new modules
  2. replayAllHooks()                   ← Replay lifecycle → hookWithId() with identical ID
                                         → Framework automatically executes replaceHook() atomic replacement
  3. oldHookHandles.forEach(unhook)     ← Remove residual old Hooks (replaced hooks are already invalidated, unhook is a no-op)
```

---

## Phase 3 — Resource Cleanup: Preventing Classloader Leaks

### Status: Under Evaluation

### Problem Manifest

| # | File | Issue | Type | Risk |
|---|---|---|---|---|
| 1 | `OwnerInfoHook.java:252` | `new Thread()` lacks termination mechanism | Thread | 🟡 Low — HTTP fetch thread, terminates quickly |
| 2 | `DexKitHelper.kt:24,32` | `System.loadLibrary("dexkit")` + unclosed bridge cache | Native | 🟡 Low — loadLibrary is one-time, old bridge unclosed |
| 3 | `NativeNotificationIcon.java:33` | `ThreadLocal<Boolean> isCtsMode` | ThreadLocal | 🟢 Very low — Prevents GC but does not break functionality |
| 4 | `PermissionControllerHook.java:75` | `ThreadLocal<Boolean> isRowVersionTls` | ThreadLocal | 🟢 Very low — Same as above |

### Impact Assessment

| Risk Item | Blocks Hot Reload? | Causes Crashes? | Practical Impact |
|---|:---:|:---:|---|
| Running threads | No (`onHotReloading` still returns true) | Potential race condition | Old thread might operate on stale objects after new code loads; however OwnerInfoHook thread is a short-lived HTTP fetch |
| ThreadLocal residual | No | No | Prevents old classloader GC → memory leak. A classloader is typically a few MBs, acceptable |
| Unloaded native libs | No | No (re-loading is a no-op) | `DexKitBridge` old instances retain native resources, but replaced by new bridge after reload |
| LsposedServiceProtector | N/A | N/A | Currently **unregistered** in HookManager, does not participate in hot reload |

### Recommendation

**Phase 3 Priority: Low.** Current hot reload capabilities (Phase 1+2) have been verified in testing (7 SUCCEEDED, 3 UNSUPPORTED). Phase 3 improvements target long-term memory efficiency (preventing classloader buildup over repeated reloads), without affecting correctness of individual reloads.

If long-term usage exhibits classloader leaks leading to OOM, implement Phase 3:
- Add `prepareForHotReload()` hook called by `onHotReloading()` in `BaseHookModule`
- Override in individual modules to clean up threads/ThreadLocals/native resources
- `HookInit.onHotReloading()` iterates over modules calling `prepareForHotReload()` before returning true

---

## Known Limitations — LSPosed Native Library Hot Reload Restrictions

### Symptoms

Hot reloading permanently returns `UNSUPPORTED` for the following 3 processes:
- `com.android.systemui`
- `com.zui.launcher`
- `com.motorola.mobiledesktop`

Error message: *"Hot reload with native libraries is supported only for stale targets."*

### Root Cause

```
ZTool APK packages libdexkit.so (from org.luckypray:dexkit:2.0.6)
  → LSPosed detects .so files in APK's lib/ directory
    → Marks module as "containing native libraries"
      → Tightens hot reload policy: only available for stale targets (running old module code)
        → These 3 processes become fresh upon restart (running latest code) → Permanently UNSUPPORTED
```

LSPosed evaluates the presence of native libraries at the **APK file level**, regardless of whether `System.loadLibrary` is called at runtime.
Once native code is loaded, it cannot be safely unloaded (`dlclose` is a no-op for most .so libraries on Android). Hence LSPosed restricts modules containing native libraries to stale processes only.

### Impact

These 3 processes automatically load the latest module code upon cold start, **requiring no hot reload to gain the newest Hooks**.
Hot reload is unnecessary for them—they are already in `UP_TO_DATE` status.

### Hook Modules Using DexKit

| Directory | File | DexKit Purpose |
|---|---|---|
| systemui/ | `NoChargeAnimation.java` | Obfuscated method signature lookup |
| systemui/ | `SystemUINetworkSpeeddoublelayerHook.java` | Obfuscated method signature lookup |
| launcher/ | `CleanGlobalSearch.java` | Obfuscated method signature lookup |
| launcher/ | `DisableForceStop.java` | Obfuscated method signature lookup |
| launcher/ | `ZuiLauncherHotseatHook.java` | Obfuscated method signature lookup |
| mobiledesktop/ | `BypassShareWarningHook.kt` | Obfuscated method signature lookup |
| mobiledesktop/ | `DisableNearbyShareAutoOffHook.kt` | Obfuscated method signature lookup |

### Evaluation & Decision

**Accept LSPosed restrictions.** The 3 `UNSUPPORTED` results are harmless and expected. Cold starts ensure latest Hooks take effect. (Note: Offline DexKit indexing now resolves this restriction).

---

## Verification Strategy

1. `./gradlew assembleDebug` builds cleanly ✅
2. Installed on device, triggered "Advanced Settings → Hot Reload All Modules" ✅
   - 7 SUCCEEDED, 3 UNSUPPORTED (processes with native libs; cold start already runs latest code)
3. Verified via Logcat that `onHotReloading` / `onHotReloaded` were called ✅
4. Verified Hook features remain functional after hot reload ✅

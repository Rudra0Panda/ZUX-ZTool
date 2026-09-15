# Migration Plan: Migrating Remaining Java Hooks to Kotlin

> Status: ✅ Completed (all 22 Hooks + 1 utility class migrated to Kotlin across 6 batch commits)
> Goal: Migrate the remaining 22 Java Hooks + 1 Java utility class under `hook/modules/` to Kotlin, eliminating legacy Java Hook code in adherence to the AGENTS.md requirement that "all new code must be Kotlin".

## 0. Background and Constraints

- Previously, `hook/modules/` contained **61 Kotlin** and **23 Java** files.
- Among the 23 Java files, 22 were Hook classes and **1 was a pure utility class** `CustomDateFormatter` (referenced by both Hooks and Repositories).
- All 22 Hooks were registered in `HookManager.kt`, all preference keys existed in `PreferenceKeys.kt`, and all scopes existed in `ScopeKeys.kt` → **no new keys/scopes required** (`OwnerInfoHook` split reuses existing `AUTO_OWNER_INFO` key).
- Migration principles (aligned with Kotlin Hook paradigms):
  - Inherit `AppHookModule` / `SystemHookModule` (split dual-callback modules into two single-callback modules).
  - `getModuleName()` returns `PreferenceKeys.CONSTANT.name`; hardcoded strings prohibited.
  - `getTargetPackages()` returns `ScopeKeys.CONSTANT.packageName`.
  - Preference reading uses `remotePreferences` property.
  - Hooks use `hookWithId(target, id) { chain -> ... }` lambda form.
  - Logging uses `logger.<level>`.
  - Reflection prefers `findMethod`/`findField` (recursive search up inheritance hierarchy via `HookReflectionHelper.kt`).
  - Obfuscated method lookups prefer offline DexKit index `DexIndexStore.string(...)`, falling back to hardcoded defaults.
- Reference paradigms:
  - Standard App Hook: `hook/modules/ota/LenovoOTAHook.kt`
  - Framework Hook: `hook/modules/systemframework/NoMorePasswordPer24H.kt`
  - Indexed App Hook: `hook/modules/mobiledesktop/DisableNearbyShareAutoOffHook.kt`
  - Dual-callback split precedent: `DisableGameAudioApp.kt` (gametool) + `DisableGameAudio.kt` (systemframework)

## 1. Inventory (23 Java Files)

### 1.1 Hook Class Manifest & Classification

| # | File | Module | Size | Notes |
|---|---|---|---|---|
| 1 | `gametool/AutoMistakeTouchHook.java` | GameTool | Large (~280) | `Handler.postDelayed` delayed tasks |
| 2 | `gametool/CpuFrequencyFix.java` | GameTool | Medium | None |
| 3 | `launcher/misc/DisableForceStop.java` | Launcher | Large (~470) | **Uses DexIndexStore** |
| 4 | `launcher/misc/RecentTaskMemoryViewHook.java` | Launcher | Large (~460) | None |
| 5 | `mobiledesktop/AutoAcceptFileTransferHook.java` | MobileDesktop | Medium (~207) | **Indexed** (4-link chained lookup) |
| 6 | `safecenter/DisableAllVirusScans.java` | SafeCenter | Medium | Dual target packages |
| 7 | `safecenter/EnableAutorunByDefault.java` | SafeCenter | Small | Dual target packages |
| 8 | `setting/AppInfoHeaderDetailsHook.java` | Settings | Medium | None |
| 9 | `setting/OwnerInfoHook.java` | Settings/System | Large (~473) | **Dual callbacks + BroadcastReceiver + network thread; split** |
| 10 | `systemui/misc/CustomControlCenterDate.java` | SystemUI | Large (~420) | Depends on CustomDateFormatter |
| 11 | `systemui/misc/NotificationCenterTransparency.java` | SystemUI | Medium | None |
| 12 | `systemui/qs/BrightnessSliderPercentageHook.java` | SystemUI | Large (~517) | None |
| 13 | `systemui/qs/ControlCenterNoTileLabelsHook.java` | SystemUI | Medium | None |
| 14 | `systemui/qs/CustomQsColor.java` | SystemUI | Medium | None |
| 15 | `systemui/qs/CustomQsRoundCorner.java` | SystemUI | Medium | None |
| 16 | `systemui/qs/VolumeSliderPercentageHook.java` | SystemUI | Large (~510) | None |
| 17 | `systemui/statusbar/CustomStatusBarClock.java` | SystemUI | Large (~380) | Depends on CustomDateFormatter |
| 18 | `systemui/statusbar/NativeNotificationIcon.java` | SystemUI | Small | None |
| 19 | `systemui/statusbar/NotificationIconHook.java` | SystemUI | Medium | None |
| 20 | `systemui/statusbar/StatusBarClockSecondsHook.java` | SystemUI | Medium | None |
| 21 | `systemui/statusbar/SystemUIBatteryHook.java` | SystemUI | Large (~290) | Resource name reflection |
| 22 | `systemui/statusbar/SystemUINetworkSpeeddoublelayerHook.java` | SystemUI | Large (~310) | **Uses DexIndexStore** |

### 1.2 Non-Hook Utility Class

- `systemui/misc/CustomDateFormatter.java` (~280): Date formatting utility (lunar calendar, solar terms, etc.), referenced by `CustomControlCenterDate`, `CustomStatusBarClock`, and two Repositories (`ControlCenterSettingsRepository.kt:76`, `StatusBarSettingsRepository.kt:51`). Class name, package, and static method signatures must be preserved.

## 2. Phased Migration Strategy

- **Batch A (Straightforward ports, no complex dependencies)**: `CpuFrequencyFix`, `EnableAutorunByDefault`, `DisableAllVirusScans`, `AppInfoHeaderDetailsHook`, `NativeNotificationIcon`, `NotificationIconHook`, `StatusBarClockSecondsHook`, `ControlCenterNoTileLabelsHook`, `CustomQsColor`, `CustomQsRoundCorner`, `NotificationCenterTransparency`.
- **Batch B (Large files direct translation)**: `AutoMistakeTouchHook`, `RecentTaskMemoryViewHook`, `BrightnessSliderPercentageHook`, `VolumeSliderPercentageHook`, `SystemUIBatteryHook`.
- **Batch C (Utility class pre-requisite)**: `CustomDateFormatter` → then migrate dependent `CustomControlCenterDate`, `CustomStatusBarClock`.
- **Batch D (Indexing)**: `AutoAcceptFileTransferHook` (migrate 4 chained lookups to DexKit index).
- **Batch E (Decomposition)**: `OwnerInfoHook` split into Settings-side and System-side Kotlin Hooks.
- **Batch F (Finalization)**: `DisableForceStop`, `SystemUINetworkSpeeddoublelayerHook` (maintain DexIndexStore lookups).

## 3. Implementation Steps

1. Migrate Batch A (11 small/medium Hooks).
2. Migrate Batch B (5 large Hooks).
3. Migrate Batch C (`CustomDateFormatter` + 2 dependent files).
4. Migrate Batch D (`AutoAcceptFileTransferHook` + indexer).
5. Migrate Batch E (`OwnerInfoHook` split into `OwnerInfoSettingsHook` + `OwnerInfoSystemHook` + `OwnerInfoUpdater`).
6. Migrate Batch F (`DisableForceStop` + `SystemUINetworkSpeeddoublelayerHook`).
7. Delete all corresponding `.java` files upon porting.
8. Verify `./gradlew assembleDebug` compiles cleanly with zero `.java` files remaining in `hook/modules/`.

## 4. Key Architectural Decisions

- **D1 (BroadcastReceiver ownership)**: Preserved intact on both sides (`OwnerInfoSettingsHook` via Activity context; `OwnerInfoSystemHook` via ContextImpl context).
- **D2 (Shared logic helper)**: Created `OwnerInfoUpdater.kt` in `hook/modules/setting/` to encapsulate API fetching, JSON parsing, and lockscreen updates.
- **D3 (Indexing scope)**: Indexed all 4 lookups in `MobileDesktopDexIndexer` without bumping `SCHEMA_VERSION`.

## 5. Verification

- Compilation: `./gradlew assembleDebug`
- Reference audit: Verified zero residual Java files in `hook/modules/`
- Regression testing: Confirmed all preference keys and hook IDs preserved exactly.

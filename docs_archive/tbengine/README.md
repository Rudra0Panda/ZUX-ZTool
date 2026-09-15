# TB Engine (UDS Real-time Connection Engine) Hook Plan and Implementation Notes

Target application: `com.lenovo.tbengine` (ZUI tablet system update background engine, `android:persistent="true"`, no launcher entry).
This document records functional scope, hook point selection criteria, frontend entry points, and known constraints for future reference.

## 1. Scope

This release implements 4 Hooks (user requirements #2/#3/#4/#5), **excluding** payload signature verification bypass (#1) and telemetry reporting disabling (#6):

| Switch Key | Target Behavior | Status |
|---|---|---|
| `disable_tbengine_auto_download` | Disable automatic firmware package downloading | Implemented |
| `disable_tbengine_auto_install` | Disable automatic installation (AB background install / reboot to Recovery) | Implemented |
| `disable_tbengine_app_update` | Disable automatic updates for pre-installed apps and data packages | Implemented |
| `disable_tbengine_push` | Disable UPS push notification channel registration | Implemented |

All switches default to disabled (off); when turned off, the engine behavior is completely unchanged.

## 2. Hook Point Selection (Based on V1.1.1.260616 Decompilation Analysis)

`tbengine` is a state-machine driven background engine (containing two workflows in `MainService`: OTA and AppData).
The automated paths and user-initiated manual paths are **distinct methods** at the `ServiceController` layer, allowing surgical interception of automatic operations without impacting manual actions:

### 2.1 Disable Auto-Download (`DisableTbEngineAutoDownload`)

- Hook `com.lenovo.tbengine.core.services.ServiceController.startOrResumeDownload(Context)` → no-op.
  This method is invoked exclusively by automated background paths (advancing upon detecting a new release); user clicks route through `userStartOrResumeDownload(Context)`, which remains unaffected.
- Hook `com.lenovo.tbengine.core.policy.OtaPolicy.setmSettingNormalAutoDownload(boolean)` → force parameter `false`,
  `getmSettingNormalAutoDownload()` → force return `false`. Aligns with the approach used by `NoAutoOtaInstall` in the `ota` module, ensuring Wi-Fi auto-download policy bits stay off and preventing other paths from reopening it.

### 2.2 Disable Auto-Install (`DisableTbEngineAutoInstall`)

- Hook `ServiceController.startABInstalling(Context)` → no-op: Blocks automatic background installation after A/B seamless update verification succeeds.
- Hook `android.os.RecoverySystem.installPackage(Context, File)` → no-op: Primary switch for "reboot to recovery to install" on A-only devices.
  Note that this method also serves as the reboot entry after user confirmation; this switch means "prohibit the engine from automatically rebooting to install". When enabled, manual reboot confirmations in the Lenovo center will also not proceed (switch defaults to off; documented for users).
- Hook `OtaPolicy.setmSettingNormalAutoInstall(boolean)` / `getmSettingNormalAutoInstall()` → force `false` (nightly auto-install policy bit).

### 2.3 Disable Pre-installed App Auto-Updates (`DisableTbEngineAppUpdate`)

- Hook `ServiceController.startOrResumeAppDataDownload(Context)` → no-op.
  Automatic workflow progression for AppData (`AppDataNewVersionFound.doMyUiBackgroundJob`) and Whatsnew user confirmation paths converge at this method; the user path `userStartOrResumeAppDataDownload(Context)` is preserved.

### 2.4 Disable UPS Push (`DisableTbEnginePush`)

- Hook `com.lenovo.tbengine.core.push.PushControls.initPushChannel()` → no-op.
- Hook `PushControls.registerInitReceiver()` → no-op.
  Entry points exist only in `MyApplication.onCreate()` (PRC region) and device activation completion (`SmartScenarioController`), both flowing through these methods.
  When disabled, no tokens are registered, and no reporting is made to `tb-zui.lenovo.com/engine/push-message/register`.

### 2.5 General Considerations

- `tbengine` is a persistent system application with self-circuit-breaking safeguards (if the Service restarts >= 7 times in 5 minutes, it stops restarting). All exceptions inside Hook Chain SAMs must be caught and logged, never thrown.
- Method names are derived from decompilation of this specific version; if subsequent versions alter obfuscation, DexIndex must be rebuilt or hook points updated.
- Scope: `ScopeKeys.TB_ENGINE` exists, and `scope.list` includes `com.lenovo.tbengine`; no new entries needed.

## 3. Frontend Design

- Added `FeatureDestination.TbEngine("feature/tb-engine")`, with feature card visibility requiring `com.lenovo.tbengine` to be installed.
- `ScopeUtils.getScopes(FeatureDestination.TbEngine) = [ScopeKeys.TB_ENGINE]`, restart method `AmStop`
  (for persistent apps, after `am force-stop`, the system automatically relaunches them, equivalent to an engine reboot).
- New screen `screens/tbengine/TbEngineSettingsScreen.kt`, Repository in `data/tbengine/TbEngineSettingsRepository.kt`,
  ViewModel in `viewmodel/TbEngineSettingsViewModel.kt`. Contains 4 `SettingItem.Switch` items + a "Restart Engine" FAB in the bottom right (reusing the Ota screen confirmation dialog pattern).
- Strings: Paired in `res/values/strings.xml` and `res/values-en/strings.xml`.

## 4. Modified File Manifest

Backend:
- `app/src/main/java/com/qimian233/ztool/hook/modules/tbengine/DisableTbEngineAutoDownload.kt` (New)
- `app/src/main/java/com/qimian233/ztool/hook/modules/tbengine/DisableTbEngineAutoInstall.kt` (New)
- `app/src/main/java/com/qimian233/ztool/hook/modules/tbengine/DisableTbEngineAppUpdate.kt` (New)
- `app/src/main/java/com/qimian233/ztool/hook/modules/tbengine/DisableTbEnginePush.kt` (New)
- `app/src/main/java/com/qimian233/ztool/hook/base/HookManager.kt` (Register 4 modules)
- `app/src/main/java/com/qimian233/ztool/data/keys/PreferenceKeys.kt` (Add 4 BoolKeys)

Frontend:
- `app/src/main/java/com/qimian233/ztool/screens/features/FeaturesRoute.kt` (New destination + card)
- `app/src/main/java/com/qimian233/ztool/utils/ScopeUtils.kt` (Scope mapping)
- `app/src/main/java/com/qimian233/ztool/navigation/ZToolNavHost.kt` (Route + indexing)
- `app/src/main/java/com/qimian233/ztool/screens/tbengine/TbEngineSettingsScreen.kt` (New)
- `app/src/main/java/com/qimian233/ztool/viewmodel/TbEngineSettingsViewModel.kt` (New)
- `app/src/main/java/com/qimian233/ztool/data/tbengine/TbEngineSettingsRepository.kt` (New)
- `app/src/main/res/values/strings.xml`, `app/src/main/res/values-en/strings.xml`

## 5. Local OTA Resigning (`SignTbEngineLocalOta`)

### 5.1 Trigger Chain (`com.lenovo.ota` UI → `tbengine`)

```
UI Menu memu_localInstall → MainActivity.checkLocalOtaPackageFile()
  Checks /sdcard/ota.zip → ServiceController.startABLocalInstalling()
  → Writes otaPackageBrief/PackageVerified=true
  → Broadcasts "com.lenovo.ota.ab.installing" (explicitly sent to tbengine/NotificationReceiver,
    permission lenovo.permission.udsengine.exported)
tbengine: NotificationReceiver → MessengerService → ServiceController.startABInstalling
  → SwfABInstalling.doMyPrimaryJob(): /sdcard/ota.zip → /data/ota_package/local_lenovoota.zip
    → PayloadSpecs.forNonStreaming → UpdateEngine.applyPayload
```

Hook intercept point is `SwfABInstalling.doMyPrimaryJob()` (tbengine worker thread, non-blocking, no ANR);
Also hooks `android.os.UpdateEngine.applyPayload` to inject `public_key` attribute (AOSP key rotation channel),
writing the public key PEM to `/data/ota_package/ztool_ota_pub.pem`.

### 5.2 Payload Format (TB710FU OTA_414_479774.zip, AOSP v2)

- Layout: `[24B header CrAU][manifest][metadata signature 267B][data chunk][payload signature 267B @ EOF]`;
  manifest field 4 `signatures_offset` / field 5 `signatures_size` are offsets **relative to the data chunk start**.
- Signature block is fixed 267B: `0a8802 128002 <256B RSA-2048 signature> 1d00010000`; metadata and payload signatures are isomorphic.
- Hash convention (verified against AOSP source): `METADATA_HASH` = SHA256(**header+manifest**);
  `FILE_HASH` = SHA256(entire payload.bin); metadata signature covers header+manifest;
  **payload signature covers header+manifest+data chunk**—AOSP DeltaPerformer's `signed_hash_calculator_`
  accumulates header+manifest (metadata signature verification) and the data chunk continuously,
  excluding only the intermediate metadata signature block (`DiscardBuffer(false, metadata_size_)`)
  and the trailing signature block itself (`DiscardBuffer(true, 0)`).
- Data chunk remains untouched ⇒ manifest and `signatures_offset`/`size` remain identical; resigning only replaces the two 256B signature values, preserving the total length of `payload.bin`.

### 5.3 Keys and Data Flow

- RSA-2048 key pairs are generated by `TbEngineSettingsRepository.ensureOtaSigningKeys()` when the TB Engine screen is opened for the first time; PKCS#8/X509 Base64 stored in `xposed_module_config` (`tbengine_ota_private_key` / `tbengine_ota_public_key`),
  read by the Hook side via `remotePreferences`; private key never leaves the device.
- During resigning, `payload.bin` / `payload_properties.txt` inside the zip remain STORED (`forNonStreaming` relies on offsets), with properties FILE_HASH / FILE_SIZE / METADATA_HASH / METADATA_SIZE updated synchronously.
- Disk overhead is approximately 2x package size in temporary space; signing duration is dominated by full-pass SHA256.

### 5.4 Real Device Verification (TB710FU, 2026-09-12)

- **Signature mathematical verification passed**: The expected metadata hash reported by `update_engine` matched the on-device measurement `SHA256(first 437820 bytes of payload blob)` (= header+manifest) completely, confirming that metadata hash coverage and signing algorithms (RSA-2048 PKCS#1 v1.5 / SHA-256) are correct.
- **`public_key` attribute ineffective**: ZUI's `update_engine` signature verification follows the `/system/etc/security/otacerts.zip` certificate path (log: `Verifying using certificates:`), ignoring the injected `public_key` attribute. Verified failure chain: injected attribute → engine attempts verification using OEM certificates → mismatch (26).

### 5.5 Trust Chain Solution: otacerts Certificate Addition Module (Implemented)

- ZTool app side `OtaCertBuilder` (pure Java hand-crafted DER without BouncyCastle) generates a self-signed X.509 v3 certificate (CN=ZTool OTA Local Signing, 20-year validity) using the existing RSA-2048 key pair, validated via `CertificateFactory` re-read; DER saved to `tbengine_ota_cert` preference.
- `TbEngineSettingsRepository.installOtaCertModule()`:
  1. Root `cat` reads original `/system/etc/security/otacerts.zip`;
  2. Appends ZTool certificate entry `ztool_ota.x509.pem` (STORED, preserving OEM certificates for cumulative trust);
  3. Generates Magisk/KSU module zip (`system/etc/security/otacerts.zip` overlay + `module.prop` + `customize.sh`);
  4. Installs via `magisk --install-module`, falling back to `ksud module install`.
- **KernelSU Note**: Requires an environment supporting meta-module mounts; otherwise systemless overlays will not take effect.
- Reboot after installation; local packages resigned with ZTool pass `update_engine` signature checks (fully compatible with otacerts certificate verification).

## 6. Deferred Items (Future Iterations)

- **Payload signature verification bypass**: `tbengine` only performs MD5 checks; payload signature checks occur in native `update_engine` and framework `RecoverySystem.verifyPackage`, which require system-framework hooks and lack a third-party package injection entry point; deferred.
- **Telemetry disabling**: Reporting entries for `PromptUtils.collectUNData` and obfuscated packages `a.a.a.b.*` / `com.chilkatsoft` are dispersed; requires establishing an offline DexIndex for `tbengine` before implementation; deferred.

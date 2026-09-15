# <p align="center">ZTool - LSPosed Customization Module for ZUXOS & Hello UI</p>

<div align="center">
  <img src="/app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" alt="ZTool Logo">

  <a href="https://github.com/qwqawa64/ZUX-ZTool"><img alt="Static Badge" src="https://img.shields.io/badge/GitHub-ZUX--ZTool-%23ADD8E6?style=for-the-badge"></a>
  <a href="https://github.com/LSPosed/LSPosed"><img alt="Static Badge" src="https://img.shields.io/badge/Framework-LSPosed-%23F48FB1?style=for-the-badge&color=%23F48FB1"></a>
  <a href="https://www.zuxos.com/"><img alt="Static Badge" src="https://img.shields.io/badge/Target-ZUXOS%2FHello%20UI-%23E2231A?style=for-the-badge"></a>
  <a href="https://github.com/qwqawa64/ZUX-ZTool/commits/master/"><img alt="GitHub commits since latest release" src="https://img.shields.io/github/commits-since/qwqawa64/ZUX-ZTool/latest?style=for-the-badge"></a>
</div>

> "Make ZUXOS Great, Not 'Again'."

> [!tip]
> This documentation is written based on version `20260913`.
> 
> Some features also apply to Hello UI / ZUI. We cannot guarantee feature availability across all device configurations and firmware versions.

## Overview

ZTool is an LSPosed enhancement module tailored for devices running ZUXOS and Hello UI.

### Game Optimization Features
- CPU Frequency Fix: Fixes CPU clock reading logic in game services
- Device Model Disguise: Enables Legion gaming mode
- Audio Control: Disables game mode audio post-processing to reduce audio latency
- Temperature Management: Corrects SoC temperature readings
- Mistouch Prevention: Allows automatically enabling mistouch protection

### Interface Customization
- Status Bar Clock: Format and style customization
- Status Bar Network Speed: Dual-row display (upload/download simultaneously) or readable format, custom refresh interval, hide slow speed
- Status Bar Battery: Battery percentage positioned outside the battery icon
- AOD Support: Force enable native Android AOD or Lenovo OLED always-on display
- Lock Screen Hitokoto: Custom Hitokoto API signatures on lock screen
- Charging Information: Displays real charging wattage or negotiated protocol wattage on the lock screen
- Notification Icons: Limit maximum status bar notification icons; use native notification icons
- Control Center Customization: Custom date display format in control center
- Font Customization: Import and apply custom fonts
- Charging Animation: Option to disable charging animations, or enable Legion Y700 animations on non-Y700 devices
- Quick Settings Tiles: No-label mode, custom tile colors, custom corner radius
- Control Center Blur: Custom blur intensity
- Volume / Brightness Sliders: Display exact percentage values
- Device Info Customization: Customize properties in the About Device panel
- Screen Transition Animation: Custom screen turn-on and turn-off animations
- App Info Details: Display target SDK and other package metadata with long-press copy
- Live Wallpaper: Support desktop live video wallpapers
- Charging Animation Duration: Configure display duration
- Portrait Control Center: Expanded control center layout in portrait orientation

### System Updates
- Enable local package installation
- Fetch incremental OTA update details
- Fetch fastboot / recovery firmware download links
- Disguise OTA system version
- Disable red dot notification badges when system updates are available

### Launcher Features
- Unlock dock bar pinned application limits
- Allow completely disabling the dock bar
- Hide recent applications in the dock / taskbar
- Automatically decline user agreements for recommended app folders
- Remove app update blue dot badges
- Suppress desktop notification badges for system updates
- No-label mode for home screen and app drawer
- Customize card swipe background killing behavior
- Display available RAM in recent tasks overview
- Clean up global search interface
- Custom home screen grid size
- Square launcher folders
- Batch uninstall applications directly from launcher
- Restore original settings and launcher icons

### One Vision / Multi-Window Features
- Landscape Mode Adaptation: Force landscape display for supported apps
- Blacklist Management: Clear app restrictions
- Dynamic Configuration: Manually configure parallel window parameters
- Remove small window and split-screen whitelist limits
- Split-Screen Support: Bypass app split-screen restrictions
- Floating Windows: Enhanced multi-window management

### Lenovo Share (Mobile Desktop / Ready For)
- Remove file transfer startup warning
- Prevent file transfer from automatically disconnecting
- Automatically accept incoming file transfer requests

### Package Installer
- Remove recommended application promotions and ads
- Skip warning confirmation screens
- Allow using native Android package installer
- Automatically grant requested permissions upon app installation

### Miscellaneous
- Dolby Audio: Allow turning off Dolby Atmos when using built-in speakers
- App Permission Manager: Option to use native Android permission manager
- AI Input Integration: Custom AI global input wake word
- Cloud Policy Blocking (Experimental)
- System Framework Restrictions Bypass:
  - Force allow screenshots (bypass FLAG_SECURE)
  - Bypass "Cannot use this folder" storage access restrictions
  - Open associated apps in floating window
  - Suppress dialog prompts when launching associated apps
  - Prevent brightness throttling during games
- Remove language restrictions on Chinese (PRC) ROMs

> More features are actively being added...

## Requirements

- System: ZUXOS / Hello UI
- Environment: Root + LSPosed Framework

> [!important]
> Compatibility with unofficial libxposed implementations has not been verified. If you experience issues while using third-party frameworks like Vector, issues may not be accepted.

## Installation

1. Download the latest APK from [Releases](https://github.com/qwqawa64/ZUX-ZTool/releases).
2. Install the APK and grant Root permissions.
3. Enable ZTool in LSPosed Manager and check the required scope applications.
4. Reboot the system to activate all hooks.

## Beta Builds

Untested development builds are available under the project's [GitHub Actions](https://github.com/qwqawa64/ZUX-ZTool/actions) page.

See [CHANGELOG.md](/CHANGELOG.md) for recent changes, and check [TODOS.md](/TODOS.md) for upcoming features.

## Disclaimer

- This module is intended for learning and personal customization only.
- Certain features require Magisk/KernelSU root environment.
- System-level modifications carry risks. Always back up your data and ensure you know how to recover your device before proceeding.

## Acknowledgements

- [dantmnf](https://github.com/dantmnf)'s [UnfuckZUI](https://github.com/dantmnf/UnfuckZUI) project, from which several implementations were adapted:
  - Native notification icons
  - Disable full-screen charging animation
  - Allow disabling Dolby Audio with speaker
  - Disable swipe-card app killing
  - Prevent automatic guest user creation
  - Maintain portrait orientation across reboots
  - Native package installer support
  - Native permission dialogs
  - Always allow package list access
  - Enable autorun by default

- [墨染_nlx](https://github.com/morannlx)'s [ZUXOS+](https://github.com/morannlx/me.inkdye.zuxos) project, which inspired several requested features:
  - Quick jump shortcut inside Settings
  - Custom About Device panel info
  - Control center tile no-label mode
  - Control center blur and transparency customization
  - Control center tile color customization
  - Control center tile corner radius customization
  - Disable system update red dot badge
  - Memory usage display in recent tasks
  - Extended package info in application details

> [!warning]
> Warning: Items in this TODO list are not "planned and guaranteed to be implemented". Whether a feature is actually implemented should be determined by the source code and distributed binaries.

## TODOs
- [x] Core Patch / Signature Bypass
- [x] Feature Search
- [x] Update Firstrun: Change agreement acceptance conditions and styling
- [ ] Reuse update check cache, do not repeat requests when an update has already been detected
- [ ] Custom desktop icon size
- [ ] Display error reasons when update check fails
- [ ] Resolve DNS pollution
- [ ] Fix square big folders after launcher grid size change
- [ ] Provide feature in Advanced Settings to delete /data/ota-package
- [ ] Add quick link to GitHub Issues
- [ ] Redesign home page
- [ ] Add overscroll bounce to Features page
- [ ] Force certain punctuation marks to use half-width characters
- [ ] Built-in lock screen Hitokoto API and regex (the configuration used by the home page)

## Done Until 20260913 Beta
- [x] Completed Credits: Hitokoto support, GitHub acceleration support
- [x] Reset all props modified by the module
- [x] Hide slow network speed on speed indicator
- [x] Prevent screen dimming during gaming
- [x] Centralized DexKit method name lookup with persistent list/file
- [x] Fixed module hot reload feature
- [x] Square launcher folders
- [x] No-label mode with separate controls for drawer and home screen
- [x] Batch app uninstallation in launcher
- [x] Block cloud policy sync (experimental)
- [x] Custom lock screen clock color (supported on ZUXOS 1.5)
- [x] Configurable charging animation duration
- [x] Updated enhanced landscape mode adaptation
- [x] Original settings and launcher icons

## Done Until 20260808 Beta
- [x] Desktop live wallpaper
- [x] Remove language restrictions on PRC ROMs
- [x] Lock screen power display with concatenated info: real voltage, current, power, battery temperature, etc.
- [x] Use Hook instead of executing shell commands for Native AOD
- [x] Launch associated apps in floating window
- [x] Hide recent apps in dock bar
- [x] Widen portrait control center
- [x] Automatically decline user agreement for recommended app folders on desktop
- [x] Suppress popups when launching associated apps
- [x] Remove app update blue dots (separated from no-label hook)
- [x] Settings app badge notification updates
- [x] Custom charging animation
- ~~[ ] Do not use su to obtain charging info~~ su is indispensable, but we added powerful customization features

## Done Until 20260710 Beta
- [x] Adjust memory display position, font size, and color
- [x] Adjust battery percentage display color
- [x] Suppress warning when launching Lenovo Share ~~Make Lenovo Share quick tile support tap to toggle~~
- [x] Disable launcher dock bar
- [x] Automatically accept file transfer requests
- [x] Launcher no-label mode
- [x] Custom network speed refresh interval
- [x] Prevent file transfer from automatically turning off
- [x] Prevent automatic installation and overnight installation of system updates
- [x] Disable overnight install and reboot confirmation dialogs for system updates
- [ ] Game Assistant: Support displaying various metrics (CPU temp, GPU temp if available, battery temp, RAM speed, power wattage, battery level, etc.) // Maybe next version?

## Done Until 20260624 Beta
- [x] App details: show package name, target SDK, first install time, last update time, install source; support long-press to copy
- [x] Remove "Cannot use this folder" restriction
- [x] Disable FLAG_SECURE (allow screenshots anywhere)
- [x] Freeform desktop grid layout
- [x] Set "Actual Power" refresh interval
- [x] Memory usage display
- [x] Clean up global search
- [x] Screen turn-on / turn-off animations
- [x] Suppress virus detection dialogs
- [x] Suppress new system version available prompts
- [x] Notification center rounded rectangle icons
- [x] Notification center tile color customization
- [x] Notification center transparency customization
- [x] Quick settings tile no-label mode
- [x] Custom corner radius for volume and brightness sliders in control center
- [x] In higher Android versions of package installer, remove "This app has not passed review..." warning
- [x] Customize About Device panel information
- [x] Allow untrusted touch events
- [x] Detect non-ZUXOS / Hello UI devices
- [x] Show feature entries only for installed apps
- [x] Shortcut entry inside Settings
- [x] Display volume and brightness percentages

~~- [ ] Prevent Learning Assistant from restricting settings changes~~ The "Digital Wellbeing" feature in newer system versions no longer restricts these setting changes
~~- [ ] Force show Google entry at bottom of Settings~~ Automatically shown when Google services are enabled
~~- [ ] Directly open usage stats / permission settings for apps like Lenovo Share~~ Revisit if it becomes annoying again; Lenovo Share no longer requests as many permissions

Tips:
- Enable ultra-high refresh rate mode for the best animation experience
- Flash MiSans font for the best MIUIx theme experience
- Power consumption is positively correlated with the number of enabled features
- Do not blindly test extreme boundary values; though we test boundaries most of the time, most of the time...
- This app does not need notification permissions. If you prefer a persistent notification, well... that's fine too...
- setTag(10086);

System Services: Camera Assistant, Hello UI Performance Service

- Help chips: use MIUI-style popup, and refactor standalone inline help chips
- Verify compatibility under Material theme

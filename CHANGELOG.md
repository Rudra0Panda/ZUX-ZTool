# Changelog

## "Beta/260913" (c1605)
- Initial hot reload support
- Split DexKit method lookup logic to avoid Hook process calling native libraries directly
- Prevent screen brightness throttling during gaming
- Hide slow network speeds on status bar speed indicator
- Support clearing corrupted or tainted persistent properties
- Added and updated acknowledgements

## "Beta/260808" (c1358)
- Desktop live wallpaper (aspect ratio handling improved)
- Removed language restrictions on Chinese (PRC) ROMs
- Lock screen power display supports concatenated data: real voltage, current, power, battery temp (supports bash-style custom formatting)
- Use Hook instead of shell commands for Native AOD
- Launch associated apps in small floating window
- Hide recent apps in dock / taskbar
- Widen portrait control center
- Automatically decline user agreements for recommended app folders
- Suppress confirmation popups when launching associated apps
- Separated app update blue dot removal from no-label hook
- Custom charging animations
- Suppress desktop notification badges for system updates
- Bypass warning screen on older Lenovo Share versions
- Various subtle UI and performance optimizations

## "Beta/260710" (c1105)
- Disabled nighttime OTA update confirmation dialogs
- Prevent system updates from installing automatically
- Prevent Lenovo Share from automatically turning off
- Custom network speed refresh interval
- Desktop no-label mode
- Added hot reload entry
- Dynamic scope permission requests
- Fallback update check URL
- UI refinements; preliminary support for MIUIx theme branch
- DexKit anti-obfuscation support
- Upgraded to libxposed API 102 (min 101, target 102)
- Automatically accept file transfer requests
- Option to disable launcher dock bar
- Suppress startup warning in Lenovo Share
- Adjusted brightness and volume percentage colors
- Display estimated integer RAM and extended RAM in recent tasks overview
- Improved layout and styling of memory indicators

## "Beta/260624" (c791)
- Volume / brightness slider percentage indicators
- Quick navigation shortcut inside Settings
- Dynamically display feature entries based on installed packages
- Detect non-ZUXOS / Hello UI devices
- Redesigned First Run onboarding flow
- (Experimental) Allow untrusted touch events
- (Experimental) Prevent facial recognition timeout
- Customize information in About Device panel
- Removed native package installer warning about unverified apps
- Control center tiles no-label mode
- Control center transparency customization
- Control center tile color customization
- Control center tile corner radius customization
- Disable red dot notification badge for system updates
- Display memory information in recent tasks
- Cleaned up global search
- Enhanced SafeCenter scan blocking to prevent manual and background virus scans
- Screen turn-on / turn-off transition animations
- Extended app information fields (Target SDK, etc.) with long-press copy
- Jetpack Compose refactor with Material 3 (Expressive) and Miuix themes
- Fixed game audio processing restrictions on Android 16
- Fixed One Vision extension module installation and execution issues

## "Beta/260325" (c346)
- Export diagnostic logs
- Optimized lock screen real charging wattage with configurable refresh interval
- Themed monochrome app icons
- Custom desktop grid layout
- Restart button on main dashboard

## "Beta/251227" (c295)
- Update checking service
- EDL (9008) and MTK deep flash package download links
- Automatically enable mistouch prevention
- Native background app whitelist management
- Force allow screenshots (bypass FLAG_SECURE)
- Custom AI global input wake word
- New native notification icon hook point on Android 16
- Refactored UI layouts
- Forced split-screen support on Android 16
- Forced fixed-ratio floating windows on Android 16
- Forced freeform floating windows on Android 16
- Custom OTA request parameters

## "Demo/251127" (8)
- Enabled Lenovo OLED Always-On Display
- Backup and restore module configuration
- Optimized status bar network speed layout
- Dual-row network speed indicator
- Removed dock bar icon count limit
- Resolved MTK device crash scenarios
- Enabled Legion Y700 exclusive charging animation
- External battery percentage next to battery icon
- Predictive back gesture support
- Updated Settings hook points on Android 16
- Improved font importing logic

## "Demo/251114" (7)
- Cleaned up unnecessary notification icon count hook logic
- Native package installer support
- Allow disabling Dolby Audio on speakers (adapted from UnfuckZUI)
- Allow disabling charging animations (adapted from UnfuckZUI)
- Log management panel and collection service
- Native permission manager support (adapted from UnfuckZUI)
- Native status bar notification icons (adapted from UnfuckZUI)
- Prevent automatic guest user creation (adapted from UnfuckZUI)
- Maintain screen orientation across reboots (adapted from UnfuckZUI)
- Disable swipe-card app killing in ZUX launcher (adapted from UnfuckZUI)
- Allow apps to access package list by default (adapted from UnfuckZUI)
- Allow apps to autostart by default (adapted from UnfuckZUI)
- About page

## "Demo/251106" (6)
- Real charging wattage display
- Configurable status bar notification icon limit
- Refactored SystemUI settings categorization
- Custom control center date formatting
- Custom font import
- Adapted One Vision floating windows

## "Demo/251031" (5)
- AOD toggle
- Lock screen random quote / Hitokoto API
- Custom regex matching for API responses
- Charging wattage display

## "Demo/251027" (2-4)
- SystemUI hook panel
- Updated version name and code display
- Custom status bar clock format and live preview
- Lunar calendar support
- Seconds display toggle
- Custom status bar clock color, font size, and letter spacing
- Bold font toggle for status bar clock
- Resolved excessive logging in custom status bar module
- Forced floating windows
- Removed split-screen blacklist

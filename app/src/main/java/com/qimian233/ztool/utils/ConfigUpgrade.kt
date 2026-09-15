package com.qimian233.ztool.utils

import android.content.Context
import android.util.Log
import com.qimian233.ztool.EnhancedShellExecutor
import java.io.File

// Class for upgrading configuration, used to remove old 'module_enabled_' prefix and save new configuration.
// This utility class can only be enabled after removing PREFIX prefix in other parts.
object ConfigUpgrade {
    private const val TAG = "ConfigUpgrade"
    private var mPreferencesUtils: ModulePreferencesUtils? = null
    private var mCachedXSharedPrefsDir: String? = null

    private fun getPreferencesUtils(context: Context): ModulePreferencesUtils {
        return mPreferencesUtils ?: ModulePreferencesUtils(context).also { mPreferencesUtils = it }
    }

    // Executor method group
    private fun upgradeConfigFormat(context: Context) {
        try {
            val prefs = getPreferencesUtils(context)
            val allSettings = prefs.getAllSettings()
            Log.d(TAG, "Successfully fetched all settings:\n$allSettings")
            prefs.clearAllSettings()
            Log.d(TAG, "All config wiped, start upgrading config")
            // writeConfigToSharedPrefs method has built-in removal of 'module_enabled_' prefix, call directly here.
            prefs.writeConfigToSharedPrefs(allSettings)
            prefs.saveBooleanSetting("isConfigUpgraded", true)
            Log.d(TAG, "Config format upgraded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upgrade config format: ", e)
        }
    }

    private fun upgradeRemotePrefs(context: Context) {
        try {
            var oldDir = getXSharedPreferenceDirectory()
            if (oldDir == null || oldDir.trim().isEmpty()) {
                Log.i(TAG, "No old XSharedPreferences directory found, skipping remote prefs upgrade.")
                return
            }
            oldDir = oldDir.trim()
            if (oldDir.contains("\n")) {
                oldDir = oldDir.substring(0, oldDir.indexOf("\n")).trim()
            }
            Log.d(TAG, "Old XSharedPreferences directory: $oldDir")

            val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
            val destPath = sharedPrefsDir.absolutePath
            val appUid = android.os.Process.myUid()
            val executor = EnhancedShellExecutor.getInstance()

            val copyResult = executor.executeRootCommand(
                "cp " + oldDir + "/* " + destPath + "/ 2>/dev/null; " +
                        "chown -R " + appUid + " " + destPath + "/ 2>/dev/null; " +
                        "chmod -R 660 " + destPath + "/* 2>/dev/null; " +
                        "echo DONE",
                10
            )
            if (!copyResult.isSuccess || !copyResult.output.contains("DONE")) {
                Log.e(TAG, "Failed to copy old prefs files: " + copyResult.output)
                return
            }
            Log.d(TAG, "Copied old config files to shared_prefs")

            val prefs = getPreferencesUtils(context)
            val oldSettings = prefs.getAllSettingsFromLocal()
            if (oldSettings.isEmpty()) {
                Log.i(TAG, "No settings found in old config, skipping remote sync.")
                return
            }
            Log.d(TAG, "Read " + oldSettings.size + " settings from old config, syncing to RemotePreferences...")
            prefs.writeConfigToSharedPrefs(oldSettings)

            val newSettings = prefs.getAllSettings()
            if (newSettings.isNotEmpty()) {
                Log.d(TAG, "Sync verified: " + newSettings.size + " settings in RemotePreferences.")
                prefs.deleteLocalModulePreferences()
                executor.executeRootCommand("rm -rf " + oldDir, 5)
                Log.d(TAG, "Remote prefs upgrade completed successfully.")
            } else {
                Log.e(TAG, "Remote prefs sync verification failed, keeping old files.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upgrade remote prefs: ", e)
        }
    }

    // Methods corresponding to individual detection vectors
    private fun isConfigEmpty(prefs: ModulePreferencesUtils): Boolean {
        return prefs.getAllSettings().isEmpty()
    }

    private fun isUpgradedFlagExists(prefs: ModulePreferencesUtils): Boolean {
        return prefs.loadBooleanSetting("isConfigUpgraded", false)
    }

    private fun isConfigItemStartsWithOldPrefix(prefs: ModulePreferencesUtils): Boolean {
        for (key in prefs.getAllSettings().keys) {
            if (key.startsWith("module_enabled_")) {
                Log.d(TAG, "Old config format detected, need to upgrade config.")
                return true
            }
        }
        return false
    }

    private fun getXSharedPreferenceDirectory(): String? {
        if (mCachedXSharedPrefsDir != null) {
            return mCachedXSharedPrefsDir
        }
        val executor = EnhancedShellExecutor.getInstance()
        val result = executor.executeRootCommand(
            "find /data/misc -type d -name 'com.qimian233.ztool' 2>/dev/null | grep -E '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/prefs/com.qimian233.ztool$'", 5
        )
        if (!result.isSuccess) {
            Log.i(TAG, "Unable to find New XSharedPreferences directory, command failed!")
            return null
        }
        mCachedXSharedPrefsDir = result.output
        return mCachedXSharedPrefsDir
    }

    // Comprehensive detection gate corresponding to the two configuration upgrade detection points
    fun isConfigFormatUpgradeRequired(context: Context): Boolean {
        val prefs = getPreferencesUtils(context)
        // If config is empty, no upgrade is needed (user may have clicked "Clear Configuration" or this is a fresh install)
        // A config upgraded flag can be set along the way to avoid repeated upgrade checks.
        if (isConfigEmpty(prefs)) {
            Log.d(TAG, "Config is empty, maybe user performed reset or this is a fresh install, skipping upgrade.")
            prefs.saveBooleanSetting("isConfigUpgraded", true)
            return false
        }
        // First try reading the new config upgraded flag; if absent, configuration upgrade is required
        if (!isUpgradedFlagExists(prefs)) {
            Log.d(TAG, "Upgraded flag not detected, try alternative method to detect config version.")
            return isConfigItemStartsWithOldPrefix(prefs)
        }
        Log.d(TAG, "Config is already upgraded.")
        return false
    }

    private fun isRemotePrefsUpgradeRequired(context: Context): Boolean {
        val prefs = ModulePreferencesUtils(context)
        val isUpgradeNeeded = isConfigEmpty(prefs) && getXSharedPreferenceDirectory() != null
        if (isUpgradeNeeded) Log.w(TAG, "Please upgrade to RemotePreferences!") else Log.i(TAG, "No need to upgrade from XSharedPreferences.")
        return isUpgradeNeeded
    }

    // Configuration upgrade method called externally
    // Checks necessity of RemotePrefs and Prefs format upgrades sequentially; upgrades if needed
    // Return value determines whether frontend displays configuration upgrade dialog
    // Is New prefix also considered old config now... interesting
    fun configUpgrader(context: Context): Boolean {
        // Java version created a new instance on each call; reset instance state here to maintain consistent behavior
        mPreferencesUtils = null
        mCachedXSharedPrefsDir = null

        if (isRemotePrefsUpgradeRequired(context)) { // Upgrade to RemotePrefs does not require a dialog
            upgradeRemotePrefs(context)
        }

        return if (isConfigFormatUpgradeRequired(context)) {
            upgradeConfigFormat(context)
            true
        } else {
            false
        }
    }
}

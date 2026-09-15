package com.qimian233.ztool.utils

import android.util.Log
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.screens.features.FeatureDestination
import com.qimian233.ztool.data.keys.HowToRestart
import com.qimian233.ztool.data.keys.Scope
import com.qimian233.ztool.data.keys.ScopeKeys

/**
 * Scope utility class.
 * <p>
 * Centrally defines scope lists for each feature entry and provides unified scope restart logic,
 * shared by the frontend FeaturesRoute and various Repositories.
 * All scope package names and recommended restart methods originate from [ScopeKeys]; hardcoding package names here is prohibited.
 * </p>
 */
object ScopeUtils {

    private const val TAG = "ScopeUtils"

    /**
     * Returns all scopes (package name + recommended restart method) associated with a feature entry.
     * All of these package names must be in the LSPosed scope for the feature's Hooks to take full effect.
     */
    fun getScopes(destination: FeatureDestination): List<Scope> {
        return when (destination) {
            FeatureDestination.SettingsDetail -> listOf(
                ScopeKeys.SETTINGS,
                ScopeKeys.PERMISSION_CONTROLLER,
                ScopeKeys.ZUI_SAFE_CENTER
            )
            FeatureDestination.Ota -> listOf(
                ScopeKeys.OTA,
                ScopeKeys.TB_ENGINE,
                ScopeKeys.SETTINGS
            )
            FeatureDestination.SafeCenter -> listOf(
                ScopeKeys.ZUI_SAFE_CENTER,
                ScopeKeys.LENOVO_SAFE_CENTER,
                ScopeKeys.DOCUMENTS_UI
            )
            FeatureDestination.Framework -> listOf(
                ScopeKeys.ANDROID_SYSTEM,
                ScopeKeys.SYSTEM_SERVER
            )
            FeatureDestination.GameTool -> listOf(
                ScopeKeys.GAME_SERVICE,
            )
            FeatureDestination.PackageInstaller -> listOf(ScopeKeys.PACKAGE_INSTALLER)
            FeatureDestination.SystemUi -> listOf(
                ScopeKeys.SYSTEM_UI,
                ScopeKeys.WALLPAPER_SETTINGS
            )
            FeatureDestination.Launcher -> listOf(ScopeKeys.LAUNCHER)
            FeatureDestination.MobileDesktop -> listOf(
                ScopeKeys.MOBILE_DESKTOP,
            )
            FeatureDestination.TbEngine -> listOf(ScopeKeys.TB_ENGINE)
            FeatureDestination.ZuiPerformance -> listOf(ScopeKeys.ZUI_PERFORMANCE)
        }
    }

    /**
     * Returns all scope package names (including main package) associated with a feature entry.
     */
    fun getScopePackages(destination: FeatureDestination): List<String> =
        getScopes(destination).map { it.packageName }

    /**
     * Unified scope restart result.
     */
    sealed interface RestartResult {
        /** All succeeded */
        data object Success : RestartResult
        /** Partially succeeded, [failed] is the list of failed package names */
        data class PartialSuccess(val failed: List<String>) : RestartResult
        /** All failed */
        data class Failure(val message: String) : RestartResult
    }

    /**
     * Restart a set of scope processes, dispatching according to each Scope's registered [HowToRestart] strategy:
     * - [HowToRestart.AmStop]: Try [am force-stop] first, fallback to killall on failure;
     * - [HowToRestart.KillAll]: Direct killall (e.g. SystemUI cannot be force-stopped);
     * - [HowToRestart.Reboot]: System framework processes cannot be restarted per-package, skip and note reboot requirement.
     */
    fun restartScope(
        scopes: List<Scope>,
        shellExecutor: EnhancedShellExecutor = EnhancedShellExecutor.getInstance(),
        timeoutSeconds: Int = 5
    ): RestartResult {
        if (scopes.isEmpty()) return RestartResult.Success

        val failed = mutableListOf<String>()
        for (scope in scopes) {
            when (scope.howToRestart) {
                HowToRestart.AmStop -> {
                    val result = shellExecutor.executeRootCommand(
                        "am force-stop ${scope.packageName}",
                        timeoutSeconds
                    )
                    if (result.isSuccess) {
                        Log.d(TAG, "Force stop ${scope.packageName}: success")
                        continue
                    }
                    // Fallback to killall
                    Log.w(TAG, "am force-stop ${scope.packageName} failed, trying killall")
                    if (!killPackage(scope.packageName, shellExecutor, timeoutSeconds)) {
                        failed.add(scope.packageName)
                    }
                }
                HowToRestart.KillAll -> {
                    if (!killPackage(scope.packageName, shellExecutor, timeoutSeconds)) {
                        failed.add(scope.packageName)
                    }
                }
                HowToRestart.Reboot -> {
                    // System framework processes cannot be restarted via force-stop/killall; system reboot is required
                    Log.i(TAG, "${scope.packageName} requires system reboot, skipped")
                }
            }
        }

        return when {
            failed.isEmpty() -> RestartResult.Success
            failed.size == scopes.size -> RestartResult.Failure("All packages failed to restart")
            else -> RestartResult.PartialSuccess(failed)
        }
    }

    private fun killPackage(
        pkg: String,
        shellExecutor: EnhancedShellExecutor,
        timeoutSeconds: Int
    ): Boolean {
        val result = shellExecutor.executeRootCommand("killall $pkg", timeoutSeconds)
        if (result.isSuccess) {
            Log.d(TAG, "killall $pkg: success")
            return true
        }
        Log.e(TAG, "killall $pkg: failed — ${result.error}")
        return false
    }
}

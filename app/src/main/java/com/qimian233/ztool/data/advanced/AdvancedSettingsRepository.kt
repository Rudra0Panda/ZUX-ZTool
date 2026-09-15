package com.qimian233.ztool.data.advanced

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.qimian233.ztool.EnhancedShellExecutor
import com.qimian233.ztool.XposedServiceBridge
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import java.util.concurrent.atomic.AtomicInteger

/**
 * Advanced settings repository.
 * <p>
 * Encapsulates developer features such as module hot reload, handles thread switching and result aggregation.
 * </p>
 */
class AdvancedSettingsRepository(
    private val shellExecutor: EnhancedShellExecutor = EnhancedShellExecutor.getInstance()
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Query ----

    /** Get API version, returns 0 if not activated */
    fun getApiVersion(): Int = XposedServiceBridge.getApiVersion()

    /** Get running Hook targets list */
    fun getRunningTargets(): List<HookedTarget> = XposedServiceBridge.getRunningTargets()

    // ---- Hot Reload ----

    /**
     * Perform hot reload on all current running targets not in RELOADING state.
     *
     * @param onProgress Callback after each target finishes (main thread), arguments: (target, result)
     * @param onComplete Callback after all targets finish (main thread), arguments: (succeeded, failed, unsupported, died, details)
     */
    fun performHotReloadAll(
        onProgress: (target: HookedTarget, result: HotReloadResult) -> Unit,
        onComplete: (succeededCount: Int, failedCount: Int, unsupportedCount: Int, diedCount: Int, details: List<HotReloadDetail>) -> Unit
    ) {
        if (XposedServiceBridge.getApiVersion() < 102) {
            Log.e(TAG, "Low API version, unable to perform hot-reload")
            return
        }
        val targets = getRunningTargets()
        if (targets.isEmpty()) {
            onComplete(0, 0, 0, 0, emptyList())
            return
        }

        val eligible = targets.filter { it.state != HookedTarget.State.RELOADING }
        if (eligible.isEmpty()) {
            onComplete(0, 0, 0, 0, emptyList())
            return
        }

        val total = eligible.size
        val completed = AtomicInteger(0)
        val succeeded = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val unsupported = AtomicInteger(0)
        val died = AtomicInteger(0)
        val details = java.util.Collections.synchronizedList(mutableListOf<HotReloadDetail>())

        for (target in eligible) {
            val callback = object : XposedService.HotReloadCallback {
                override fun onHotReloadResult(target: HookedTarget, result: HotReloadResult) {
                    val status = result.status()
                    val message = result.message() ?: ""
                    val processName = target.processName
                    val detail = HotReloadDetail(processName, status.name, message)
                    details.add(detail)

                    when (status) {
                        HotReloadResult.Status.SUCCEEDED -> {
                            Log.d(TAG, "Hot reload succeeded: $processName")
                            succeeded.incrementAndGet()
                        }
                        HotReloadResult.Status.FAILED -> {
                            Log.w(TAG, "Hot reload failed: $processName - $message")
                            failed.incrementAndGet()
                        }
                        HotReloadResult.Status.UNSUPPORTED -> {
                            Log.w(TAG, "Hot reload not supported: $processName - $message")
                            unsupported.incrementAndGet()
                        }
                        HotReloadResult.Status.PROCESS_DIED -> {
                            Log.w(TAG, "Target process exited: $processName - $message")
                            died.incrementAndGet()
                        }
                        HotReloadResult.Status.IN_PROGRESS -> { return }
                    }

                    mainHandler.post {
                        onProgress(target, result)
                        if (completed.incrementAndGet() >= total) {
                            mainHandler.post {
                                onComplete(
                                    succeeded.get(),
                                    failed.get(),
                                    unsupported.get(),
                                    died.get(),
                                    details.toList()
                                )
                            }
                        }
                    }
                }
            }

            try {
                XposedServiceBridge.hotReloadModule(target, Bundle(), callback)
            } catch (e: Exception) {
                Log.e(TAG, "Exception initiating hot reload: ${target.processName}", e)
                failed.incrementAndGet()
                val detail = HotReloadDetail(target.processName, "FAILED", e.message ?: "unknown")
                details.add(detail)
                mainHandler.post {
                    onProgress(target, HotReloadResult(HotReloadResult.Status.FAILED, e.message))
                    if (completed.incrementAndGet() >= total) {
                        mainHandler.post {
                            onComplete(succeeded.get(), failed.get(), unsupported.get(), died.get(), details.toList())
                        }
                    }
                }
            }
        }
    }

    // ---- Persistent Value Reset ----

    /**
     * Reset all persistent values modified by this app's Hooks, executing each item and aggregating results.
     *
     * Currently supported:
     * - doze_always_on: Clears residue written by older versions via `settings put secure doze_always_on 1`.
     * - autorun: Clears whitelist bits written by Hook in the attr column of SafeCenter's AutoRunManager table.
     * - mistouch: Clears game center mistouch prevention persistence (Settings.Global.key_game_assistant_prevent_misoperation).
     *
     * @param onComplete Callback after all items are executed (caller thread), arguments: (succeeded, failed, unsupported, details)
     */
    fun resetPersistentValues(
        onComplete: (succeeded: Int, failed: Int, unsupported: Int, details: List<PersistentResetDetail>) -> Unit
    ) {
        val details = mutableListOf<PersistentResetDetail>()
        var succeeded = 0
        var failed = 0
        val unsupported = 0

        // 1. Native AOD switch (residue written by older shell versions)
        val aod = resetDozeAlwaysOn()
        if (aod.success) succeeded++ else failed++
        details += PersistentResetDetail(
            KEY_RESET_AOD, if (aod.success) "SUCCEEDED" else "FAILED", aod.message
        )

        // 2. App autorun status (SafeCenter AutoRunManager.attr whitelist bit)
        val autorun = resetAutorun()
        if (autorun.success) succeeded++ else failed++
        details += PersistentResetDetail(
            KEY_RESET_AUTORUN, if (autorun.success) "SUCCEEDED" else "FAILED", autorun.message
        )

        // 3. Game mistouch prevention status (Game center SettingsValueUtilKt -> Settings.Global)
        val mistouch = resetMistakeTouch()
        if (mistouch.success) succeeded++ else failed++
        details += PersistentResetDetail(
            KEY_RESET_MISTOUCH, if (mistouch.success) "SUCCEEDED" else "FAILED", mistouch.message
        )

        onComplete(succeeded, failed, unsupported, details)
    }

    /**
     * Clear residue values written by older versions via `settings put secure doze_always_on 1`.
     * Native AOD is now handled by Hook (ForceNativeAod); deleting residue restores system defaults.
     */
    private fun resetDozeAlwaysOn(): ResetOutcome {
        val current = shellExecutor.executeRootCommand("settings get secure doze_always_on")
        if (current.isSuccess) {
            val value = current.output.trim()
            if (value.isEmpty() || value.equals("null", ignoreCase = true)) {
                return ResetOutcome(true, "doze_always_on has no residual value, no reset needed")
            }
        }
        val result = shellExecutor.executeRootCommand("settings delete secure doze_always_on")
        return if (result.isSuccess) {
            ResetOutcome(true, "Cleared doze_always_on residual value")
        } else {
            ResetOutcome(false, "Failed to clear doze_always_on: ${result.error}")
        }
    }

    /**
     * Clear whitelist bits written by EnableAutorunByDefault Hook in SafeCenter's AutoRunManager table.
     * Database: com.zui.safecenter / com.lenovo.safecenter at databases/perf_leemcenter.db,
     * table AutoRunManager, column attr. Bitmasks:
     * - USER_WHITE_LIST_APP = 0x20000000
     * - RELATIVE_APP_WHITE_LIST = 0x40000000
     * Only clears whitelist bits without touching the state column (user manual autorun switches).
     */
    @SuppressLint("SdCardPath")
    private fun resetAutorun(): ResetOutcome {
        val whitelistMask = 0x20000000 or 0x40000000 // 1610612736
        val dbPaths = listOf(
            "/data/user/0/com.zui.safecenter/databases/perf_leemcenter.db",
            "/data/user/0/com.lenovo.safecenter/databases/perf_leemcenter.db"
        )
        var cleared = false
        for (dbPath in dbPaths) {
            // Skip if database does not exist (may be another package variant or no residue)
            val exists = shellExecutor.executeRootCommand("ls $dbPath")
            if (!exists.isSuccess) continue
            // Table may not be created (autorun management never opened); check table existence first to avoid false alarm of unavailable sqlite3
            val tableCheck = shellExecutor.executeRootCommand(
                "sqlite3 \"$dbPath\" \"SELECT name FROM sqlite_master WHERE type='table' AND name='AutoRunManager';\""
            )
            if (!tableCheck.isSuccess) {
                return ResetOutcome(false, "Failed to clear autorun whitelist: sqlite3 unavailable or database inaccessible")
            }
            if (tableCheck.output.trim().isEmpty()) continue
            // Pre-check residual count
            val count = shellExecutor.executeRootCommand(
                "sqlite3 \"$dbPath\" \"SELECT count(*) FROM AutoRunManager WHERE (attr & $whitelistMask) != 0;\""
            )
            if (!count.isSuccess) {
                return ResetOutcome(false, "Failed to clear autorun whitelist: ${count.error}")
            }
            if (count.output.trim() == "0") continue
            // Clear whitelist bits (retain stubborn / relative and other bits)
            val update = shellExecutor.executeRootCommand(
                "sqlite3 \"$dbPath\" \"UPDATE AutoRunManager SET attr = attr & ~$whitelistMask;\""
            )
            if (!update.isSuccess) {
                return ResetOutcome(false, "Failed to clear autorun whitelist: ${update.error}")
            }
            cleared = true
        }
        return if (cleared) {
            ResetOutcome(true, "Cleared autorun whitelist residue")
        } else {
            ResetOutcome(true, "No autorun whitelist residue found")
        }
    }

    /**
     * Clear game center mistouch prevention persistent value.
     * Writer is `com.zui.util.SettingsValueUtilKt.setPreventMisoperation`,
     * ultimately falling back to `Settings.Global.key_game_assistant_prevent_misoperation`.
     * AutoMistakeTouchHook only intercepts in-memory writes; deleting residue restores system defaults.
     */
    private fun resetMistakeTouch(): ResetOutcome {
        val current = shellExecutor.executeRootCommand(
            "settings get global key_game_assistant_prevent_misoperation"
        )
        if (current.isSuccess) {
            val value = current.output.trim()
            if (value.isEmpty() || value.equals("null", ignoreCase = true)) {
                return ResetOutcome(true, "Mistouch prevention has no residual value, no reset needed")
            }
        }
        val result = shellExecutor.executeRootCommand(
            "settings delete global key_game_assistant_prevent_misoperation"
        )
        return if (result.isSuccess) {
            ResetOutcome(true, "Cleared mistouch prevention persistent value")
        } else {
            ResetOutcome(false, "Failed to clear mistouch prevention: ${result.error}")
        }
    }

    private data class ResetOutcome(val success: Boolean, val message: String)

    companion object {
        private const val TAG = "AdvancedRepo"
        private const val KEY_RESET_AOD = "doze_always_on"
        private const val KEY_RESET_AUTORUN = "autorun"
        private const val KEY_RESET_MISTOUCH = "mistouch"
    }
}

/**
 * Result details of a single hot reload operation, for UI display.
 */
data class HotReloadDetail(
    val processName: String,
    val status: String,
    val message: String
)

/**
 * Result details of a single persistent value reset operation, for UI display.
 */
data class PersistentResetDetail(
    val key: String,
    val status: String,
    val message: String
)

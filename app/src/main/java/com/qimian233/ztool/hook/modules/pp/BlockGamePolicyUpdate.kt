package com.qimian233.ztool.hook.modules.pp

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Block ZUI Performance Service (com.zui.pp) cloud OTA application of game/performance policies.
 *
 * WhatsNewBroadcastReceiver receives the android.action.targetcomponent.performance.dataupdate
 * broadcast pushed by com.lenovo.tbengine (UDS engine), pulls gamepolicy.zip from ContentProvider,
 * extracts and overwrites PerformanceConfig / GamePolicyConfig by version number.
 * Swallowing onReceive prevents cloud-pushed policy packages from saving to disk, preserving
 * the system built-in policies under system/etc.
 */
class BlockGamePolicyUpdate : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.PP_BLOCK_GAME_POLICY_UPDATE.name

    override fun getTargetPackages(): Array<out String?>? = arrayOf(ScopeKeys.ZUI_PERFORMANCE.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        try {
            val receiverClass = classLoader.loadClass(
                "com.zui.performance.utils.WhatsNewBroadcastReceiver"
            )
            val onReceive = findMethod(
                receiverClass,
                "onReceive",
                android.content.Context::class.java,
                android.content.Intent::class.java
            )
            hookWithId(onReceive, "pp_game_policy_broadcast") { _ ->
                logger.debug("Blocked WhatsNewBroadcastReceiver.onReceive (game policy OTA).")
                // no-op: do not receive cloud policy packages
            }
            logger.info("Hooked WhatsNewBroadcastReceiver.onReceive")
        } catch (t: Throwable) {
            logger.error("Failed to hook WhatsNewBroadcastReceiver.onReceive", t)
        }
    }
}

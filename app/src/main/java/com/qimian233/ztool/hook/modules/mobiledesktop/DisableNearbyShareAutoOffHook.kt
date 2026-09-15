package com.qimian233.ztool.hook.modules.mobiledesktop

import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface

/**
 * Test Hook — Disables the 10-minute auto-off countdown for Smart Connect nearby sharing.
 *
 * Mechanism: FileUnionSwitchManager (obfuscated as `com.motorola.motoaccount.sdk.se.c` in current version)
 * sends a delayed message (what=1, delay=600000ms=10min) in startCountDown after nearby share is enabled;
 * handler receives the message and calls `MotoDiscoveryManager.B(false)` to turn it off automatically.
 * This Hook replaces startCountDown with a no-op, preventing countdown startup.
 *
 * The target method is obfuscated, located by DexIndexer via the log string "startCountDown()",
 * and falls back to hardcoded class/method names in current version.
 */
class DisableNearbyShareAutoOffHook : AppHookModule() {

    companion object {
        private val TARGET_PACKAGE = ScopeKeys.MOBILE_DESKTOP.packageName
        // Fallback: Hardcoded class and method name for current version (FileUnionSwitchManager)
        private const val FALLBACK_CLASS = "com.motorola.motoaccount.sdk.se.c"
        private const val FALLBACK_METHOD = "b"
    }

    override fun getModuleName(): String = "disable_nearby_share_countdown"

    override fun getTargetPackages(): Array<String> = arrayOf(TARGET_PACKAGE)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        // ── Read obfuscated class/method names from offline index ─────────────────────────────
        val module = DexIndexStore.lookup(xposed, ScopeKeys.MOBILE_DESKTOP.packageName)
            ?.getAsJsonObject(DexIndexConstants.ModuleKeys.DISABLE_NEARBY_SHARE_COUNTDOWN)
        val targetClassName = module?.get(DexIndexConstants.Keys.TARGET_CLASS)
            ?.takeIf { !it.isJsonNull }?.asString ?: FALLBACK_CLASS
        val targetMethodName = module?.get(DexIndexConstants.Keys.TARGET_METHOD)
            ?.takeIf { !it.isJsonNull }?.asString ?: FALLBACK_METHOD

        // ── Install Hook ─────────────────────────────────────────────
        try {
            val targetClass = classLoader.loadClass(targetClassName)

            val targetMethod = targetClass.declaredMethods.firstOrNull { method ->
                method.name == targetMethodName
                        && method.parameterTypes.isEmpty()
                        && method.returnType == Void.TYPE
            }

            if (targetMethod == null) {
                logger.error(
                    "Could not find startCountDown method ($targetMethodName) in $targetClassName",
                    null
                )
                return
            }

            hookWithId(targetMethod, "target") { 
                logger.debug("startCountDown() intercepted — auto-off timer prevented.")
                null
            }
            logger.info("Installed hook for FileUnionSwitchManager.$targetMethodName()")
        } catch (e: ClassNotFoundException) {
            logger.error("$targetClassName (FileUnionSwitchManager) not found", e)
        } catch (t: Throwable) {
            logger.error("Failed to hook FileUnionSwitchManager.startCountDown()", t)
        }
    }
}

package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import android.text.TextUtils
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * AI IME expansion feature Hook module.
 * Function: Expands AI trigger symbols and forcibly enables LGSI AI feature flags.
 * Scope: Global (dynamically detects if target class is present).
 */
@SuppressLint("PrivateApi")
class AiInputExpand : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.AI_INPUT_EXPAND.name

    override fun getTargetPackages(): Array<String?>? = null

    /**
     * Override to support global hooking,
     * as RemoteInputConnectionImpl is loaded in various application processes.
     */
    override fun supportsPackage(packageName: String?): Boolean = true

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        // 1. Modify RemoteInputConnectionImpl trigger symbols
        runCatching { hookRemoteInputConnection(classLoader) }
        // 2. Forcibly enable LgsiFeatures features
        runCatching { hookLgsiFeatures(classLoader) }
    }

    private fun hookRemoteInputConnection(classLoader: ClassLoader) {
        val className = "android.view.inputmethod.RemoteInputConnectionImpl"

        // Check if class exists; return early if not to avoid invalid hook attempts
        val targetClass: Class<*>?
        try {
            targetClass = classLoader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            return
        }

        // Define new trigger symbol array using new symbols
        val newSignArray = this.prefStringArray

        // Modify static constant array AI_COMMAND_SIGN_ARRAYS
        findField(targetClass, "AI_COMMAND_SIGN_ARRAYS").set(null, newSignArray)

        // Modify default AI_COMMAND_SIGN
        findField(targetClass, "AI_COMMAND_SIGN").set(null, "&&")

        logger.info("Successfully expanded AI input signs [&&] for package")
    }

    private fun hookLgsiFeatures(classLoader: ClassLoader) {
        val className = "com.lgsi.config.LgsiFeatures"

        val featureClass: Class<*>
        try {
            featureClass = classLoader.loadClass(className)
        } catch (_: ClassNotFoundException) {
            return
        }

        // Forcibly return true for enabled method
        try {
            val method = featureClass.getDeclaredMethod("enabled", Int::class.javaPrimitiveType)
            hookWithId(
                method,
                "lgsi_features_enabled"
            ) { true }
            logger.info("Successfully forced LGSI Features check to TRUE")
        } catch (_: NoSuchMethodException) {
            // Method does not exist, ignore
        }
    }

    private val prefStringArray: Array<String?>
        /**
         * Read comma-separated string array from preferences.
         */
        get() {
            val value: String? = try {
                remotePreferences.getString(PreferenceKeys.AI_INPUT_EXPAND_SIGNS.name, "")
            } catch (_: Throwable) {
                ""
            }
            if (TextUtils.isEmpty(value)) return arrayOfNulls(0)
            return value!!.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        }
}

package com.qimian233.ztool.hook.modules.gametool

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Game service device model disguise Hook module.
 * Disguises device model as TB322FC to bypass game service device checks.
 */
class DeviceModelDisguiseHook : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.DISGUISE_TB322FC.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.GAME_SERVICE.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        hookDeviceUtils(classLoader)
    }

    private fun hookDeviceUtils(classLoader: ClassLoader) {
        try {
            // Find DeviceUtils class
            val deviceUtilsClass = classLoader.loadClass("com.zui.util.DeviceUtils")

            // Hook getBuildModel method to forcibly return target model
            val getBuildModelMethod = deviceUtilsClass.getDeclaredMethod("getBuildModel")
            hookWithId(
                getBuildModelMethod,
                "get_build_model"
            ) { "TB322FC" }

            logger.info("Successfully hooked DeviceUtils.getBuildModel for com.zui.game.service")
        } catch (e: Exception) {
            logger.error("Failed to hook DeviceUtils.getBuildModel", e)
        }
    }
}

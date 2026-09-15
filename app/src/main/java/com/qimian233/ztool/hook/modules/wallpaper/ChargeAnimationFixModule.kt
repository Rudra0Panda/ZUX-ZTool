package com.qimian233.ztool.hook.modules.wallpaper

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Charge animation fix module.
 * Fixes charge animation display issues in ZUI system wallpaper settings, forcibly showing all charge animation options.
 * Ensures the system uses the resource array containing all charge animations by modifying key methods in Utilities.
 */
class ChargeAnimationFixModule : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.CHARGE_ANIMATION_FIX.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.WALLPAPER_SETTINGS.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        try {
            hookChargeAnimationUtils(classLoader)
        } catch (t: Throwable) {
            logger.error("Failed to hook charge animation utilities", t)
        }
    }

    /**
     * Hook key methods in Utilities class to fix charge animation display.
     */
    private fun hookChargeAnimationUtils(classLoader: ClassLoader) {
        try {
            val utilsClass = classLoader.loadClass(UTILS_CLASS)

            // Modify Utilities.isLegiony() to return true
            // Original logic: (!Utilities.isLegiony() || Utilities.isOversea) ? "chargeStyle_row" : "chargeStyle"
            // Forcing isLegiony to return true ensures the "chargeStyle" array is used
            val isLegionyMethod = utilsClass.getDeclaredMethod("isLegiony")
            hookWithId(
                isLegionyMethod,
                "is_legiony"
            ) { true }

            // Modify Utilities.isOversea() to return false
            val isOverseaMethod = utilsClass.getDeclaredMethod("isOversea")
            hookWithId(
                isOverseaMethod,
                "is_oversea"
            ) { false }

            // Fix charge animation display issues on tablet devices
            val isPadMethod = utilsClass.getDeclaredMethod("isPad")
            hookWithId(isPadMethod, "is_pad") { false }

            logger.info("Successfully enabled all charge animations")
            logger.debug("Now showing: default, particle, turbo, triangle, girl")
        } catch (t: Throwable) {
            logger.error("Failed to hook Utilities class", t)
        }
    }

    companion object {
        private const val UTILS_CLASS = "com.zui.wallpapersetting.util.Utilities"
    }
}

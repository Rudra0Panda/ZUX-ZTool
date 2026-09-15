package com.qimian233.ztool.hook.modules.setting

import android.annotation.SuppressLint
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Split Screen mandatory feature Hook module (Settings side).
 *
 * Shares the same preference key [Split_Screen_mandatory] with systemframework.SplitScreenMandatory,
 * ensuring simultaneous enable/disable on both sides.
 */
@SuppressLint("PrivateApi")
class SplitScreenMandatory : AppHookModule() {
    override fun getModuleName(): String = "Split_Screen_mandatory"

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SETTINGS.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        // Settings-side Hook logic (currently no extra hooks, reserved for future extensions)
    }
}

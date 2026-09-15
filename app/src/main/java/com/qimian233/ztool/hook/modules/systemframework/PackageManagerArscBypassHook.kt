package com.qimian233.ztool.hook.modules.systemframework

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Bypass resources.arsc storage restrictions:
 * AssetManager.containsAllocatedTable constantly returns false,
 * allowing targetSdk R+ APKs whose resources.arsc does not meet uncompressed alignment requirements to install and load.
 */
class PackageManagerArscBypassHook : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.PKG_MGR_BYPASS_ARSC_RESTRICTION.name

    override fun getTargetPackages(): Array<out String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    @Throws(Throwable::class)
    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader
        try {
            val assetManagerClass = classLoader.loadClass("android.content.res.AssetManager")
            val containsAllocatedTable = assetManagerClass
                .getDeclaredMethod("containsAllocatedTable")
            hookWithId(containsAllocatedTable, "pkgmgr_arsc_contains_allocated_table") { _ ->
                false
            }
            logger.info("Hooked AssetManager.containsAllocatedTable")
        } catch (e: Throwable) {
            logger.error("Failed hooking AssetManager.containsAllocatedTable", e)
        }
    }
}

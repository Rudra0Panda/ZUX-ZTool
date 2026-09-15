package com.qimian233.ztool.hook.modules.setting

import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Test Hook: Intercepts LenovoUtils region detection methods, taking effect only in LocaleListEditor call scenarios.
 *
 * When called from com.android.settings.localepicker.LocaleListEditor:
 * - com.lenovo.common.utils.LenovoUtils.isRowVersion returns true
 * - com.lenovo.common.utils.LenovoUtils.isPrcVersion returns false
 *
 * Other call scenarios follow original logic to avoid side effects on other Settings pages.
 * Accurately targets via stack trace inspection; language settings page is rarely opened, so overhead is negligible.
 *
 * Automatically enabled via module name without frontend toggle needed.
 */
class LocaleListEditorHook : AppHookModule() {

    companion object {
        private const val TARGET_CLASS = "com.android.settings.localepicker.LocaleListEditor"
    }

    override fun getModuleName(): String = PreferenceKeys.ALLOW_ADD_LANGUAGE.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SETTINGS.packageName)

    private fun isFromLocaleListEditor(): Boolean =
        Throwable().stackTrace.any { it.className == TARGET_CLASS }

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val cl = param.defaultClassLoader
        try {
            val lenovoUtils = cl.loadClass("com.lenovo.common.utils.LenovoUtils")

            val isRowMethod = lenovoUtils.getDeclaredMethod("isRowVersion")
            hookWithId(isRowMethod, "locale_row_version") { chain ->
                if (isFromLocaleListEditor()) true else chain.proceed()
            }

            val isPrcMethod = lenovoUtils.getDeclaredMethod("isPrcVersion")
            hookWithId(isPrcMethod, "locale_prc_version") { chain ->
                if (isFromLocaleListEditor()) false else chain.proceed()
            }

            logger.info("LocaleListEditorHook installed: isRowVersion→true, isPrcVersion→false (LocaleListEditor only)")
        } catch (t: Throwable) {
            logger.error("LocaleListEditorHook failed", t)
        }
    }
}

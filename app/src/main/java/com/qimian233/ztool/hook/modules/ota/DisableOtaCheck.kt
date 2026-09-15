package com.qimian233.ztool.hook.modules.ota

import android.view.Menu
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Disable Lenovo OTA check Hook module.
 * Function: Forcibly displays local install menu item, bypassing click counter check logic.
 */
class DisableOtaCheck : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.DISABLE_OTA_CHECK.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.OTA.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        logger.info("Starting hook for com.lenovo.ota - enabling local install service")

        try {
            hookOnCreateOptionsMenu(classLoader)
            hookOnPrepareOptionsMenu(classLoader)
            hookClickCountCallBack(classLoader)

            logger.info("All OTA check bypass hooks initialized")
        } catch (e: Exception) {
            logger.error("Error initializing OTA check bypass module", e)
        }
    }

    /**
     * Hook onCreateOptionsMenu method to ensure menu items are not hidden by default.
     */
    private fun hookOnCreateOptionsMenu(classLoader: ClassLoader) {
        try {
            val mainActivityClass = classLoader.loadClass(MAIN_ACTIVITY)
            val onCreateOptionsMenu =
                mainActivityClass.getDeclaredMethod("onCreateOptionsMenu", Menu::class.java)
            val rClass = classLoader.loadClass($$"com.lenovo.ota.R$id")

            hookWithId(
                onCreateOptionsMenu,
                "on_create_options_menu"
            ) { chain ->
                val result = chain.proceed()
                try {
                    val menu = chain.args[0] as Menu
                    // Find local install menu item and set to visible
                    val menuLocalInstallField = findField(rClass, "memu_localInstall")
                    val menuLocalInstallId = menuLocalInstallField.getInt(null)

                    val localInstallItem = menu.findItem(menuLocalInstallId)
                    if (localInstallItem != null) {
                        localInstallItem.isVisible = true
                        logger.debug("Enabled local install menu in onCreateOptionsMenu")
                    }
                } catch (e: Exception) {
                    logger.error("onCreateOptionsMenu hook execution error", e)
                }
                result
            }
        } catch (e: Exception) {
            logger.error("Failed to set onCreateOptionsMenu hook", e)
        }
    }

    /**
     * Hook onPrepareOptionsMenu method to bypass condition checks.
     */
    private fun hookOnPrepareOptionsMenu(classLoader: ClassLoader) {
        try {
            val mainActivityClass = classLoader.loadClass(MAIN_ACTIVITY)
            val onPrepareOptionsMenu =
                mainActivityClass.getDeclaredMethod("onPrepareOptionsMenu", Menu::class.java)
            val rClass = classLoader.loadClass($$"com.lenovo.ota.R$id")

            hookWithId(
                onPrepareOptionsMenu,
                "on_prepare_options_menu"
            ) { chain ->
                val result = chain.proceed()
                try {
                    val menu = chain.args[0] as Menu
                    // Get menu item ID via reflection
                    val menuLocalInstallField = findField(rClass, "memu_localInstall")
                    val menuLocalInstallId = menuLocalInstallField.getInt(null)

                    val localInstallItem = menu.findItem(menuLocalInstallId)
                    if (localInstallItem != null) {
                        // Forcibly set to visible, bypassing original mCount >= 6 check
                        localInstallItem.isVisible = true
                        logger.debug("Forced local install menu visible in onPrepareOptionsMenu")
                    }

                    // Also set counter to 6 to ensure other related logic works properly
                    val mCountField = mainActivityClass.getDeclaredField("mCount")
                    mCountField.isAccessible = true
                    mCountField.setInt(chain.thisObject, 6)
                } catch (e: Exception) {
                    logger.error("onPrepareOptionsMenu hook execution error", e)
                }
                result
            }
        } catch (e: Exception) {
            logger.error("Failed to set onPrepareOptionsMenu hook", e)
        }
    }

    /**
     * Hook clickCountCallBack method to ensure counter always satisfies condition.
     */
    private fun hookClickCountCallBack(classLoader: ClassLoader) {
        try {
            val mainActivityClass = classLoader.loadClass(MAIN_ACTIVITY)
            val clickCountCallBack = mainActivityClass.getDeclaredMethod("clickCountCallBack")
            val mCountField = findField(mainActivityClass, "mCount")

            hookWithId(
                clickCountCallBack,
                "click_count_call_back"
            ) { chain ->
                // Directly set counter to 6 before calling
                mCountField.setInt(chain.thisObject, 6)
                logger.debug("Forced counter to 6 before clickCountCallBack")
                chain.proceed()
            }
        } catch (e: Exception) {
            logger.error("Failed to set clickCountCallBack hook", e)
        }
    }

    companion object {
        private const val MAIN_ACTIVITY = "com.lenovo.row.ota.core.d.ui.MainActivity"
    }
}

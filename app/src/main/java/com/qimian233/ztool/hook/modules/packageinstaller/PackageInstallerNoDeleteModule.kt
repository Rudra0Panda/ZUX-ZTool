package com.qimian233.ztool.hook.modules.packageinstaller

import android.annotation.SuppressLint
import android.app.Activity
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Disable post-install APK deletion prompt module.
 * Intercepts system PackageInstaller (com.android.packageinstaller) to modify the default "Delete package after install" behavior,
 * unchecking the deletion option by default on first install to prevent accidental deletion of installation files.
 */
class PackageInstallerNoDeleteModule : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.PACKAGE_INSTALLER_DISABLE_DELETE.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.PACKAGE_INSTALLER.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        hookPackageInstaller(classLoader)
    }

    /**
     * Hook system package installer core logic.
     * Intercepts InstallSuccessExtra.initView to modify default APK deletion behavior.
     * In newer PackageInstaller versions, CheckBox is a local variable in initView(),
     * and OnCheckedChangeListener directly overwrites mDeleteApk, so we need to:
     * 1. Force mDeleteApk = false
     * 2. Locate CheckBox via findViewById and replace its listener to prevent manual checks from overwriting boolean
     * 3. Fallback: Hook clearCachedApkIfNeededAndFinish to re-ensure mDeleteApk = false
     */
    private fun hookPackageInstaller(classLoader: ClassLoader) {
        try {
            logger.info("Starting hook for package installer")

            @SuppressLint("PrivateApi") val installSuccessExtraClass = classLoader.loadClass(
                "com.android.packageinstaller.InstallSuccessExtra"
            )

            // --- Hook 1: initView() — Initial setup + UI fix ---
            val initView = installSuccessExtraClass.getDeclaredMethod("initView")
            val mDeleteApkField = installSuccessExtraClass.getDeclaredField("mDeleteApk")
            mDeleteApkField.isAccessible = true

            hookWithId(initView, "init_view") { chain ->
                val result = chain.proceed()
                if (!isEnabled()) {
                    return@hookWithId result
                }

                try {
                    val instance = chain.thisObject
                    logger.debug("Inside initView method for package installer")

                    // Force mDeleteApk = false (regardless of configuration changes)
                    mDeleteApkField.setBoolean(instance, false)

                    // Locate CheckBox via findViewById (in newer versions it is a local variable, cannot reflect field)
                    try {
                        val activity = instance as Activity
                        @SuppressLint("DiscouragedApi") val checkBoxId =
                            activity.resources.getIdentifier(
                                "del_check_box", "id", ScopeKeys.PACKAGE_INSTALLER.packageName
                            )
                        if (checkBoxId != 0) {
                            val view = activity.findViewById<View?>(checkBoxId)
                            if (view is CheckBox) {
                                // Update UI to unchecked state
                                view.isChecked = false
                                // Replace listener: prevent user manual check from overwriting mDeleteApk
                                view.setOnCheckedChangeListener { buttonView: CompoundButton?, isChecked: Boolean ->
                                    try {
                                        mDeleteApkField.setBoolean(instance, false)
                                    } catch (_: Throwable) {
                                    }
                                    // Always display unchecked
                                    if (isChecked) {
                                        buttonView!!.isChecked = false
                                    }
                                }
                                logger.debug("Successfully updated UI checkbox and replaced listener")
                            }
                        } else {
                            logger.warn("CheckBox resource ID 'del_check_box' not found, may be new version")
                        }
                    } catch (uiError: Throwable) {
                        logger.error("Failed to update checkbox UI", uiError)
                    }
                } catch (t: Throwable) {
                    logger.error("Error in afterHookedMethod for initView", t)
                }
                result
            }

            logger.info("Successfully hooked InstallSuccessExtra.initView()")

            // --- Hook 2: clearCachedApkIfNeededAndFinish() — Fallback protection ---
            // Called after deletion thread finishes, or in onStop.
            // Re-ensures mDeleteApk = false as multi-layer defense.
            try {
                val clearMethod = installSuccessExtraClass.getDeclaredMethod(
                    "clearCachedApkIfNeededAndFinish"
                )
                hookWithId(clearMethod, "clear") { chain ->
                    if (isEnabled()) {
                        try {
                            mDeleteApkField.setBoolean(chain.thisObject, false)
                        } catch (_: Throwable) {
                        }
                    }
                    chain.proceed()
                }
                logger.info("Successfully hooked InstallSuccessExtra.clearCachedApkIfNeededAndFinish()")
            } catch (t: Throwable) {
                logger.error("Failed to hook clearCachedApkIfNeededAndFinish", t)
            }
        } catch (t: Throwable) {
            logger.error("Failed to initialize package installer hook", t)
        }
    }
}

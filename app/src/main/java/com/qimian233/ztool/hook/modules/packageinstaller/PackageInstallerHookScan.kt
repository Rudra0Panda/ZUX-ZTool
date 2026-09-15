package com.qimian233.ztool.hook.modules.packageinstaller

import android.annotation.SuppressLint
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Disable APK scanning Hook module.
 * Intercepts PackageInstaller's scanning pipeline and returns safe results directly.
 */
@SuppressLint("PrivateApi")
class PackageInstallerHookScan : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.DISABLE_SCAN_APK.name

    override fun getTargetPackages(): Array<String> = arrayOf(PACKAGE_INSTALLER)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        hookPackageInstaller(classLoader)
    }

    private fun hookPackageInstaller(classLoader: ClassLoader) {
        logger.info("Starting PackageInstaller scan hooks...")

        // Method 1: Skip scan directly and return safe result immediately
        hookScanMethods(classLoader)

        // Method 2: Intercept scan result handling
        hookResultMethods(classLoader)

        // Method 3: Skip scan service binding
        hookServiceMethods(classLoader)

        logger.info("PackageInstaller scan hook initialization complete")
    }

    private fun hookScanMethods(classLoader: ClassLoader) {
        try {
            // Intercept startScanApps method to return directly without scanning
            val activityExtraClass = classLoader.loadClass(
                "com.android.packageinstaller.PackageInstallerActivityExtra"
            )
            val startScanApps = activityExtraClass.getDeclaredMethod("startScanApps")
            hookWithId(startScanApps, "start_scan_apps") { chain ->
                logger.debug("Intercepted startScanApps, skipping scan flow")
                // Send scan complete message immediately
                val activity = chain.thisObject
                val mHanderField = activity.javaClass.getDeclaredField("mHander")
                mHanderField.isAccessible = true
                val handler = mHanderField.get(activity)
                if (handler != null) {
                    handler.javaClass.getDeclaredMethod(
                        "sendEmptyMessage",
                        Int::class.javaPrimitiveType
                    )
                        .invoke(handler, 2) // SCAN_APP_OK = 2
                    logger.debug("Sent SCAN_APP_OK message")
                }

                null // Return directly without executing scan
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook startScanApps", t)
        }
    }

    private fun hookResultMethods(classLoader: ClassLoader) {
        try {
            // Intercept showResultIfFinish method to force showing installation UI
            val activityExtraClass = classLoader.loadClass(
                "com.android.packageinstaller.PackageInstallerActivityExtra"
            )
            val showResultIfFinish = activityExtraClass.getDeclaredMethod("showResultIfFinish")
            hookWithId(
                showResultIfFinish,
                "show_result_if_finish"
            ) { chain ->
                logger.debug("Intercepted showResultIfFinish")
                val activity = chain.thisObject

                // Forcibly set scan result to safe
                val mScanAppResultField = activity.javaClass.getDeclaredField("mScanAppResult")
                mScanAppResultField.isAccessible = true
                mScanAppResultField.setInt(activity, 2) // SCAN_APP_OK

                val mCheckSafeInstallResultField =
                    activity.javaClass.getDeclaredField("mCheckSafeInstallResult")
                mCheckSafeInstallResultField.isAccessible = true
                mCheckSafeInstallResultField.setInt(activity, 1)

                val isScanBeginField = activity.javaClass.getDeclaredField("isScanBegin")
                isScanBeginField.isAccessible = true
                isScanBeginField.setBoolean(activity, true)

                logger.debug("Forcibly set scan result to safe state")
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook showResultIfFinish", t)
        }
    }

    private fun hookServiceMethods(classLoader: ClassLoader) {
        try {
            // Intercept bindSafeService method to skip service binding
            val activityExtraClass = classLoader.loadClass(
                "com.android.packageinstaller.PackageInstallerActivityExtra"
            )
            val bindSafeService = activityExtraClass.getDeclaredMethod("bindSafeService")
            hookWithId(
                bindSafeService,
                "bind_safe_service"
            ) { chain ->
                logger.debug("Intercepted bindSafeService, skipping service binding")
                val activity = chain.thisObject

                // Set bound state to avoid retries
                val isBindField = activity.javaClass.getDeclaredField("isBind")
                isBindField.isAccessible = true
                isBindField.setBoolean(activity, true)

                val isConnectField = activity.javaClass.getDeclaredField("isConnect")
                isConnectField.isAccessible = true
                isConnectField.setBoolean(activity, true)

                // Send scan start message immediately
                val mHanderField = activity.javaClass.getDeclaredField("mHander")
                mHanderField.isAccessible = true
                val handler = mHanderField.get(activity)
                if (handler != null) {
                    handler.javaClass.getDeclaredMethod(
                        "sendEmptyMessage",
                        Int::class.javaPrimitiveType
                    )
                        .invoke(handler, 1) // SCAN_APP_BEGIN
                    logger.debug("Sent SCAN_APP_BEGIN message")
                }

                null // Skip actual binding
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook bindSafeService", t)
        }
    }

    companion object {
        private val PACKAGE_INSTALLER = ScopeKeys.PACKAGE_INSTALLER.packageName
    }
}

package com.qimian233.ztool.hook.modules.launcher.misc

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayList

/**
 * ZUI Launcher background management optimization hook module.
 * Prevents killing app background services when swiping away recent task cards.
 * Smart adaptation for Android 16+ and Android 15- versions.
 * Supports whitelist mechanism to protect only designated apps.
 */
class DisableForceStop : AppHookModule() {

    // Whitelisted app package name set
    private var whiteList: Array<String> = arrayOf()

    override fun getModuleName(): String = PreferenceKeys.DISABLE_FORCE_STOP.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        val packageName = param.packageName
        // Get current Android SDK version
        val sdkVersion = getSDKVersion()
        whiteList = getWhiteListPackages()
        logger.trace("Current Android SDK: $sdkVersion, target package name: $packageName")
        logger.trace("White list enabled, app in whitelist: ${whiteList.size}")

        // Select Hook strategy based on Android version
        if (sdkVersion >= 36) { // Includes Android 16
            hookForAndroid16Plus(classLoader, packageName)
        } else {
            hookForAndroid15Minus(classLoader, packageName)
        }
    }

    /**
     * Hook strategy for Android 16+ versions
     * Target new architecture after ZUI Launcher desktop major redesign
     */
    private fun hookForAndroid16Plus(classLoader: ClassLoader, packageName: String) {
        try {
            if (ScopeKeys.LAUNCHER.packageName == packageName) {
                hookZuiLauncherAndroid16(classLoader)
            } else if ("com.android.launcher3" == packageName) {
                hookBaseLauncherAndroid16()
            }
            logger.info("Android 16+ Hook applied, whitelist protection enabled")
        } catch (t: Throwable) {
            logger.error("Android 16+ Hook failed!", t)
        }
    }

    // Check if whitelist protection is enabled
    private fun isWhiteListEnabled(): Boolean {
        return try {
            remotePreferences.getBoolean(PreferenceKeys.FORCE_STOP_WHITE_LIST_ENABLE.name, false)
        } catch (_: Throwable) {
            false
        }
    }

    // Get app package names in whitelist
    private fun getWhiteListPackages(): Array<String> {
        val value = try {
            remotePreferences.getString(PreferenceKeys.FORCE_STOP_WHITE_LIST.name, "")
        } catch (_: Throwable) {
            ""
        }
        if (value.isNullOrEmpty()) return arrayOf()
        return value.split(",").toTypedArray()
    }

    // Check if designated package name is in whitelist
    private fun isProtectedPackage(packageName: String): Boolean {
        if (!isWhiteListEnabled()) return true // Whitelist not enabled, protect all apps
        for (pkg in whiteList) {
            if (pkg == packageName) {
                return true
            }
        }
        return false
    }

    /**
     * Hook strategy for Android 15 and lower versions
     * Target traditional Launcher architecture
     */
    private fun hookForAndroid15Minus(classLoader: ClassLoader, packageName: String) {
        try {
            if (ScopeKeys.LAUNCHER.packageName == packageName || "com.android.launcher3" == packageName) {
                hookLegacyLauncher(classLoader)
            }
            logger.info("Android 15- Hook applied, whitelist protection enabled")
        } catch (t: Throwable) {
            logger.error("Android 15- Hook failed!", t)
        }
    }

    /**
     * Android 16+ ZUI Launcher dedicated Hook (with whitelist mechanism)
     */
    private fun hookZuiLauncherAndroid16(classLoader: ClassLoader) {
        try {
            val overviewUtilitiesClass = classLoader.loadClass("com.zui.launcher.util.OverviewUtilities")

            // Hook removeAppProcess method - main process killing entry point
            val removeAppProcessMethod: Method = overviewUtilitiesClass.getDeclaredMethod(
                "removeAppProcess", Context::class.java, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType
            )
            hookWithId(removeAppProcessMethod, "remove_app_process_1") { chain ->
                val pkgName = chain.args[2] as String // Note: parameter index corrected
                val uid = chain.args[3] as Int

                // Check if in whitelist
                if (isProtectedPackage(pkgName)) {
                    // In whitelist, prevent kill operation
                    logger.trace("Android 16: Avoid killing app in whitelist: $pkgName (UID: $uid)")
                    return@hookWithId null
                }

                // Not in whitelist, allow original method execution
                logger.trace("Android 16: Allow killing app: $pkgName")
                chain.proceed()
            }

            // Hook c method - auxiliary method for forcefully killing processes (dynamic lookup via DEXKit)
            val cMethodName = findCMethodName()
            val cMethod: Method = overviewUtilitiesClass.getDeclaredMethod(
                cMethodName, Context::class.java, String::class.java, Int::class.javaPrimitiveType
            )
            hookWithId(cMethod, "hook_165") { chain ->
                val pkgName = chain.args[1] as String
                val uid = chain.args[2] as Int

                // Check if in whitelist
                if (isProtectedPackage(pkgName)) {
                    // In whitelist, prevent forced kill
                    logger.trace("Android 16: Blocked forced killing app in whitelist: $pkgName (UID: $uid)")
                    return@hookWithId null
                }

                // Not in whitelist, allow original method execution
                logger.trace("Android 16: Allow forced killing app: $pkgName")
                chain.proceed()
            }

            // Hook removeAllRunningAppProcesses method - batch cleanup entry point
            val removeAllMethod: Method = overviewUtilitiesClass.getDeclaredMethod(
                "removeAllRunningAppProcesses", Context::class.java, ArrayList::class.java, Boolean::class.javaPrimitiveType
            )
            hookWithId(removeAllMethod, "remove_all_1") { chain ->
                val tasks = chain.args[1] as ArrayList<*>?

                if (tasks != null) {
                    val totalTasks = tasks.size
                    var protectedCount = 0

                    // Record whitelisted apps
                    for (task in tasks) {
                        try {
                            // Try to get package name corresponding to task
                            val pkgName = getPackageNameFromTask(task)
                            if (pkgName != null && isProtectedPackage(pkgName)) {
                                protectedCount++
                                logger.trace("Android 16: Whitelist APP detected when performing batch kill: $pkgName")
                            }
                        } catch (_: Exception) {
                            // If unable to get package name, skip
                        }
                    }

                    if (protectedCount > 0) {
                        // If contains whitelisted app, prevent entire batch cleanup operation
                        logger.trace("Android 16: $protectedCount included in batch kill list, blocking kill operation")
                        return@hookWithId null
                    }

                    // Does not contain whitelisted app, allow batch cleanup execution
                    logger.trace("Android 16: $totalTasks APP(s) are allowed to be killed.")
                }

                chain.proceed()
            }

            // Hook AsyncTask subclass doInBackground method - asynchronous cleanup logic
            val asyncTaskClass = findInnerClass(classLoader)

            if (asyncTaskClass != null) {
                val doInBackgroundMethod: Method =
                    asyncTaskClass.getDeclaredMethod("doInBackground", arrayOf<Void>().javaClass)
                hookWithId(doInBackgroundMethod, "do_in_background") { chain ->
                    try {
                        // Try to get task list
                        val thisObject = chain.thisObject
                        val tasksField: Field = thisObject.javaClass.getDeclaredField("tasks")
                        tasksField.isAccessible = true
                        val tasks = tasksField.get(thisObject)

                        if (tasks is ArrayList<*>) {
                            for (task in tasks) {
                                try {
                                    val pkgName = getPackageNameFromTask(task)
                                    if (pkgName != null && isProtectedPackage(pkgName)) {
                                        logger.trace("Android 16: Whitelist app detected in async task, count: $pkgName, blocking async task")
                                        return@hookWithId null
                                    }
                                } catch (_: Exception) {
                                    // Skip unrecognizable tasks
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // If unable to check, block by default
                        logger.warn("Android 16: Unable to check async task, blocking it by default")
                        return@hookWithId null
                    }

                    // Does not contain whitelisted app, allow execution
                    logger.trace("Android 16: Allowed to perform async kill")
                    chain.proceed()
                }
            }

            // Try hooking methods that may be newly added in Android 16
            hookAdditionalAndroid16Methods(classLoader)

            logger.info("Hook for Android 16+ ZUI Launcher successfully applied.")
        } catch (t: Throwable) {
            logger.error("Android 16+: Failed to hook ZUI Launcher", t)
        }
    }

    /**
     * Android 16+ basic Launcher Hook
     */
    private fun hookBaseLauncherAndroid16() {
        try {
            // Possible Hook points for basic Launcher on Android 16
            // Specific hooks for com.android.launcher3 can be added here as needed
            logger.warn("Android 16 logic not implemented yet!")
        } catch (t: Throwable) {
            logger.error("Android 16+: failed to hook basic Launcher", t)
        }
    }

    /**
     * General Hook strategy for Android 15 and lower versions (with whitelist mechanism)
     */
    @SuppressLint("PrivateApi")
    private fun hookLegacyLauncher(classLoader: ClassLoader) {
        try {
            logger.info("Start hooking legacy Launcher with whitelist enabled.")

            // Hook ActivityManagerWrapper class methods
            val amwclass = try {
                classLoader.loadClass("com.android.systemui.shared.system.ActivityManagerWrapper")
            } catch (_: ClassNotFoundException) {
                null
            }

            if (amwclass != null) {
                logger.info("Found ActivityManagerWrapper class, starting Hook...")

                val removeAllMethod: Method = amwclass.getDeclaredMethod(
                    "removeAllRunningAppProcesses", Context::class.java, ArrayList::class.java
                )
                hookWithId(removeAllMethod, "remove_all_2") { chain ->
                    val tasks = chain.args[1] as ArrayList<*>?

                    if (tasks != null) {
                        var protectedCount = 0
                        for (task in tasks) {
                            try {
                                val pkgName = getPackageNameFromTask(task)
                                if (pkgName != null && isProtectedPackage(pkgName)) {
                                    protectedCount++
                                }
                            } catch (_: Exception) {
                                // Skip unrecognizable tasks
                            }
                        }

                        if (protectedCount > 0) {
                            logger.trace("Legacy architecture: Batch cleanup contains $protectedCount whitelisted app(s), blocking cleanup")
                            return@hookWithId null
                        }
                    }

                    chain.proceed()
                }

                val removeAppProcessMethod: Method = amwclass.getDeclaredMethod(
                    "removeAppProcess", Context::class.java, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType
                )
                hookWithId(removeAppProcessMethod, "remove_app_process_2") { chain ->
                    val pkgName = chain.args[2] as String

                    if (isProtectedPackage(pkgName)) {
                        logger.trace("Legacy architecture: Prevent killing whitelisted app: $pkgName")
                        return@hookWithId null
                    }

                    chain.proceed()
                }

                logger.info("ActivityManagerWrapper Hook completed [OK], whitelist mechanism active")
            } else {
                logger.warn("ActivityManagerWrapper class not found, trying other Hook points...")
                // Fallback Hook points can be added here
            }
        } catch (e: Exception) {
            logger.error("Failed to hook legacy launcher", e)
        }
    }

    /**
     * Possible newly added Hook points in Android 16
     */
    private fun hookAdditionalAndroid16Methods(classLoader: ClassLoader) {
        try {
            // Try hooking task management related methods that may be newly added in Android 16
            val potentialClasses = arrayOf(
                "com.zui.launcher.taskbar.TaskbarManager",
                "com.zui.launcher.recents.RecentsModel",
                "com.zui.launcher.recents.TaskStackListener"
            )

            for (className in potentialClasses) {
                val targetClass = try {
                    classLoader.loadClass(className)
                } catch (_: ClassNotFoundException) {
                    null
                }
                if (targetClass != null) {
                    logger.debug("Android 16 new class: $className")
                    // Specific hook logic can be added here as needed
                }
            }
        } catch (_: Throwable) {
            // Ignore errors, these are optional Hook points
            logger.info("Android 16 extra hook points detection completed.")
        }
    }

    /**
     * Extract package name from task object
     * @param task Task object
     * @return Package name, or null if cannot be extracted
     */
    private fun getPackageNameFromTask(task: Any?): String? {
        if (task == null) {
            return null
        }

        try {
            // Method 1: Try getting package name via ComponentName
            val componentNameField: Field = task.javaClass.getDeclaredField("componentName")
            componentNameField.isAccessible = true
            val componentName = componentNameField.get(task)
            if (componentName != null) {
                val getPackageNameMethod: Method =
                    componentName.javaClass.getMethod("getPackageName")
                val packageNameObj = getPackageNameMethod.invoke(componentName)
                if (packageNameObj is String) {
                    return packageNameObj
                }
            }

            // Method 2: Try directly getting packageName field
            try {
                val packageNameField: Field = task.javaClass.getDeclaredField("packageName")
                packageNameField.isAccessible = true
                val packageNameFieldVal = packageNameField.get(task)
                if (packageNameFieldVal is String) {
                    return packageNameFieldVal
                }
            } catch (_: NoSuchFieldException) {
                // Field may not exist, continue trying other methods
            }

            // Method 3: Try getting package name via BaseActivityInfo
            try {
                val baseActivityInfoField: Field = task.javaClass.getDeclaredField("baseActivityInfo")
                baseActivityInfoField.isAccessible = true
                val baseActivityInfo = baseActivityInfoField.get(task)
                if (baseActivityInfo != null) {
                    val packageNameField: Field =
                        baseActivityInfo.javaClass.getDeclaredField("packageName")
                    packageNameField.isAccessible = true
                    val packageNameObj = packageNameField.get(baseActivityInfo)
                    if (packageNameObj is String) {
                        return packageNameObj
                    }
                }
            } catch (_: NoSuchFieldException) {
                // Field may not exist
            }

            // Method 4: Try getting package name via taskDescription
            try {
                val taskDescriptionField: Field = task.javaClass.getDeclaredField("taskDescription")
                taskDescriptionField.isAccessible = true
                val taskDescription = taskDescriptionField.get(task)
                if (taskDescription != null) {
                    val getPackageNameMethod: Method =
                        taskDescription.javaClass.getMethod("getPackageName")
                    val packageNameObj = getPackageNameMethod.invoke(taskDescription)
                    if (packageNameObj is String) {
                        return packageNameObj
                    }
                }
            } catch (_: NoSuchFieldException) {
                // Field may not exist
            }
        } catch (_: Exception) {
            // All methods failed, return null
        }

        return null
    }

    /**
     * Read obfuscated method name with signature (Context, String, int)->void in OverviewUtilities from offline index.
     * Fallback to hardcoded "c" if index is missing or fails.
     */
    private fun findCMethodName(): String {
        val name = DexIndexStore.string(
            xposed,
            ScopeKeys.LAUNCHER.packageName,
            DexIndexConstants.ModuleKeys.DISABLE_FORCE_STOP,
            DexIndexConstants.Keys.FORCE_STOP_METHOD
        )
        if (name != null) {
            logger.info("Loaded force-stop method from dex index: $name")
            return name
        }
        return "c" // Fallback to hardcoded
    }

    /**
     * Find inner class via reflection (handles obfuscated inner class names).
     * Iterate through possible inner class names ($1-$5, $a-$e) until a class with doInBackground method is found.
     */
    private fun findInnerClass(classLoader: ClassLoader): Class<*>? {
        // First try common obfuscation patterns: $a, $b, $c, $d, $e
        for (suffix in 'a'..'e') {
            try {
                val cls = classLoader.loadClass("com.zui.launcher.util.OverviewUtilities$$suffix")
                // Verify: this inner class should have a doInBackground method
                try {
                    cls.getDeclaredMethod("doInBackground", arrayOf<Void>().javaClass)
                    logger.info("Found inner class: ${cls.name}")
                    return cls
                } catch (_: NoSuchMethodException) {
                }
            } catch (_: ClassNotFoundException) {
            }
        }
        // Then try numeric suffixes: $1, $2, $3, $4, $5
        for (i in 1..5) {
            try {
                val cls = classLoader.loadClass("com.zui.launcher.util.OverviewUtilities$$i")
                try {
                    cls.getDeclaredMethod("doInBackground", arrayOf<Void>().javaClass)
                    logger.info("Found inner class: ${cls.name}")
                    return cls
                } catch (_: NoSuchMethodException) {
                }
            } catch (_: ClassNotFoundException) {
            }
        }
        return null
    }

    /**
     * Get current Android SDK version
     */
    private fun getSDKVersion(): Int {
        return try {
            Build.VERSION.SDK_INT
        } catch (t: Throwable) {
            logger.error("Failed to fetch SDK level, use default.", t)
            Build.VERSION_CODES.BASE // Return minimum version
        }
    }
}

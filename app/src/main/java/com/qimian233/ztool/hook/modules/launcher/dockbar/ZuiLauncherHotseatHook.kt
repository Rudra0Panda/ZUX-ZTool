package com.qimian233.ztool.hook.modules.launcher.dockbar

import android.annotation.SuppressLint
import android.content.Intent
import android.view.View
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.hook.base.AppHookModule
import com.qimian233.ztool.hook.base.DexIndexStore
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * ZUI Launcher Hotseat扩展Hook模块
 * 解除ZUI Launcher的Hotseat最大数量限制，支持添加更多应用到底部快捷栏
 * ZUI Launcher Hotseat expansion hook module.
 * Removes the maximum count limit on ZUI Launcher's Hotseat, supporting adding more apps to the bottom dock bar.
 */
@SuppressLint("PrivateApi")
class ZuiLauncherHotseatHook : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.ZUI_LAUNCHER_HOTSEAT.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        // 避让逻辑做到 Hook 层，repository 保持干净
        // Evasion logic handled at Hook layer; repository stays clean
        val disableDockBar: Boolean = try {
            remotePreferences.getBoolean(PreferenceKeys.DISABLE_DOCK_BAR.name, false)
        } catch (_: Throwable) {
            false
        }
        if (disableDockBar) {
            logger.warn("Disable dock bar hook enabled, will not expand dock bar.")
            return
        }

        logger.info("开始Hook ZUI Launcher Hotseat限制")
        logger.info("Starting hook for ZUI Launcher Hotseat limit")

        try {
            // Hook 1: 绕过Hotseat最大数量检查
            // Hook 1: Bypass Hotseat max count check
            hookHotseatMaxCount(classLoader)

            // Hook 2: 绕过空间检查
            // Hook 2: Bypass space checks
            hookSpaceChecks(classLoader)

            // Hook 3: 修改DeviceProfile配置
            // Hook 3: Modify DeviceProfile configuration
            hookDeviceProfile(classLoader)

            // Hook 4: 修复的添加项目方法
            // Hook 4: Patched add item methods
            hookAddItemMethods(classLoader)

            // Hook 5: 修改数据库层面的Hotseat限制
            // Hook 5: Modify database-level Hotseat limit
            hookDatabaseHotseatLimit(classLoader)

            // Hook 6: 修改LoaderCursor的位置检查逻辑
            // Hook 6: Modify LoaderCursor position check logic
            hookLoaderCursorMethods(classLoader)

            // Hook 7: 数据库操作Hook
            // Hook 7: Database operation hooks
            hookDatabaseOperations(classLoader)

            // Hook 9: CellLayout相关方法
            // Hook 9: CellLayout related methods
            hookCellLayoutMethods(classLoader)

            logger.info("ZUI Launcher Hotseat Hook完成")
            logger.info("ZUI Launcher Hotseat hook completed")
        } catch (t: Throwable) {
            logger.error("ZUI Launcher Hook过程中发生错误", t)
            logger.error("Error occurred while hooking ZUI Launcher", t)
        }
    }

    /**
     * Hook 1: 修改Hotseat的最大数量限制
     * Hook 1: Modify Hotseat maximum count limit
     */
    private fun hookHotseatMaxCount(classLoader: ClassLoader) {
        try {
            val hotseatClass = classLoader.loadClass("com.android.launcher3.Hotseat")
            val getMaxCountMethod = hotseatClass.getDeclaredMethod("getMaxCount")
            hookWithId(getMaxCountMethod, "get_max_count") { chain ->
                chain.proceed()
                logger.debug("修改Hotseat最大数量为20")
                logger.debug("Modified Hotseat maximum count to 20")
                20
            }
        } catch (t: Throwable) {
            logger.error("Hook getMaxCount失败", t)
            logger.error("Failed to hook getMaxCount", t)
        }
    }

    /**
     * Hook 2: 绕过各种空间检查方法
     * Hook 2: Bypass various space check methods
     */
    private fun hookSpaceChecks(classLoader: ClassLoader) {
        try {
            val launcherClass = classLoader.loadClass("com.android.launcher3.Launcher")

            // Hook Launcher的showOutOfSpaceMessage方法，阻止显示空间不足提示
            // Hook Launcher.showOutOfSpaceMessage to prevent showing out of space alert
            val showOutOfSpaceMethod = findMethod(launcherClass, "showOutOfSpaceMessage",
                Boolean::class.javaPrimitiveType
            )
            hookWithId(showOutOfSpaceMethod, "show_out_of_space") {
                logger.debug("阻止显示空间不足提示")
                logger.debug("Prevented showing out of space message")
                null
            }

            // Hook checkOccupiedShortcut方法，使其总是返回true（可以放置）
            // Hook checkOccupiedShortcut to always return true (can place)
            val workspaceItemInfoClass =
                classLoader.loadClass("com.android.launcher3.model.data.WorkspaceItemInfo")
            val workspaceClass = classLoader.loadClass("com.android.launcher3.Workspace")
            val checkOccupiedMethod = findMethod(launcherClass, "checkOccupiedShortcut",
                View::class.java,
                workspaceItemInfoClass,
                workspaceClass,
                Boolean::class.javaPrimitiveType
            )
            hookWithId(checkOccupiedMethod, "check_occupied") { chain ->
                chain.proceed()
                logger.debug("强制通过空间检查")
                logger.debug("Forced space check to pass")
                true
            }
        } catch (t: Throwable) {
            logger.error("Hook空间检查失败", t)
            logger.error("Failed to hook space checks", t)
        }
    }

    /**
     * Hook 3: 修改DeviceProfile配置
     * Hook 3: Modify DeviceProfile configuration
     */
    private fun hookDeviceProfile(classLoader: ClassLoader) {
        try {
            val deviceProfileClass = classLoader.loadClass("com.android.launcher3.DeviceProfile")

            // Hook DeviceProfile的getHotseatColumnSpan
            // Hook DeviceProfile.getHotseatColumnSpan
            val getHotseatColumnSpanMethod =
                deviceProfileClass.getDeclaredMethod("getHotseatColumnSpan")
            hookWithId(getHotseatColumnSpanMethod, "get_hotseat_column_span") { chain ->
                chain.proceed()
                20
            }

            // Hook recalculateHotseatWidthAndBorderSpace方法
            // Hook recalculateHotseatWidthAndBorderSpace method
            val recalculateMethod =
                deviceProfileClass.getDeclaredMethod("recalculateHotseatWidthAndBorderSpace")
            hookWithId(recalculateMethod, "recalculate") { chain ->
                chain.proceed()
                val deviceProfile = chain.thisObject
                // 强制设置numShownHotseatIcons为20
                // Force set numShownHotseatIcons to 20
                val numShownField = findField(deviceProfileClass, "numShownHotseatIcons")
                numShownField.set(deviceProfile, 20)
                logger.debug("修改DeviceProfile的Hotseat配置")
                logger.debug("Modified DeviceProfile Hotseat configuration")
                null
            }
        } catch (t: Throwable) {
            logger.error("Hook DeviceProfile失败", t)
            logger.error("Failed to hook DeviceProfile", t)
        }
    }

    /**
     * Hook 4: 修复的添加项目方法
     * Hook 4: Patched add item methods
     */
    private fun hookAddItemMethods(classLoader: ClassLoader) {
        try {
            val launcherClass = classLoader.loadClass("com.android.launcher3.Launcher")
            val pendingRequestArgsClass =
                classLoader.loadClass("com.android.launcher3.util.PendingRequestArgs")
            val pendingAddItemInfoClass =
                classLoader.loadClass("com.android.launcher3.PendingAddItemInfo")

            // Hook completeAddShortcut方法，绕过添加限制
            // Hook completeAddShortcut method to bypass add restrictions
            val completeAddMethod = launcherClass.getDeclaredMethod(
                "completeAddShortcut",
                Intent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                pendingRequestArgsClass
            )
            hookWithId(completeAddMethod, "complete_add") { chain ->
                logger.debug("准备添加快捷方式到Hotseat")
                logger.debug("Preparing to add shortcut to Hotseat")
                chain.proceed()
            }

            // Hook addPendingItem方法
            // Hook addPendingItem method
            val addPendingItemMethod = launcherClass.getDeclaredMethod(
                "addPendingItem",
                pendingAddItemInfoClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                IntArray::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            hookWithId(
                addPendingItemMethod,
                "add_pending_item"
            ) { chain ->
                // 确保添加项目时不会受到限制
                // Ensure adding items is not restricted
                val container = chain.args[1] as Int

                if (container == -101) { // -101是Hotseat的容器ID
                    logger.debug("正在添加项目到Hotseat，绕过限制")
                if (container == -101) { // -101 is Hotseat container ID
                    logger.debug("Adding item to Hotseat, bypassing limits")
                }
                chain.proceed()
            }

            // Hook addToWorkspace方法（更通用的方法）
            // Hook addToWorkspace method (more general method)
            try {
                val itemInfoClass =
                    classLoader.loadClass("com.android.launcher3.model.data.ItemInfo")
                val addToWorkspaceMethod = launcherClass.getDeclaredMethod(
                    "addToWorkspace",
                    itemInfoClass, Boolean::class.javaPrimitiveType
                )
                hookWithId(
                    addToWorkspaceMethod,
                    "add_to_workspace"
                ) { chain ->
                    val itemInfo = chain.args[0]
                    val containerField = findField(itemInfo.javaClass, "container")
                    val container = containerField.getInt(itemInfo)

                    if (container == -101) {
                        logger.debug("添加项目到Hotseat工作区")
                        logger.debug("Adding item to Hotseat workspace")
                    }
                    chain.proceed()
                }
            } catch (t: Throwable) {
                logger.error("Hook addToWorkspace失败", t)
                logger.error("Failed to hook addToWorkspace", t)
            }
        } catch (t: Throwable) {
            logger.error("Hook添加方法失败", t)
            logger.error("Failed to hook add methods", t)
        }
    }

    /**
     * Hook 5: 修改数据库层面的Hotseat数量限制
     * Hook 5: Modify database-level Hotseat count limit
     */
    private fun hookDatabaseHotseatLimit(classLoader: ClassLoader) {
        try {
            val invProfileClass =
                classLoader.loadClass("com.android.launcher3.InvariantDeviceProfile")

            // Hook InvariantDeviceProfile的getNumDatabaseHotseatIcons
            // Hook InvariantDeviceProfile.getNumDatabaseHotseatIcons
            val getNumMethod = invProfileClass.getDeclaredMethod("getNumDatabaseHotseatIcons")
            hookWithId(getNumMethod, "get_num") { chain ->
                chain.proceed()
                logger.debug("修改数据库Hotseat数量为20")
                logger.debug("Modified database Hotseat count to 20")
                20
            }

            // 直接修改numDatabaseHotseatIcons字段（备用方案）
            // Directly modify numDatabaseHotseatIcons field (fallback plan)
            try {
                val numField = findField(invProfileClass, "numDatabaseHotseatIcons")
                numField.set(null, 20)
                logger.debug("直接修改numDatabaseHotseatIcons为20")
                logger.debug("Directly modified numDatabaseHotseatIcons to 20")
            } catch (t: Throwable) {
                logger.error("直接修改numDatabaseHotseatIcons失败", t)
                logger.error("Failed to directly modify numDatabaseHotseatIcons", t)
            }
        } catch (t: Throwable) {
            logger.error("Hook数据库Hotseat限制失败", t)
            logger.error("Failed to hook database Hotseat limit", t)
        }
    }

    /**
     * Hook 6: 修改LoaderCursor的位置检查逻辑
     * Hook 6: Modify LoaderCursor position check logic
     */
    private fun hookLoaderCursorMethods(classLoader: ClassLoader) {
        try {
            val loaderCursorClass =
                classLoader.loadClass("com.android.launcher3.model.LoaderCursor")
            val itemInfoClass = classLoader.loadClass("com.android.launcher3.model.data.ItemInfo")
            val bgDataModelClass = classLoader.loadClass("com.android.launcher3.model.BgDataModel")

            // Hook checkItemPlacement方法，绕过Hotseat位置检查
            // Hook checkItemPlacement method, bypassing Hotseat position checks
            val checkItemPlacementMethod = loaderCursorClass.getDeclaredMethod(
                "checkItemPlacement",
                itemInfoClass, Boolean::class.javaPrimitiveType
            )
            hookWithId(
                checkItemPlacementMethod,
                "check_item_placement"
            ) { chain ->
                val itemInfo = chain.args[0]
                val containerField = findField(itemInfo.javaClass, "container")
                val container = containerField.getInt(itemInfo)
                val screenIdField = findField(itemInfo.javaClass, "screenId")
                val screenId = screenIdField.getInt(itemInfo)

                // 如果是Hotseat且位置在扩展范围内，直接返回true
                // If Hotseat and position is within extended range, directly return true
                if (container == -101 && screenId >= 0 && screenId < 20) {
                    logger.debug("强制通过Hotseat位置检查: $screenId")
                    logger.debug("Forced Hotseat position check to pass: $screenId")
                    return@hookWithId true
                }
                chain.proceed()
            }

            // Hook b方法（维度检查）— 方法名来自离线索引
            // Hook b method (dimension check) - method name from offline index
            val bMethodName = findBMethodName()
            val bMethod = loaderCursorClass.getDeclaredMethod(bMethodName, itemInfoClass)
            hookWithId(bMethod, "hook_289") { chain ->
                val result = chain.proceed()
                val itemInfo = chain.args[0]
                val containerField = findField(itemInfo.javaClass, "container")
                val container = containerField.getInt(itemInfo)

                // 如果是Hotseat，强制返回false（不删除）
                // If Hotseat, force return false (do not delete)
                if (container == -101) {
                    logger.debug("绕过Hotseat维度检查")
                    logger.debug("Bypassed Hotseat dimension check")
                    return@hookWithId false
                }
                result
            }

            // Hook checkAndAddItem方法
            // Hook checkAndAddItem method
            val checkAndAddItemMethod = loaderCursorClass.getDeclaredMethod(
                "checkAndAddItem",
                itemInfoClass, bgDataModelClass
            )
            hookWithId(
                checkAndAddItemMethod,
                "check_and_add_item"
            ) { chain ->
                val itemInfo = chain.args[0]
                val containerField = findField(itemInfo.javaClass, "container")
                val container = containerField.getInt(itemInfo)
                val screenIdField = findField(itemInfo.javaClass, "screenId")
                val screenId = screenIdField.getInt(itemInfo)

                if (container == -101) {
                    logger.debug("checkAndAddItem - Hotseat位置: $screenId")
                    logger.debug("checkAndAddItem - Hotseat position: $screenId")
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.error("Hook LoaderCursor失败", t)
            logger.error("Failed to hook LoaderCursor", t)
        }
    }

    /**
     * Hook 7: 数据库操作Hook
     * Hook 7: Database operation hooks
     */
    private fun hookDatabaseOperations(classLoader: ClassLoader) {
        try {
            val launcherModelClass = classLoader.loadClass("com.android.launcher3.LauncherModel")
            val itemInfoClass = classLoader.loadClass("com.android.launcher3.model.data.ItemInfo")

            // Hook LauncherModel的addOrMoveItemInDatabase方法
            // Hook LauncherModel.addOrMoveItemInDatabase
            val addOrMoveMethod = launcherModelClass.getDeclaredMethod(
                "addOrMoveItemInDatabase",
                itemInfoClass,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            hookWithId(addOrMoveMethod, "add_or_move") { chain ->
                val container = chain.args[1] as Int
                val screen = chain.args[2] as Int

                if (container == -101 && screen >= 5) {
                    logger.debug("数据库操作 - Hotseat位置: $screen")
                    // 允许操作继续
                    logger.debug("Database operation - Hotseat position: $screen")
                    // Allow operation to proceed
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            logger.error("Hook数据库操作失败", t)
            logger.error("Failed to hook database operations", t)
        }
    }

    /**
     * Hook 9: 修改CellLayout相关方法
     * Hook 9: Modify CellLayout related methods
     */
    private fun hookCellLayoutMethods(classLoader: ClassLoader) {
        try {
            val cellLayoutClass = classLoader.loadClass("com.android.launcher3.CellLayout")

            // Hook CellLayout的findCellForSpan方法，使其总是能找到位置
            // Hook CellLayout.findCellForSpan so it always finds a spot
            val findCellMethod = cellLayoutClass.getDeclaredMethod(
                "findCellForSpan",
                IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            hookWithId(findCellMethod, "find_cell") { chain ->
                val result = chain.proceed() as Boolean
                if (!result) {
                    // 如果原本找不到位置，强制返回true并设置坐标
                    // If no spot was originally found, force return true and set coordinates
                    val cellXY = chain.args[0] as IntArray
                    cellXY[0] = 0
                    cellXY[1] = 0
                    logger.debug("强制找到Cell位置")
                    logger.debug("Forced cell position found")
                    return@hookWithId true
                }
                true
            }
        } catch (t: Throwable) {
            logger.error("Hook CellLayout失败", t)
            logger.error("Failed to hook CellLayout", t)
        }
    }

    /**
     * 从离线索引读取 LoaderCursor 中签名 (ItemInfo)→boolean 的混淆方法名。
     * 索引缺失/失败时回退硬编码 "b"。
     * Read obfuscated method name with signature (ItemInfo)->boolean in LoaderCursor from offline index.
     * Fallback to hardcoded "b" if index is missing or fails.
     */
    private fun findBMethodName(): String {
        return DexIndexStore.string(
            xposed,
            ScopeKeys.LAUNCHER.packageName,
            DexIndexConstants.ModuleKeys.ZUI_LAUNCHER_HOTSEAT,
            DexIndexConstants.Keys.LOADER_CURSOR_B_METHOD
        ) ?: "b"
    }
}

package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import com.qimian233.ztool.hook.modules.setting.OwnerInfoUpdater
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Method

/**
 * 锁屏OwnerInfo自动更新Hook模块（System server 侧）。
 * Lock screen OwnerInfo auto-update Hook module (System server side).
 * <p>
 * 由 [OwnerInfoHook] 拆分而来：在 system server 中监听
 * PowerManagerService.setPowerState / userActivity 及
 * ContextImpl.registerReceiver，触发 OwnerInfo 更新。
 * 核心更新逻辑见 [OwnerInfoUpdater]。
 * Split from [OwnerInfoHook]: listens to PowerManagerService.setPowerState / userActivity
 * and ContextImpl.registerReceiver in system server to trigger OwnerInfo updates.
 * Core update logic is in [OwnerInfoUpdater].
 * </p>
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class OwnerInfoSystemHook : SystemHookModule() {

    private var mScreenReceiver: BroadcastReceiver? = null
    private var mIsReceiverRegistered = false

    override fun getModuleName(): String = PreferenceKeys.AUTO_OWNER_INFO.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.ANDROID_SYSTEM.packageName)

    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader
        hookSystemPackage(classLoader)
    }

    private fun hookSystemPackage(classLoader: ClassLoader) {
        logger.info("开始Hook System包")
        logger.info("Starting hook for System package")
        val updater = OwnerInfoUpdater(xposed, logger)

        // Hook点1: 在系统PowerManagerService中监听屏幕状态
        // Hook point 1: Monitor screen state in system PowerManagerService
        try {
            val setPowerStateMethod: Method = classLoader
                .loadClass("com.android.server.power.PowerManagerService")
                .getDeclaredMethod("setPowerState", Boolean::class.javaPrimitiveType)
            hookWithId(setPowerStateMethod, "set_power_state") { chain ->
                val result = chain.proceed()
                val screenOn = chain.args[0] as Boolean
                logger.debug("电源状态改变，屏幕状态: $screenOn")
                logger.debug("Power state changed, screen state: $screenOn")

                if (screenOn) {
                    // 屏幕亮起时更新OwnerInfo
                    // Update OwnerInfo when screen turns on
                    updater.updateOwnerInfo(null, classLoader)
                }
                result
            }
            logger.info("成功Hook PowerManagerService.setPowerState")
            logger.info("Successfully hooked PowerManagerService.setPowerState")
        } catch (e: Throwable) {
            logger.error("Hook PowerManagerService.setPowerState失败", e)
            logger.error("Failed to hook PowerManagerService.setPowerState", e)
        }

        // Hook点2: 用户活动监听
        // Hook point 2: User activity monitoring
        try {
            val userActivityMethod: Method = classLoader
                .loadClass("com.android.server.power.PowerManagerService")
                .getDeclaredMethod(
                    "userActivity",
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            hookWithId(userActivityMethod, "user_activity") { chain ->
                val result = chain.proceed()
                val event = chain.args[0] as Int
                // 用户活动事件，包括屏幕触摸、按键等
                // User activity events, including touch, button presses, etc.
                if (event == 0 || event == 2 || event == 3) { // POWER_BUTTON, TOUCH, etc.
                    logger.debug("检测到用户活动，更新OwnerInfo")
                    logger.debug("Detected user activity, updating OwnerInfo")
                    updater.updateOwnerInfo(null, classLoader)
                }
                result
            }
            logger.info("成功Hook PowerManagerService.userActivity")
            logger.info("Successfully hooked PowerManagerService.userActivity")
        } catch (e: Throwable) {
            logger.error("Hook PowerManagerService.userActivity失败", e)
            logger.error("Failed to hook PowerManagerService.userActivity", e)
        }

        // Hook点3: 在ContextImpl中注册广播接收器
        // Hook point 3: Register broadcast receiver in ContextImpl
        try {
            val registerReceiverMethod: Method = classLoader
                .loadClass("android.app.ContextImpl")
                .getDeclaredMethod(
                    "registerReceiver",
                    BroadcastReceiver::class.java,
                    IntentFilter::class.java
                )
            hookWithId(registerReceiverMethod, "register_receiver") { chain ->
                // 检查是否是我们自己的接收器，避免重复注册
                // Check if this is our own receiver to avoid duplicate registration
                if (chain.args[0] == mScreenReceiver) {
                    return@hookWithId chain.proceed()
                }

                val filter = chain.args[1] as IntentFilter
                if (hasScreenActions(filter)) {
                    // 这是一个包含屏幕动作的过滤器，我们可以在这里注册自己的接收器
                    // Filter contains screen actions; register our own receiver here
                    registerScreenReceiver(chain.thisObject, classLoader, updater)
                }
                chain.proceed()
            }
            logger.info("成功Hook ContextImpl.registerReceiver")
            logger.info("Successfully hooked ContextImpl.registerReceiver")
        } catch (e: Throwable) {
            logger.error("Hook ContextImpl.registerReceiver失败", e)
            logger.error("Failed to hook ContextImpl.registerReceiver", e)
        }
    }

    private fun hasScreenActions(filter: IntentFilter): Boolean {
        return try {
            // 检查过滤器是否包含屏幕相关的动作
            // Check if filter contains screen-related actions
            val actions = filter.actionsIterator()
            while (actions != null && actions.hasNext()) {
                val action = actions.next()
                if (Intent.ACTION_SCREEN_ON == action ||
                    Intent.ACTION_SCREEN_OFF == action ||
                    Intent.ACTION_USER_PRESENT == action
                ) {
                    return true
                }
            }
            false
        } catch (e: Throwable) {
            logger.error("检查IntentFilter动作时出错", e)
            logger.error("Error checking IntentFilter actions", e)
            false
        }
    }

    private fun registerScreenReceiver(
        contextObj: Any?,
        classLoader: ClassLoader,
        updater: OwnerInfoUpdater
    ) {
        if (mIsReceiverRegistered) {
            return
        }

        try {
            val context: Context
            if (contextObj is Context) {
                context = contextObj
            } else {
                // 尝试通过反射获取Context
                // Try getting Context via reflection
                val getContextMethod: Method = contextObj!!.javaClass.getDeclaredMethod("getContext")
                context = getContextMethod.invoke(contextObj) as Context
            }

            mScreenReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    logger.debug("收到广播: $action")
                    logger.debug("Received broadcast: $action")

                    if (Intent.ACTION_SCREEN_ON == action ||
                        Intent.ACTION_USER_PRESENT == action
                    ) {
                        // 屏幕亮起或用户解锁时更新OwnerInfo
                        // Update OwnerInfo when screen turns on or user unlocks
                        updater.updateOwnerInfo(context, classLoader)
                    }
                }
            }

            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_SCREEN_ON)
            filter.addAction(Intent.ACTION_USER_PRESENT)

            context.registerReceiver(mScreenReceiver, filter)
            mIsReceiverRegistered = true
            logger.debug("Successfully registered screen state broadcast receiver")
            // 立即更新一次
            // Update immediately once
            updater.updateOwnerInfo(context, classLoader)
        } catch (e: Throwable) {
            logger.error("注册广播接收器失败", e)
            logger.error("Failed to register broadcast receiver", e)
        }
    }
}

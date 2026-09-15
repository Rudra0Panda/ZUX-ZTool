package com.qimian233.ztool.hook.modules.systemframework

import android.annotation.SuppressLint
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 禁用 ZUI 高温降低亮度（HBM 高亮模式热保护）。
 * Disable ZUI high-temperature brightness throttling (HBM thermal protection).
 *
 * ZuiDisplayService（services.jar）在 hbm 温度传感器（type==SKIN, name=="hbm"）
 * 达到 quitTemperature 阈值时会关闭高亮模式（HBM），把屏幕亮度限制在普通亮度
 * 上限以下（setHbmBrightness / setHbmLux 中的温度判定）。
 * ZuiDisplayService (services.jar) turns off high brightness mode (HBM) when the HBM
 * temperature sensor (type==SKIN, name=="hbm") reaches the quitTemperature threshold,
 * restricting screen brightness below the standard brightness ceiling (temperature check in setHbmBrightness / setHbmLux).
 *
 * 本 Hook 将 ZuiDisplayService.pullTemperatureLocked() 的返回值固定为 0（视为
 * 低温），使温度判定恒不触发，高温下依然允许进入 HBM 高亮模式。
 * This Hook locks the return value of ZuiDisplayService.pullTemperatureLocked() to 0 (treated
 * as low temperature), preventing the thermal throttle check from ever triggering and allowing
 * HBM to remain active even under high temperatures.
 *
 * 生效方式：重启系统（system_server 进程）。
 * Activation: Restart system (system_server process).
 */
class DisableHbmThermalLimit : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.DISABLE_HBM_THERMAL_LIMIT.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    @SuppressLint("PrivateApi")
    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader
        try {
            logger.info("Hooking ZuiDisplayService.pullTemperatureLocked")
            val method = findMethod(
                classLoader.loadClass("com.android.server.display.ZuiDisplayService"),
                "pullTemperatureLocked"
            )
            // 固定返回 0（0.0°C），绕过 quitTemperature 高温判定
            // Constantly return 0 (0.0°C) to bypass quitTemperature high temperature check
            hookWithId(method, "disable_hbm_thermal_limit") { 0 }
            logger.info("Hooked ZuiDisplayService.pullTemperatureLocked [OK]")
        } catch (t: Throwable) {
            logger.error("Failed hooking ZuiDisplayService.pullTemperatureLocked", t)
        }
    }
}

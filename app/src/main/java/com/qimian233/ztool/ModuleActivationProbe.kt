package com.qimian233.ztool

/**
 * Module activation status detector.
 * <p>
 * Activation state is maintained by [ZToolApplication]—Application itself implements
 * [io.github.libxposed.service.XposedServiceHelper.OnServiceListener],
 * registered in [ZToolApplication.attachBaseContext],
 * updating [ZToolApplication.isModuleActivated] on onServiceBind/onServiceDied.
 * </p>
 * <p>
 * This class exposes the public [isModuleActive] query interface,
 * delegating directly to [ZToolApplication.isModuleActivated].
 * </p>
 */
object ModuleActivationProbe {

    fun isModuleActive(): Boolean = ZToolApplication.isModuleActivated
}

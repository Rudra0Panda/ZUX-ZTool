package com.qimian233.ztool.hook.modules.systemframework

import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.SystemHookModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Bypass signature identity (digest) comparison:
 * - SigningDetails.checkCapability / checkCapabilityRecover constantly returns true for non-PERMISSION/APPLICATION
 *   capability bits (preserves capability checks to prevent granting privileged permissions by mistake)
 * - KeySetManagerService upgrade KeySet verification constantly passes on install path
 * - InstallPackageHelper.doesSignatureMatchForPermissions allows same-package apps when "use previous signatures" is active
 * - StrictJarVerifier.verifyBytes extracts cert chain directly from signature block when "use previous signatures" is disabled
 * - hasCommonAncestor passes sharedUser lineage validation on verifySignatures call chain
 */
class PackageManagerDigestBypassHook : SystemHookModule() {

    override fun getModuleName(): String = PreferenceKeys.PKG_MGR_BYPASS_DIGEST.name

    override fun getTargetPackages(): Array<out String> = arrayOf(ScopeKeys.SYSTEM_SERVER.packageName)

    @Throws(Throwable::class)
    override fun handleSystemServerStarting(param: SystemServerStartingParam) {
        val classLoader = param.classLoader
        val usePreviousSignatures = remotePreferences.getBoolean(
            PreferenceKeys.PKG_MGR_USE_PREVIOUS_SIGNATURES.name,
            PreferenceKeys.PKG_MGR_USE_PREVIOUS_SIGNATURES.default
        )
        val sharedUserEnabled = remotePreferences.getBoolean(
            PreferenceKeys.PKG_MGR_BYPASS_SHARED_USER.name,
            PreferenceKeys.PKG_MGR_BYPASS_SHARED_USER.default
        )
        hookSigningDetails(classLoader, sharedUserEnabled)
        hookKeySetManagerService(classLoader)
        hookInstallPackageHelper(classLoader, usePreviousSignatures)
        hookStrictJarVerifier(classLoader, usePreviousSignatures)
        deoptCanJoinSharedUserId(classLoader)
    }

    private fun hookSigningDetails(classLoader: ClassLoader, sharedUserEnabled: Boolean) {
        try {
            val signingDetailsClass = classLoader.loadClass("android.content.pm.SigningDetails")
            val checkCapability = findMethod(
                signingDetailsClass, "checkCapability",
                signingDetailsClass, Int::class.javaPrimitiveType
            )
            hookWithId(checkCapability, "pkgmgr_check_capability") { chain ->
                if (!isCapabilityFlagProtected(chain.getArg(1))) {
                    true
                } else {
                    chain.proceed()
                }
            }
            val checkCapabilityRecover = findMethod(
                signingDetailsClass, "checkCapabilityRecover",
                signingDetailsClass, Int::class.javaPrimitiveType
            )
            hookWithId(checkCapabilityRecover, "pkgmgr_check_capability_recover") { chain ->
                if (!isCapabilityFlagProtected(chain.getArg(1))) {
                    true
                } else {
                    chain.proceed()
                }
            }
            val hasCommonAncestor = findMethod(
                signingDetailsClass, "hasCommonAncestor", signingDetailsClass
            )
            hookWithId(hasCommonAncestor, "pkgmgr_has_common_ancestor") { chain ->
                if (sharedUserEnabled && callStackContains("verifySignatures")) {
                    true
                } else {
                    chain.proceed()
                }
            }
            logger.info("Hooked SigningDetails capability checks")
        } catch (e: Throwable) {
            logger.error("Failed hooking SigningDetails", e)
        }
    }

    private fun hookKeySetManagerService(classLoader: ClassLoader) {
        try {
            val keySetClass = classLoader.loadClass("com.android.server.pm.KeySetManagerService")
            val shouldCheck = keySetClass.declaredMethods.first {
                it.name == "shouldCheckUpgradeKeySetLocked" &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            hookWithId(shouldCheck, "pkgmgr_should_check_upgrade_keyset") { chain ->
                // Limit stack anchor to install call chains (preparePackage / reconcileInstallPackages),
                // to avoid affecting settings reads and other callers
                if (callStackContains("preparePackage", "reconcileInstallPackages",
                        "preparePackageLI", "installPackageLI")
                ) {
                    true
                } else {
                    chain.proceed()
                }
            }
            val checkUpgrade = keySetClass.declaredMethods.first {
                it.name == "checkUpgradeKeySetLocked" &&
                    it.returnType == Boolean::class.javaPrimitiveType
            }
            hookWithId(checkUpgrade, "pkgmgr_check_upgrade_keyset") { chain ->
                if (callStackContains("preparePackage", "reconcileInstallPackages",
                        "preparePackageLI", "installPackageLI")
                ) {
                    true
                } else {
                    chain.proceed()
                }
            }
            logger.info("Hooked KeySetManagerService upgrade keyset checks")
        } catch (e: Throwable) {
            logger.error("Failed hooking KeySetManagerService", e)
        }
    }

    private fun hookInstallPackageHelper(classLoader: ClassLoader, usePreviousSignatures: Boolean) {
        try {
            val helperClass = classLoader.loadClass("com.android.server.pm.InstallPackageHelper")
            val doesSignatureMatch = helperClass.declaredMethods.first {
                it.name == "doesSignatureMatchForPermissions"
            }
            hookWithId(doesSignatureMatch, "pkgmgr_dosignature_match_for_permissions") { chain ->
                val original = chain.proceed()
                if (!usePreviousSignatures) return@hookWithId original
                // Only allow same-name package to prevent spoofing other apps
                if (original == false) {
                    val packageName = chain.getArg(1).javaClass
                        .methods.first { it.name == "getPackageName" }
                        .invoke(chain.getArg(1)) as String
                    if (packageName == chain.getArg(0)) {
                        return@hookWithId true
                    }
                }
                original
            }
            logger.info("Hooked InstallPackageHelper.doesSignatureMatchForPermissions")
        } catch (e: Throwable) {
            logger.error("Failed hooking doesSignatureMatchForPermissions", e)
        }
    }

    private fun hookStrictJarVerifier(classLoader: ClassLoader, usePreviousSignatures: Boolean) {
        if (usePreviousSignatures) return
        try {
            val verifierClass = classLoader.loadClass("android.util.jar.StrictJarVerifier")
            val verifyBytes = findMethod(
                verifierClass, "verifyBytes",
                ByteArray::class.java, ByteArray::class.java
            )
            val pkcs7Class = classLoader.loadClass("sun.security.pkcs.PKCS7")
            val pkcs7Ctor = pkcs7Class.declaredConstructors.first { ctor ->
                ctor.parameterCount == 1 && ctor.parameterTypes[0] == ByteArray::class.java
            }.apply { isAccessible = true }
            val getSignerInfos = pkcs7Class.getMethod("getSignerInfos")
            val signerInfoClass = classLoader.loadClass("sun.security.pkcs.SignerInfo")
            val getCertificateChain =
                signerInfoClass.getMethod("getCertificateChain", pkcs7Class)

            hookWithId(verifyBytes, "pkgmgr_jar_verify_bytes") { chain ->
                val original = chain.proceed()
                // When bypassing digest, signature block bytes may be corrupted; parse cert chain directly via PKCS7 structure
                try {
                    val block = pkcs7Ctor.newInstance(chain.getArg(0))
                    val signerInfos = getSignerInfos.invoke(block) as Array<*>
                    if (signerInfos.isEmpty()) return@hookWithId original
                    getCertificateChain.invoke(signerInfos[0], block)
                } catch (t: Throwable) {
                    logger.error("Failed to parse PKCS7 certificate chain", t)
                    original
                }
            }
            logger.info("Hooked StrictJarVerifier.verifyBytes")
        } catch (e: Throwable) {
            logger.error("Failed hooking verifyBytes", e)
        }
    }

    private fun deoptCanJoinSharedUserId(classLoader: ClassLoader) {
        try {
            val utilsClass =
                classLoader.loadClass("com.android.server.pm.PackageManagerServiceUtils")
            val canJoin = utilsClass.declaredMethods.first {
                it.name == "canJoinSharedUserId"
            }
            if (!xposed.deoptimize(canJoin)) {
                logger.warn("deoptimize canJoinSharedUserId failed")
            }
        } catch (e: Throwable) {
            logger.error("Failed deoptimizing canJoinSharedUserId", e)
        }
    }

    private fun isCapabilityFlagProtected(flags: Any?): Boolean {
        // 4 = PERMISSION, 16 = APPLICATION: allowing them could lead to falsely granted signature permissions, keep original
        val flag = (flags as? Int) ?: return true
        return flag == 4 || flag == 16
    }

    private fun callStackContains(vararg methodNames: String): Boolean {
        return Thread.currentThread().stackTrace.any { element ->
            methodNames.any { it == element.methodName }
        }
    }
}

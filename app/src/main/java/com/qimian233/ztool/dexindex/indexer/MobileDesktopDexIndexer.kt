package com.qimian233.ztool.dexindex.indexer

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.dexindex.base.DexIndexConstants
import com.qimian233.ztool.dexindex.base.DexIndexer
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData

/**
 * Offline indexer for mobiledesktop scope (com.motorola.mobiledesktop).
 *
 * Migrated as-is from DexKit queries in the following Hooks:
 * - BypassShareWarningHook (dialog method, tile refresh method; older manager class has disappeared
 *   with newer obfuscation, enabled path defaults to hardcoded MotoDiscoveryManager in Hook)
 * - DisableNearbyShareAutoOffHook (FileUnionSwitchManager obfuscated class/method,
 *   using log string "startCountDown()" as deobfuscation anchor)
 * - AutoAcceptFileTransferHook (ViewModel field, boolean field, LiveData field,
 *   LiveData update method)
 *
 * Note: Target classes mostly reside in `com.motorola.readyfor.*` / `com.motorola.motoaccount.sdk.*`
 * packages (not under scopePackage), so queries must not be narrowed using `searchPackages(scopePackage)`,
 * otherwise empty results will be returned silently.
 */
class MobileDesktopDexIndexer : DexIndexer {

    override val scopePackage: String = ScopeKeys.MOBILE_DESKTOP.packageName

    override fun index(bridge: DexKitBridge, context: Context): JsonObject {
        val modules = JsonObject()
        modules.add(DexIndexConstants.ModuleKeys.BYPASS_SHARE_WARNING, indexBypassShareWarning(bridge))
        modules.add(
            DexIndexConstants.ModuleKeys.DISABLE_NEARBY_SHARE_COUNTDOWN,
            indexDisableNearbyShareCountdown(bridge)
        )
        modules.add(
            DexIndexConstants.ModuleKeys.AUTO_ACCEPT_FILE_TRANSFER,
            indexAutoAcceptFileTransfer(bridge)
        )
        return modules
    }

    // ── BypassShareWarningHook ──────────────────────────────────────

    private fun indexBypassShareWarning(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        indexBypassDialogMethod(bridge, out)
        indexBypassTileRefreshMethod(bridge, out)
        return out
    }

    /** Method in dialog Activity that is zero-argument void and references R.string.file_share_expose_title field. */
    private fun indexBypassDialogMethod(bridge: DexKitBridge, out: JsonObject) {
        try {
            val md = bridge.findMethod {
                matcher {
                    paramTypes()
                    returnType = "void"
                    declaredClass = "com.motorola.readyfor.common.dialog.ActionNoticeCommonDialogActivity"
                    usingFields {
                        add {
                            name = "file_share_expose_title"
                        }
                    }
                }
            }
                // Require unique match after excluding class initializers (preserving original singleOrNull semantics)
                .singleOrNull { it.name != "<clinit>" }
            if (md != null) {
                out.addProperty(DexIndexConstants.Keys.DIALOG_METHOD, md.name)
                Log.i(TAG, "BypassShareWarningHook: dialog method = ${md.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BypassShareWarningHook: dialog method query failed", t)
        }
    }

    /** Zero-argument void method in BaseFileUnionTile referencing "refreshTile" log string (refreshes tile). */
    private fun indexBypassTileRefreshMethod(bridge: DexKitBridge, out: JsonObject) {
        try {
            val md = bridge.findMethod {
                matcher {
                    paramTypes()
                    returnType = "void"
                    declaredClass = "com.motorola.readyfor.tile.BaseFileUnionTile"
                    usingStrings("refreshTile")
                }
            }
                .singleOrNull { it.name != "<clinit>" }
            if (md != null) {
                out.addProperty(DexIndexConstants.Keys.TILE_REFRESH_METHOD, md.name)
                Log.i(TAG, "BypassShareWarningHook: tile refresh method = ${md.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BypassShareWarningHook: tile refresh method query failed", t)
        }
    }

    // ── DisableNearbyShareAutoOffHook ───────────────────────────────

    /**
     * FileUnionSwitchManager (obfuscated to com.motorola.motoaccount.sdk.se.c in newer versions):
     * Its startCountDown method is the only zero-argument void method in the entire APK referencing
     * the log string "startCountDown()", serving as a deobfuscation anchor to locate both class and method.
     */
    private fun indexDisableNearbyShareCountdown(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            val md = bridge.findMethod {
                matcher {
                    paramTypes()
                    returnType = "void"
                    usingStrings("startCountDown()")
                }
            }.singleOrNull()
            if (md != null) {
                val targetClass = md.declaredClass?.name
                if (targetClass == null) {
                    Log.w(TAG, "DisableNearbyShareAutoOffHook: declared class unavailable")
                    return out
                }
                out.addProperty(DexIndexConstants.Keys.TARGET_CLASS, targetClass)
                out.addProperty(DexIndexConstants.Keys.TARGET_METHOD, md.name)
                Log.i(TAG, "DisableNearbyShareAutoOffHook: target = $targetClass / ${md.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DisableNearbyShareAutoOffHook: discovery failed", t)
        }
        return out
    }

    // ── AutoAcceptFileTransferHook ──────────────────────────────────

    /**
     * 4-step chained query: Find ViewModel field in Activity -> find boolean field in ViewModel class ->
     * find LiveData field referenced in Activity.onStart (user accept/reject decision signal,
     * corresponding to notification shade "Accept" button write path) -> find (Object)void update method in LiveData inheritance chain.
     * Query D traces up the superClass chain (consistent with Java reflection while loop semantics).
     */
    private fun indexAutoAcceptFileTransfer(bridge: DexKitBridge): JsonObject {
        val out = JsonObject()
        try {
            // Step A: Find ViewModel subtype field in FileConnectionConfirmActivity
            val activityClass = bridge.findClass {
                matcher {
                    className("com.motorola.mobiledesktop.files.pc2phone.FileConnectionConfirmActivity")
                }
            }.singleOrNull()
            if (activityClass == null) {
                Log.w(TAG, "AutoAcceptFileTransferHook: activity class not found")
                return out
            }

            val vmField: FieldData? = activityClass.fields.firstOrNull { field ->
                isSubclassOf(field.type, "androidx.lifecycle.ViewModel")
            }
            if (vmField == null) {
                Log.w(TAG, "AutoAcceptFileTransferHook: ViewModel field not found")
                return out
            }
            out.addProperty(DexIndexConstants.Keys.VM_FIELD_NAME, vmField.name)
            val vmClass: ClassData = vmField.type
            Log.i(TAG, "AutoAcceptFileTransferHook: vm field = ${vmField.name} / class = ${vmClass.name}")

            // Step B: Find boolean field in ViewModel class (accepted flag)
            val acceptedField: FieldData? = vmClass.fields.firstOrNull { field ->
                field.typeName == "boolean"
            }
            if (acceptedField != null) {
                out.addProperty(DexIndexConstants.Keys.ACCEPTED_FIELD_NAME, acceptedField.name)
                Log.i(TAG, "AutoAcceptFileTransferHook: accepted field = ${acceptedField.name}")
            }

            // Step C: The LiveData field written in onStart is the "user decision" signal;
            // notification shade acceptance path is accepted=true in onStart + postValue(true) on this field.
            val liveDataField: FieldData? = findOnStartLiveDataField(bridge, activityClass, vmClass)
            if (liveDataField == null) {
                Log.w(TAG, "AutoAcceptFileTransferHook: accept LiveData field not found")
                return out
            }
            out.addProperty(DexIndexConstants.Keys.LIVE_DATA_FIELD_NAME, liveDataField.name)
            val liveDataClass: ClassData = liveDataField.type
            Log.i(TAG, "AutoAcceptFileTransferHook: liveData field = ${liveDataField.name} / class = ${liveDataClass.name}")

            // Step D: Find (Object)void method in LiveData inheritance chain (skip constructors)
            val updateMethod: MethodData? = findObjectVoidMethod(liveDataClass)
            if (updateMethod != null) {
                out.addProperty(DexIndexConstants.Keys.LIVE_DATA_UPDATE_METHOD, updateMethod.name)
                Log.i(TAG, "AutoAcceptFileTransferHook: update method = ${updateMethod.name}")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AutoAcceptFileTransferHook: discovery failed", t)
        }
        return out
    }

    /**
     * Locate the "user decision" field among ViewModel LiveData fields referenced in Activity.onStart.
     * onStart is a framework callback (unobfuscatable), where postValue call on this LiveData is
     * the write path for notification shade accept/reject buttons; whereas onCreate observes all LiveData fields
     * and cannot serve as a distinguishing criterion.
     */
    private fun findOnStartLiveDataField(
        bridge: DexKitBridge,
        activityClass: ClassData,
        vmClass: ClassData,
    ): FieldData? {
        val liveDataFields = vmClass.fields.filter { field ->
            isSubclassOf(field.type, "androidx.lifecycle.LiveData")
        }
        if (liveDataFields.isEmpty()) return null
        // When only one candidate exists, no differentiation needed
        if (liveDataFields.size == 1) return liveDataFields.first()
        for (candidate in liveDataFields) {
            val used = bridge.findMethod {
                matcher {
                    name = "onStart"
                    declaredClass = activityClass.name
                    usingFields {
                        add {
                            name = candidate.name
                            declaredClass = vmClass.name
                        }
                    }
                }
            }
            if (used.isNotEmpty()) {
                return candidate
            }
        }
        return null
    }

    /** Check if [cls]'s inheritance chain (including interfaces) contains a superclass of the specified name. */
    private fun isSubclassOf(cls: ClassData, superName: String): Boolean {
        var current: ClassData? = cls
        while (current != null && current.name != "java.lang.Object") {
            if (superName == current.name) return true
            for (iface in current.interfaces) {
                if (superName == iface.name) return true
            }
            current = current.superClass
        }
        return false
    }

    /** Trace up the inheritance chain to find the first method with signature (Object)void (constructors are not update methods, skip). */
    private fun findObjectVoidMethod(cls: ClassData): MethodData? {
        var current: ClassData? = cls
        while (current != null && current.name != "java.lang.Object") {
            for (m in current.methods) {
                if (m.name == "<init>" || m.name == "<clinit>") continue
                val params = m.paramTypeNames
                if (params.size == 1 && params[0] == "java.lang.Object"
                    && m.returnTypeName == "void"
                ) {
                    return m
                }
            }
            current = current.superClass
        }
        return null
    }

    private companion object {
        const val TAG = "MobileDesktopDexIndexer"
    }
}

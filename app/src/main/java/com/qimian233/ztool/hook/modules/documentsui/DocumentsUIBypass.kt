package com.qimian233.ztool.hook.modules.documentsui

import android.annotation.SuppressLint
import android.view.View
import android.widget.Button
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Android Document Picker (DocumentsUI) restriction bypass module.
 * Function: Allows users to select files/folders in restricted directories like /Android/data.
 */
@SuppressLint("PrivateApi")
class DocumentsUIBypass : AppHookModule() {
    override fun getModuleName(): String = PreferenceKeys.DOCUMENTS_UI_BYPASS.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.DOCUMENTS_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        logger.debug("Starting DocumentsUI restriction bypass module...")
        hookDocumentInfo(classLoader)
        hookPickFragment(classLoader)
    }

    /**
     * Hook DocumentInfo class to forcibly remove tree selection restrictions.
     */
    private fun hookDocumentInfo(classLoader: ClassLoader) {
        val documentInfoClass = "com.android.documentsui.base.DocumentInfo"

        try {
            val docInfoClass = classLoader.loadClass(documentInfoClass)

            // Hook isBlockedFromTree method
            val isBlockedFromTreeMethod = docInfoClass.getDeclaredMethod("isBlockedFromTree")
            hookWithId(
                isBlockedFromTreeMethod,
                "is_blocked_from_tree"
            ) { chain ->
                chain.proceed()
                false
            }
            logger.info("Successfully hooked DocumentInfo.isBlockedFromTree")

            // Optional: Try hooking isBlocked method (exists in some models or older versions)
            try {
                val isBlockedMethod = docInfoClass.getDeclaredMethod("isBlocked")
                hookWithId(isBlockedMethod, "is_blocked") { chain ->
                    chain.proceed()
                    false
                }
                logger.info("Successfully hooked DocumentInfo.isBlocked")
            } catch (_: Throwable) {
                // Method might not exist, ignore and do not log as a major error
            }
        } catch (t: Throwable) {
            logger.error("Failed to hook DocumentInfo", t)
        }
    }

    /**
     * Hook PickFragment class to forcibly enable the pick button and hide the overlay.
     */
    private fun hookPickFragment(classLoader: ClassLoader) {
        val pickFragmentClass = "com.android.documentsui.picker.PickFragment"

        try {
            val pickFragClass = classLoader.loadClass(pickFragmentClass)

            // Hook updateView method to force widget state modification after UI updates
            val updateViewMethod = pickFragClass.getDeclaredMethod("updateView")
            hookWithId(updateViewMethod, "update_view") { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject

                // 1. Get and enable mPick button
                try {
                    val mPickField = findField(fragment.javaClass, "mPick") // null-safe
                    val mPick = mPickField.get(fragment)
                    if (mPick is Button) {
                        mPick.isEnabled = true
                    }
                } catch (_: NoSuchFieldError) {
                    // Ignore missing field
                }

                // 2. Get and hide mPickOverlay overlay
                try {
                    val mPickOverlayField =
                        findField(fragment.javaClass, "mPickOverlay") // null-safe
                    val mPickOverlay = mPickOverlayField.get(fragment)
                    if (mPickOverlay is View) {
                        mPickOverlay.visibility = View.GONE // View.GONE = 8
                    }
                } catch (_: NoSuchFieldError) {
                    // Ignore missing field
                }
                result
            }
            logger.info("Successfully hooked PickFragment.updateView")
        } catch (t: Throwable) {
            logger.error("Failed to hook PickFragment", t)
        }
    }
}

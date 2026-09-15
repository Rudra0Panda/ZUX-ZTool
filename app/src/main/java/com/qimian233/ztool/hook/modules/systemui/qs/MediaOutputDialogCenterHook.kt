package com.qimian233.ztool.hook.modules.systemui.qs

import android.annotation.SuppressLint
import android.content.Context
import android.media.session.MediaSessionManager
import android.os.UserHandle
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

/**
 * Media output dialog centering + theme color fix: Fixes two issues when launching MediaOutputDialog
 * from the "Media Output" tile: the window sticking to screen left edge and theme color constantly being default yellow.
 *
 * Root cause (verified on ZUXOS 1.5.04.495 via device test + decompilation):
 * 1. Sticking left: MediaOutputBaseDialog.getGravity() hardcodes returning 19 (LEFT|CENTER_VERTICAL).
 *    Normal path (media card output chip) wraps inside full-screen transparent shell via DialogTransitionAnimator.show(),
 *    where gravity only affects alignment inside the shell; tile path (broadcast -> createAndShow(null,...)) lacks an anchor
 *    Controller, invoking standard dialog.show(), causing the 1180-wide window with LEFT gravity to land directly on screen.
 * 2. Yellow theme: when createAndShow packageName is null, MediaSwitchingController.start()
 *    skips MediaController binding, getHeaderIcon() is always null, and refresh() dynamic color chain via
 *    WallpaperColors.fromBitmap does not trigger, falling back to default legacy colors.
 *    Meanwhile, receiver's LAUNCH_MEDIA_OUTPUT_DIALOG branch proves that when provided a package name
 *    (createAndShow(pkg,false,null,true,...), verified on device), theme colors resolve properly from album art,
 *    and gravity hook takes effect as well.
 *
 * Fix (three hooks controlled together by module toggle):
 * - onReceive: set thread-local flag during LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG (used by gravity);
 * - getGravity: returns 17 (CENTER) when flag is set;
 * - createAndShow: when pkg == null, queries current active media session to inject package name and sets
 *   includePlaybackAndAppMetadata to true, making the tile path equivalent to the "full path with package name".
 *   SystemUI holds MODIFY_AUDIO_ROUTING privilege, allowing full querying of active sessions (same source as its own start()
 *   fallback logic). Keeps original parameters if no session found (empty state dialog).
 */
@SuppressLint("PrivateApi")
class MediaOutputDialogCenterHook : AppHookModule() {

    // Thread-local flag: visible only within main thread call stack, avoiding any cross-thread synchronization
    private val launchViaTileBroadcast = ThreadLocal.withInitial { false }

    override fun getModuleName(): String = PreferenceKeys.MEDIA_OUTPUT_DIALOG_CENTER.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.SYSTEM_UI.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader

        val receiverOk = hookReceiver(classLoader)
        val gravityOk = hookGetGravity(classLoader)
        val pkgOk = hookCreateAndShow(classLoader)

        if (receiverOk && gravityOk && pkgOk) {
            logger.info("MediaOutputDialogCenterHook installed")
        } else {
            logger.warn(
                "MediaOutputDialogCenterHook partial: receiver=$receiverOk " +
                    "gravity=$gravityOk createAndShow=$pkgOk"
            )
        }
    }

    /**
     * Maintain flag before and after onReceive. onReceive is standard BroadcastReceiver override,
     * signature is stable across versions; MediaOutputDialogReceiver is not obfuscated.
     */
    private fun hookReceiver(classLoader: ClassLoader): Boolean {
        return try {
            val receiverClass = classLoader.loadClass(RECEIVER_CLASS)
            val onReceive = findMethod(
                receiverClass, "onReceive",
                Context::class.java, android.content.Intent::class.java
            )
            hookWithId(onReceive, "media_output_dialog_receiver") { chain ->
                launchViaTileBroadcast.set(true)
                try {
                    chain.proceed()
                } finally {
                    launchViaTileBroadcast.set(false)
                }
            }
            true
        } catch (t: Throwable) {
            logger.error("Hook MediaOutputDialogReceiver.onReceive failed", t)
            false
        }
    }

    /**
     * Override gravity. getGravity() is a virtual method of SystemUIDialog and final-overridden
     * in MediaOutputBaseDialog; looked up by explicit signature on subclass.
     */
    private fun hookGetGravity(classLoader: ClassLoader): Boolean {
        return try {
            val dialogClass = classLoader.loadClass(BASE_DIALOG_CLASS)
            val getGravity = findMethod(dialogClass, "getGravity")
            hookWithId(getGravity, "media_output_dialog_gravity") { chain ->
                val isLaunchViaTile: Boolean =
                    launchViaTileBroadcast.get() ?: return@hookWithId chain.proceed()
                if (isLaunchViaTile) {
                    android.view.Gravity.CENTER
                } else {
                    chain.proceed()
                }
            }
            true
        } catch (t: Throwable) {
            logger.error("Hook MediaOutputBaseDialog.getGravity failed", t)
            false
        }
    }

    /**
     * Tile path upgrade: when pkg in createAndShow(pkg, ...) is null, query active media session to supply package name,
     * and enable includePlaybackAndAppMetadata (args[3]) to take the full metadata path.
     * Normal path (chip click carrying package name) and empty state (no active session) retain original parameters.
     */
    private fun hookCreateAndShow(classLoader: ClassLoader): Boolean {
        return try {
            val managerClass = classLoader.loadClass(MANAGER_CLASS)
            val createAndShow = findMethod(
                managerClass, "createAndShow",
                String::class.java,                    // packageName
                Boolean::class.javaPrimitiveType!!,    // aboveStatusBar
                classLoader.loadClass(CONTROLLER_CLASS), // DialogTransitionAnimator.Controller
                Boolean::class.javaPrimitiveType!!,    // includePlaybackAndAppMetadata
                UserHandle::class.java,                // userHandle
                android.media.session.MediaSession.Token::class.java // token
            )
            hookWithId(createAndShow, "media_output_create_and_show") { chain ->
                val args = chain.args
                if (args[0] != null) {
                    return@hookWithId chain.proceed()
                }
                val pkg =
                    findActiveMediaPackage(chain.thisObject) ?: return@hookWithId chain.proceed()
                logger.debug("Inject active media package: $pkg")
                chain.proceed(
                    arrayOf(
                        pkg,
                        args[1],
                        args[2],
                        true,   // includePlaybackAndAppMetadata: enable album art palette and playback metadata
                        args[4],
                        args[5]
                    )
                )
            }
            true
        } catch (t: Throwable) {
            logger.error("Hook MediaOutputDialogManager.createAndShow failed", t)
            false
        }
    }

    /**
     * Query package name of most recent active media session. getActiveSessionsForUser is a hidden API
     * (not present in SDK public layer, but used internally in SystemUI for full query, available in privileged processes),
     * hence invoked reflectively.
     * Does not filter playback state: consistent with normal path semantics - media cards are displayed and clickable
     * even when paused, takes only first session in system list (list is sorted with media button session prioritized).
     *
     * @param manager MediaOutputDialogManager instance (borrow its context to obtain service)
     */
    private fun findActiveMediaPackage(manager: Any?): String? {
        if (manager == null) return null
        return try {
            val contextField = findField(manager.javaClass, "context")
            val context = contextField.get(manager) as Context
            val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val current = android.os.Process.myUserHandle()
            // hidden API：getActiveSessionsForUser(ComponentName, UserHandle)
            val method = MediaSessionManager::class.java.methods.firstOrNull {
                it.name == "getActiveSessionsForUser"
            } ?: run {
                logger.warn("getActiveSessionsForUser not found on this firmware")
                return null
            }
            @Suppress("UNCHECKED_CAST")
            val sessions = method.invoke(sm, null, current) as List<android.media.session.MediaController>
            sessions.firstOrNull()?.packageName
        } catch (t: Throwable) {
            logger.warn("findActiveMediaPackage failed: ${t.message}")
            null
        }
    }

    private companion object {
        const val RECEIVER_CLASS =
            "com.android.systemui.media.dialog.MediaOutputDialogReceiver"
        const val BASE_DIALOG_CLASS =
            "com.android.systemui.media.dialog.MediaOutputBaseDialog"
        const val MANAGER_CLASS =
            "com.android.systemui.media.dialog.MediaOutputDialogManager"
        const val CONTROLLER_CLASS =
            $$"com.android.systemui.animation.DialogTransitionAnimator$Controller"
    }
}

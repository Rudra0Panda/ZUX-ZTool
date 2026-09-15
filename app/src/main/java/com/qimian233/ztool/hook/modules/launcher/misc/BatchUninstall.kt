package com.qimian233.ztool.hook.modules.launcher.misc

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Process
import android.os.UserHandle
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Array as JvmArray
import java.util.Locale

/**
 * Launcher edit mode (multi-selection) batch uninstallation.
 *
 * After `ZuiEditModePanel.onFinishInflate`, clone-injects an "Uninstall apps" button into the bottom bar,
 * and adds the button to the array returned by `getEditModeTranslateAnimViews()` so that it participates
 * in edit mode entry/exit animations along with wallpaper/widget/settings buttons.
 *
 * When clicked, collects selected ItemInfo via public APIs (getSelectedViews / View.tag),
 * filters for the primary user, itemType==APP_ITEM, and non-system apps (LauncherApps resolution + FLAG_SYSTEM check,
 * consistent with launcher's own `Utilities.getUninstallTarget` semantics), deduplicates package names,
 * and passes them via explicit Intent to ZTool's BatchUninstallActivity for Root silent uninstall.
 *
 * Hooked methods are all unobfuscated stable names (framework overrides or descriptive getters),
 * hence does not use DexIndex; gracefully falls back to omitting this button and logging if hooks fail.
 */
@SuppressLint("PrivateApi", "DiscouragedApi", "UseCompatLoadingForDrawables")
class BatchUninstall : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_BATCH_UNINSTALL.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        try {
            val panelClass = classLoader.loadClass(EDIT_MODE_PANEL_CLASS)

            val onFinishInflate = panelClass.getDeclaredMethod("onFinishInflate")
            hookWithId(onFinishInflate, "batch_uninstall_on_finish_inflate") { chain ->
                try {
                    chain.proceed()
                    val panel = chain.thisObject as? ViewGroup
                    if (panel != null) {
                        installButton(panel)
                    }
                } catch (t: Throwable) {
                    logger.error("BatchUninstall: failed to inject edit mode button", t)
                }
                null
            }

            val translateAnimViews = panelClass.getDeclaredMethod("getEditModeTranslateAnimViews")
            hookWithId(translateAnimViews, "batch_uninstall_translate_anim_views") { chain ->
                val original = chain.proceed()
                try {
                    val views = original as? Array<*> ?: return@hookWithId original
                    val panel = chain.thisObject as? View ?: return@hookWithId original
                    val button = panel.findViewWithTag<View>(BUTTON_TAG)
                        ?: return@hookWithId original
                    if (views.contains(button)) return@hookWithId original
                    val componentType = views.javaClass.componentType
                        ?: return@hookWithId original
                    val merged = JvmArray.newInstance(componentType, views.size + 1)
                    System.arraycopy(views, 0, merged, 0, views.size)
                    JvmArray.set(merged, views.size, button)
                    logger.debug("BatchUninstall: button joined edit mode translate anim views")
                    merged
                } catch (t: Throwable) {
                    logger.error("BatchUninstall: failed to attach button to edit mode anim views", t)
                    original
                }
            }

            // Bottom bar is a dual-state layout: shows Wallpaper/Widget/Settings row when unselected,
            // switches to Create Folder/Remove Icon row when selected. Batch uninstall is only shown
            // when there are selected items, syncing visibility with these three public selection-change methods.
            // Note: switchSelectedState returns primitive boolean; hooker must pass through proceed() result,
            // returning null causes NPE on unboxing in the bridge layer.
            val switchSelectedState = panelClass.getDeclaredMethod("switchSelectedState", View::class.java)
            hookWithId(switchSelectedState, "batch_uninstall_switch_selected") { chain ->
                val result = chain.proceed()
                try {
                    syncButtonVisibility(chain.thisObject as? View)
                } catch (t: Throwable) {
                    logger.error("BatchUninstall: failed to sync after switchSelectedState", t)
                }
                result
            }

            val clearSelectedItems = panelClass.getDeclaredMethod("clearSelectedItems")
            hookWithId(clearSelectedItems, "batch_uninstall_clear_selected") { chain ->
                val result = chain.proceed()
                try {
                    syncButtonVisibility(chain.thisObject as? View)
                } catch (t: Throwable) {
                    logger.error("BatchUninstall: failed to sync after clearSelectedItems", t)
                }
                result
            }

            val initSelectedItemByIds = panelClass.getDeclaredMethod(
                "initSelectedItemByIds",
                ArrayList::class.java
            )
            hookWithId(initSelectedItemByIds, "batch_uninstall_init_selected") { chain ->
                val result = chain.proceed()
                try {
                    syncButtonVisibility(chain.thisObject as? View)
                } catch (t: Throwable) {
                    logger.error("BatchUninstall: failed to sync after initSelectedItemByIds", t)
                }
                result
            }

            logger.info("BatchUninstall hooks installed")
        } catch (t: Throwable) {
            logger.error("Failed to install batch uninstall hooks", t)
        }
    }

    // ── Button Injection ────────────────────────────────────────────────

    private fun installButton(panel: ViewGroup) {
        if (panel.findViewWithTag<View>(BUTTON_TAG) != null) return
        val context = panel.context
        val resources = context.resources
        val packageName = context.packageName

        // Tested on ZUI tablet 18.1.9: bottom_panel contains two RelativeLayout containers:
        // drop_combine_folder (icon + text, statically visible) and drop_remove_icon_container
        // (GONE when idle, "Remove" target appears only when dragging). The button must attach to bottom_panel,
        // inserted between the two containers, never inside remove container, to avoid overlapping drag state.
        val combineContainer = findViewByIdOrNull(panel, resources, packageName, "drop_combine_folder_container")
        val combineText = findViewByIdOrNull(panel, resources, packageName, "drop_combine_folder") as? TextView
        val removeTarget = findViewByIdOrNull(panel, resources, packageName, "drop_remove_icon")
        val removeContainer = findViewByIdOrNull(panel, resources, packageName, "drop_remove_icon_container")
            ?: (removeTarget?.parent as? View)

        val styleSource = combineText ?: (removeTarget as? TextView)
        val anchor = removeContainer ?: removeTarget
        if (styleSource == null || anchor == null) {
            logger.warn("BatchUninstall: edit mode bottom anchors not found, button skipped")
            return
        }

        val button = TextView(context)
        button.tag = BUTTON_TAG
        button.id = View.generateViewId()
        button.text = moduleString(context, STRING_BUTTON, FALLBACK_BUTTON)
        button.isAllCaps = false
        copyStyle(button, styleSource)
        applyIcon(button, styleSource, removeTarget, resources, packageName)

        val layoutParams = buildLayoutParams(button.id, anchor, combineContainer)
        if (layoutParams == null) {
            logger.warn("BatchUninstall: cannot build layout params, button skipped")
            return
        }
        // Edit mode visibility is driven by panel alpha and translationY animations; row views never call
        // setVisibility, so visibility only toggles between VISIBLE/GONE (tracking selection count)
        button.visibility = View.GONE
        syncButtonVisibility(panel)

        (anchor.parent as? ViewGroup ?: panel).addView(button, layoutParams)
        button.setOnClickListener { handleClicked(panel) }
        logger.info(
            "BatchUninstall: edit mode button injected into " +
                anchor.parent.javaClass.name
        )
    }

    private fun copyStyle(button: TextView, styleSource: TextView) {
        runCatching {
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, styleSource.textSize)
            styleSource.textColors?.let { button.setTextColor(it) }
            button.gravity = styleSource.gravity
            button.setPadding(
                styleSource.paddingLeft,
                styleSource.paddingTop,
                styleSource.paddingRight,
                styleSource.paddingBottom
            )
            styleSource.background?.constantState?.newDrawable()?.let { button.background = it }
            button.compoundDrawablePadding = styleSource.compoundDrawablePadding
            button.typeface = styleSource.typeface
            button.includeFontPadding = styleSource.includeFontPadding
        }.onFailure { logger.warn("BatchUninstall: failed to copy button style: " + it.message) }
    }

    /**
     * Places icon following the style template's drawable direction, prioritizing assets by:
     * 1. drop_remove_icon composite icon - delete icon in the edit mode design system
     *    (circular background + glyph, same spec as create folder); GONE when idle,
     *    but XML-configured drawable still exists;
     * 2. Self-drawn composite icon: translucent circle background + ic_delete_zui centered & scaled,
     *    sized referencing drop_combine_folder measured intrinsic;
     * 3. Finally falls back to ic_delete_zui intrinsic (legacy small icon behavior).
     */
    private fun applyIcon(
        button: TextView,
        styleSource: TextView,
        removeTarget: View?,
        resources: Resources,
        packageName: String
    ) {
        val templates = styleSource.compoundDrawablesRelative
        var index = templates.indexOfFirst { it != null }
        if (index < 0) index = 1 // Default to top when template has no icon, consistent with bottom bar icon buttons

        var icon: Drawable? = null
        var source = "none"
        val removeDrawable = (removeTarget as? TextView)
            ?.compoundDrawablesRelative
            ?.getOrNull(index)
        if (removeDrawable != null) {
            icon = removeDrawable.constantState?.newDrawable()
            source = "remove_target"
        }
        if (icon == null) {
            icon = buildCompositeIcon(button.context, templates.getOrNull(index), resources, packageName)
            if (icon != null) source = "composite"
        }
        if (icon == null) {
            val deleteId = resources.getIdentifier("ic_delete_zui", "drawable", packageName)
            if (deleteId != 0) {
                icon = runCatching { resources.getDrawable(deleteId, button.context.theme) }.getOrNull()
                source = "ic_delete_zui"
            }
        }
        if (icon == null) {
            logger.warn("BatchUninstall: no icon drawable available")
            return
        }
        val arranged = arrayOfNulls<Drawable>(4)
        arranged[index] = icon
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(
            arranged[0], arranged[1], arranged[2], arranged[3]
        )
        logger.info(
            "BatchUninstall: icon from $source " +
                "intrinsic=${icon.intrinsicWidth}x${icon.intrinsicHeight} " +
                "(template=${templates.getOrNull(index)?.intrinsicWidth}x" +
                "${templates.getOrNull(index)?.intrinsicHeight})"
        )
    }

    /** Fallback: translucent circular background + centered scaled delete glyph, sized referencing template icon intrinsic. */
    private fun buildCompositeIcon(
        context: Context,
        sizeReference: Drawable?,
        resources: Resources,
        packageName: String
    ): Drawable? {
        val deleteId = resources.getIdentifier("ic_delete_zui", "drawable", packageName)
        val glyph = if (deleteId != 0) {
            runCatching { resources.getDrawable(deleteId, context.theme) }.getOrNull()
        } else null
        val scrim = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x66000000)
        }
        val size = sizeReference?.intrinsicWidth?.takeIf { it > 0 }
            ?: android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP,
                60f,
                resources.displayMetrics
            ).toInt()
        val inset = (size * 0.28f).toInt()
        return if (glyph != null) {
            object : LayerDrawable(arrayOf(scrim, glyph)) {
                override fun getIntrinsicWidth(): Int = size
                override fun getIntrinsicHeight(): Int = size
            }.apply { setLayerInset(1, inset, inset, inset, inset) }
        } else {
            object : LayerDrawable(arrayOf(scrim)) {
                override fun getIntrinsicWidth(): Int = size
                override fun getIntrinsicHeight(): Int = size
            }
        }
    }

    private fun findViewByIdOrNull(panel: View, resources: Resources, packageName: String, name: String): View? {
        val id = resources.getIdentifier(name, "id", packageName)
        return if (id != 0) panel.findViewById(id) else null
    }

    /**
     * Show button only when there are selected items (only toggles between VISIBLE/GONE, not participating in panel anim),
     * arranging "Create folder" container and the button into a two-slot layout symmetrical around screen center axis.
     *
     * bottom_panel is a custom ViewGroup (child LayoutParams is private type, cannot be positioned using rules/anchors),
     * so a type-agnostic translation approach is used: read both current layout centers,
     * translate each along X to 1600±(panel width*0.086) (locally ±275px, corresponding to half-slot distance in 3-slot layout).
     * translationX does not interfere with entry/exit animation translationY; resets container offset in unselected state.
     */
    private fun syncButtonVisibility(panel: View?) {
        val button = panel?.findViewWithTag<View>(BUTTON_TAG) ?: return
        try {
            val count = panel.javaClass.getMethod("getSelectedCount").invoke(panel) as? Int ?: 0
            val visible = count > 0
            if (button.visibility != (if (visible) View.VISIBLE else View.GONE)) {
                button.visibility = if (visible) View.VISIBLE else View.GONE
                logger.debug("BatchUninstall: button visibility -> $visible (selected=$count)")
            }
            val context = panel.context
            val combine = findViewByIdOrNull(
                panel, context.resources, context.packageName, "drop_combine_folder_container"
            ) ?: return
            if (panel.width == 0 || combine.width == 0) return
            if (visible) {
                val centerX = panel.width / 2f
                val slotOffset = panel.width * SLOT_OFFSET_RATIO
                // Create folder container natively centered in the middle slot
                combine.translationX =
                    (centerX - slotOffset) - (combine.left + combine.width / 2f)
                // Button layout position is determined by cloned params (right slot); zero out and apply symmetrical offset after layout finishes
                button.translationX = 0f
                button.post {
                    button.translationX =
                        (centerX + slotOffset) - (button.left + button.width / 2f)
                }
            } else {
                combine.translationX = 0f
                button.translationX = 0f
            }
        } catch (t: Throwable) {
            logger.warn("BatchUninstall: failed to sync button visibility: " + t.message)
        }
    }

    private fun cloneLayoutParams(source: ViewGroup.LayoutParams?): ViewGroup.LayoutParams? {
        if (source == null) return null
        return try {
            val constructor = source.javaClass.getConstructor(source.javaClass)
            constructor.newInstance(source) as ViewGroup.LayoutParams
        } catch (t: Throwable) {
            logger.warn("BatchUninstall: no copy constructor for ${source.javaClass.name}: " + t.message)
            null
        }
    }

    /**
     * Build button layout params: ConstraintLayout host anchors between combine container and remove anchor;
     * RelativeLayout host (tested on ZUI tablet bottom_panel) centers vertically and places left of remove container;
     * other layouts retain cloned values.
     */
    private fun buildLayoutParams(
        buttonId: Int,
        anchor: View,
        combineContainer: View?
    ): ViewGroup.LayoutParams? {
        val source = anchor.layoutParams ?: return null
        if (source.javaClass.name == CONSTRAINT_LAYOUT_LP) {
            val layoutParams = cloneLayoutParams(source) ?: return null
            adjustConstraintLayoutParams(layoutParams, anchor, combineContainer, buttonId)
            layoutParams.width = ViewGroup.LayoutParams.WRAP_CONTENT
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            return layoutParams
        }
        if (source is RelativeLayout.LayoutParams) {
            // Layout position uses center slot; visual symmetry is handled by translationX in syncButtonVisibility
            val layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE)
            return layoutParams
        }
        return cloneLayoutParams(source)
    }

    private fun adjustConstraintLayoutParams(
        layoutParams: ViewGroup.LayoutParams,
        anchor: View,
        combineContainer: View?,
        buttonId: Int
    ) {
        try {
            val lpClass = layoutParams.javaClass
            val unset = lpClass.getField("UNSET").getInt(null)
            val source = anchor.layoutParams
            val sourceClass = source.javaClass

            for (field in listOf("topToTop", "topToBottom", "bottomToTop", "bottomToBottom")) {
                lpClass.getField(field).setInt(layoutParams, sourceClass.getField(field).getInt(source))
            }
            lpClass.getField("verticalBias").setFloat(
                layoutParams,
                sourceClass.getField("verticalBias").getFloat(source)
            )
            lpClass.getField("startToStart").setInt(layoutParams, unset)
            lpClass.getField("endToEnd").setInt(layoutParams, unset)

            if (combineContainer != null && combineContainer !== anchor) {
                lpClass.getField("startToEnd").setInt(layoutParams, combineContainer.id)
                lpClass.getField("endToStart").setInt(layoutParams, anchor.id)
                val combineLp = combineContainer.layoutParams
                if (combineLp.javaClass == lpClass &&
                    combineLp.javaClass.getField("endToStart").getInt(combineLp) == anchor.id
                ) {
                    combineLp.javaClass.getField("endToStart").setInt(combineLp, buttonId)
                    combineContainer.layoutParams = combineLp
                }
            } else {
                lpClass.getField("endToStart").setInt(layoutParams, anchor.id)
            }
        } catch (t: Throwable) {
            logger.warn("BatchUninstall: failed to adjust constraint params: " + t.message)
        }
    }

    // ── Click Dispatch ────────────────────────────────────────────────

    /** One uninstallation candidate: display app name + execution package name. */
    private data class UninstallCandidate(val label: String, val packageName: String)

    private fun handleClicked(panel: ViewGroup) {
        try {
            val context = panel.context
            val candidates = collectUninstallableCandidates(panel, context)
            if (candidates.isEmpty()) {
                Toast.makeText(
                    context,
                    moduleString(context, STRING_NO_APPS, FALLBACK_NO_APPS),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            if (!showConfirmDialog(panel, context, candidates)) {
                logger.warn("BatchUninstall: no confirm dialog available, aborted")
                return
            }
        } catch (t: Throwable) {
            logger.error("BatchUninstall: failed to show confirm dialog", t)
        }
    }

    /**
     * Displays ZUI-style confirmation dialog; falls back to standard AlertDialog if MessageDialog reflection fails;
     * returns false if both fail (prefer aborting rather than uninstalling without confirmation).
     */
    private fun showConfirmDialog(
        panel: ViewGroup,
        context: Context,
        candidates: List<UninstallCandidate>
    ): Boolean {
        // Display app name, fallback to package name if absent
        val labelList = candidates.joinToString(separator = "\n") {
            "- " + it.label.ifBlank { it.packageName }
        }
        val message = String.format(
            Locale.getDefault(),
            moduleString(context, STRING_DIALOG_MESSAGE, FALLBACK_DIALOG_MESSAGE),
            candidates.size,
            labelList
        )
        val title = moduleString(context, STRING_DIALOG_TITLE, FALLBACK_DIALOG_TITLE)
        val packages = candidates.map { it.packageName }
        val onConfirm = Runnable { dispatchBatchUninstall(panel, context, packages) }
        if (showZuiConfirmDialog(context, title, message, onConfirm)) {
            logger.debug("BatchUninstall: ZUI MessageDialog shown")
            return true
        }
        if (showFallbackConfirmDialog(context, title, message, onConfirm)) {
            logger.info("BatchUninstall: fallback AlertDialog shown")
            return true
        }
        return false
    }

    /**
     * Reflectively calls launcher's built-in zui.app.MessageDialog, usage referencing
     * EditModeRemoveDropTarget.m() (Builder chain + window type 2038).
     */
    private fun showZuiConfirmDialog(
        launcher: Context,
        title: CharSequence,
        message: CharSequence,
        onConfirm: Runnable
    ): Boolean {
        return try {
            val loader = launcher.classLoader
            val dialogClass = Class.forName("zui.app.MessageDialog", true, loader)
            val builderClass = Class.forName($$"zui.app.MessageDialog$Builder", true, loader)
            val builder = builderClass.getConstructor(Context::class.java).newInstance(launcher)
            val resources = launcher.resources
            val packageName = launcher.packageName

            val cancelListener = DialogInterface.OnCancelListener { }
            // Cancel and Confirm must be two separate listeners: sharing them would cause cancel click to also trigger uninstall
            val negativeListener = DialogInterface.OnClickListener { dialog, _ -> dialog.dismiss() }
            val positiveListener = DialogInterface.OnClickListener { _, _ -> onConfirm.run() }

            builderClass.getMethod("setCancelable", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
            builderClass.getMethod("setOnCancelListener", DialogInterface.OnCancelListener::class.java)
                .invoke(builder, cancelListener)
            try {
                builderClass.getMethod("setTitle", CharSequence::class.java).invoke(builder, title)
            } catch (_: Throwable) {
                val resId = resources.getIdentifier("uninstall_item_title", "string", packageName)
                builderClass.getMethod("setTitle", Int::class.javaPrimitiveType).invoke(builder, resId)
            }
            try {
                builderClass.getMethod("setMessageDialogType", Int::class.javaPrimitiveType)
                    .invoke(builder, 0)
            } catch (_: Throwable) {
                // Older versions may not have this option; ignore
            }
            setDialogButton(builderClass, builder, "setNegativeButton", resources, packageName,
                "cancel_action", android.R.string.cancel, negativeListener)
            setDialogButton(builderClass, builder, "setPositiveButton", resources, packageName,
                "uninstall_item_title", 0, positiveListener)

            val dialog = builderClass.getMethod("create").invoke(builder) as Dialog
            // Builder does not have setMessage (referencing EditModeRemoveDropTarget usage);
            // must call MessageDialog.setMessage after create, title would swallow line breaks
            dialogClass.getMethod("setMessage", CharSequence::class.java).invoke(dialog, message)
            runCatching {
                // Long lists (up to 24 items) require lifting height restrictions
                dialogClass.getMethod("disableHeightRestrictions", Boolean::class.javaPrimitiveType)
                    .invoke(dialog, true)
            }
            dialog.setCanceledOnTouchOutside(true)
            runCatching { dialog.window?.setType(DIALOG_WINDOW_TYPE) }
            dialog.show()
            true
        } catch (t: Throwable) {
            logger.warn("BatchUninstall: MessageDialog reflection failed: " + t.message)
            false
        }
    }

    /** Prefer (int resId, listener) parameters, fallback to (CharSequence, listener) on failure. */
    private fun setDialogButton(
        builderClass: Class<*>,
        builder: Any,
        methodName: String,
        resources: Resources,
        packageName: String,
        resName: String,
        fallbackResId: Int,
        clickListener: DialogInterface.OnClickListener
    ) {
        val resId = resources.getIdentifier(resName, "string", packageName)
            .takeIf { it != 0 } ?: fallbackResId
        try {
            builderClass.getMethod(
                methodName,
                Int::class.javaPrimitiveType,
                DialogInterface.OnClickListener::class.java
            ).invoke(builder, resId, clickListener)
        } catch (_: Throwable) {
            val text = if (resId != 0) {
                runCatching { resources.getString(resId) }.getOrDefault("")
            } else ""
            builderClass.getMethod(
                methodName,
                CharSequence::class.java,
                DialogInterface.OnClickListener::class.java
            ).invoke(builder, text, clickListener)
        }
    }

    private fun showFallbackConfirmDialog(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        onConfirm: Runnable
    ): Boolean {
        return try {
            val dialog = AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(true)
                .setNegativeButton(android.R.string.cancel) { d, _ -> d.dismiss() }
                .setPositiveButton(android.R.string.ok) { d, _ ->
                    d.dismiss()
                    onConfirm.run()
                }
                .create()
            dialog.setCanceledOnTouchOutside(true)
            runCatching { dialog.window?.setType(DIALOG_WINDOW_TYPE) }
            dialog.show()
            true
        } catch (t: Throwable) {
            logger.warn("BatchUninstall: AlertDialog fallback failed: " + t.message)
            false
        }
    }

    private fun dispatchBatchUninstall(panel: ViewGroup, context: Context, packages: List<String>) {
        try {
            val intent = Intent()
            intent.setClassName(MODULE_PACKAGE, ACTIVITY_CLASS)
            intent.putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(packages))
            // Authentication credential: created as PendingIntent using launcher's identity and attached to Intent.
            // Creator is bound by the system (creatorPackage=launcher), third parties can neither forge creator nor obtain
            // instances created by launcher. callingPackage returns null across tasks on some ROMs,
            // and referrer extra can be forged; PendingIntent is a reliable and unforgeable credential.
            val proof = PendingIntent.getActivity(
                context,
                0,
                Intent(AUTH_PROOF_ACTION).setPackage(MODULE_PACKAGE),
                PendingIntent.FLAG_IMMUTABLE
            )
            intent.putExtra(EXTRA_PROOF, proof)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            val activity = context as? Activity
            if (activity == null) {
                logger.error("BatchUninstall: context is not an activity, dispatch aborted")
                Toast.makeText(
                    context,
                    moduleString(context, STRING_DISPATCH_FAILED, FALLBACK_DISPATCH_FAILED),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            activity.startActivityForResult(intent, DISPATCH_REQUEST_CODE)
            // Clear selection after dispatch to prevent stale selected items remaining in edit mode after uninstall completes
            runCatching {
                panel.javaClass.getMethod("clearSelectedItems").invoke(panel)
            }.onFailure { logger.warn("BatchUninstall: clearSelectedItems failed: " + it.message) }
        } catch (t: Throwable) {
            logger.error("BatchUninstall: failed to dispatch batch uninstall", t)
            Toast.makeText(
                context,
                moduleString(context, STRING_DISPATCH_FAILED, FALLBACK_DISPATCH_FAILED),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Collect uninstallable candidates from selected views. Only retains:
     * itemInfo.itemType == 0 (app icon), primary user, resolvable by LauncherApps and non-system app;
     * deduplicated by package name, app name taken from ItemInfo.title, falling back to package name.
     */
    private fun collectUninstallableCandidates(panel: View, context: Context): List<UninstallCandidate> {
        val selectedViews = try {
            val method = panel.javaClass.getMethod("getSelectedViews")
            @Suppress("UNCHECKED_CAST")
            method.invoke(panel) as? List<View>
        } catch (t: Throwable) {
            logger.error("BatchUninstall: getSelectedViews failed", t)
            null
        } ?: return emptyList()
        if (selectedViews.isEmpty()) return emptyList()

        val classLoader = panel.javaClass.classLoader ?: return emptyList()
        val itemInfoClass = try {
            Class.forName("com.android.launcher3.model.data.ItemInfo", true, classLoader)
        } catch (t: Throwable) {
            logger.error("BatchUninstall: ItemInfo class not found", t)
            return emptyList()
        }
        val itemTypeField = itemInfoClass.getField("itemType")
        val userField = itemInfoClass.getField("user")
        val titleField = itemInfoClass.getField("title")
        val targetComponentMethod = itemInfoClass.getMethod("getTargetComponent")
        val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        val myUser = Process.myUserHandle()

        val candidates = LinkedHashMap<String, UninstallCandidate>()
        for (view in selectedViews) {
            try {
                val info = view.tag ?: continue
                if (!itemInfoClass.isInstance(info)) continue
                if (itemTypeField.getInt(info) != ITEM_TYPE_APPLICATION) continue
                val user = userField.get(info) as? UserHandle ?: continue
                if (user != myUser) continue
                val component = targetComponentMethod.invoke(info) as? ComponentName ?: continue
                val packageName = component.packageName
                if (packageName.isEmpty()) continue
                if (!isUninstallable(launcherApps, component, user)) continue
                if (candidates.containsKey(packageName)) continue
                val label = (titleField.get(info) as? CharSequence)?.toString().orEmpty()
                candidates[packageName] = UninstallCandidate(label, packageName)
            } catch (t: Throwable) {
                logger.warn("BatchUninstall: failed to inspect selected view: " + t.message)
            }
        }
        logger.info("BatchUninstall: ${candidates.size} uninstallable package(s) collected")
        return candidates.values.toList()
    }

    private fun isUninstallable(launcherApps: LauncherApps?, component: ComponentName, user: UserHandle): Boolean {
        if (launcherApps == null) return true
        return try {
            val activity = launcherApps.resolveActivity(Intent().setComponent(component), user)
            val flags = activity?.applicationInfo?.flags ?: return false
            flags and ApplicationInfo.FLAG_SYSTEM == 0
        } catch (_: Throwable) {
            false
        }
    }

    // ── Module resources (reuses RecentTaskMemoryViewHook i18n approach) ──

    private fun moduleString(hostContext: Context, resourceName: String, fallback: String): String {
        return try {
            val moduleContext = hostContext.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val resId = moduleContext.resources.getIdentifier(resourceName, "string", MODULE_PACKAGE)
            if (resId != 0) moduleContext.resources.getString(resId) else fallback
        } catch (t: Throwable) {
            logger.warn("BatchUninstall: failed to load module string $resourceName: " + t.message)
            fallback
        }
    }

    companion object {
        private const val MODULE_PACKAGE = "com.qimian233.ztool"
        private const val EDIT_MODE_PANEL_CLASS = "com.zui.launcher.uiextend.ZuiEditModePanel"
        private const val ACTIVITY_CLASS = "com.qimian233.ztool.uninstall.BatchUninstallActivity"
        private const val EXTRA_PACKAGES = "ztool_extra_batch_uninstall_packages"
        private const val EXTRA_PROOF = "ztool_extra_batch_uninstall_proof"
        private const val AUTH_PROOF_ACTION = "com.qimian233.ztool.action.BATCH_UNINSTALL_PROOF"
        private const val DISPATCH_REQUEST_CODE = 21001
        private const val BUTTON_TAG = "ztool_edit_mode_batch_uninstall"
        private const val CONSTRAINT_LAYOUT_LP =
            $$"androidx.constraintlayout.widget.ConstraintLayout$LayoutParams"
        private const val ITEM_TYPE_APPLICATION = 0

        /** Ratio of slot distance to panel width in bottom bar 3-slot layout (tested 550/3200). */
        private const val SLOT_OFFSET_RATIO = 0.086f

        private const val STRING_BUTTON = "ztool_batch_uninstall_button"
        private const val STRING_NO_APPS = "ztool_batch_uninstall_no_apps"
        private const val STRING_DIALOG_TITLE = "ztool_batch_uninstall_dialog_title"
        private const val STRING_DIALOG_MESSAGE = "ztool_batch_uninstall_dialog_message"
        private const val STRING_DISPATCH_FAILED = "ztool_batch_uninstall_dispatch_failed"

        /** Consistent with EditModeRemoveDropTarget confirmation dialog: TYPE_APPLICATION_OVERLAY. */
        private const val DIALOG_WINDOW_TYPE = 2038

        private val FALLBACK_BUTTON: String
            get() = if (Locale.getDefault().language == "zh") "卸载应用" else "Uninstall"
        private val FALLBACK_NO_APPS: String
            get() = if (Locale.getDefault().language == "zh") "所选中内容没有可卸载的应用" else "No uninstallable apps in the selection"
        private val FALLBACK_DIALOG_TITLE: String
            get() = if (Locale.getDefault().language == "zh") "批量卸载" else "Batch Uninstall"
        private val FALLBACK_DIALOG_MESSAGE: String
            get() = if (Locale.getDefault().language == "zh") {
                $$"将通过 Root 权限静默卸载以下 %1$d 个应用，桌面图标会在卸载后自动移除：\n\n%2$s"
            } else {
                $$"The following %1$d app(s) will be silently uninstalled with Root permission. Their home screen icons will be removed automatically:\n\n%2$s"
            }
        private val FALLBACK_DISPATCH_FAILED: String
            get() = if (Locale.getDefault().language == "zh") "无法打开 ZTool 执行卸载" else "Failed to open ZTool for uninstall"
    }
}

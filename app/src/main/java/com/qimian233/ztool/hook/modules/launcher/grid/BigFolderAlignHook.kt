package com.qimian233.ztool.hook.modules.launcher.grid

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

/**
 * 大文件夹图标与背景几何对齐（com.zui.launcher）。
 * Big folder icon and background geometry alignment (com.zui.launcher).
 *
 * 修的问题：
 * - 背景: PreviewBackground 按"名义格宽 x span - widgetPadding.left*2"画背景, 与邻居
 *   图标的图标体系内缩互不换算, 横向偏宽, 垂直/图形边缘也不对齐。
 * - 子网格: BigFolderConfig 样式表按 stock 行列数调校, 改变行列数后 stock 间距过大致图标贴边。
 * - 标签: FolderIcon.z() 按名义格宽推导 topMargin, 与邻居标签存在偏差。
 * Problems addressed:
 * - Background: PreviewBackground draws background by "nominal cell width x span - widgetPadding.left*2", which does not
 *   convert to/from neighboring icons' inner padding, resulting in excess horizontal width and misaligned vertical/glyph edges.
 * - Sub-grid: BigFolderConfig stylesheet is tuned to stock rows/cols; changing rows/cols makes stock spacing too large, pushing icons to borders.
 * - Label: FolderIcon.z() derives topMargin based on nominal cell width, causing deviations with neighboring labels.
 *
 * 最终改写逻辑（公式中的量来自 [readGridMetrics] 的实测派生）：
 * 1. PreviewBackground.setup 尾部（大文件夹分支）:
 *    宽 = spanX*cellWidth + (spanX-1)*gapX - 2*图标内缩, offsetX = 图标内缩;
 *    offsetY = rowInset + artInset, previewSizeY = (spanY-1)*行距 + rowInset + iconSize
 *    - artInset —— 背景上下边对齐上/下排图标的图形边缘。
 *    rowInset = (cellHeight - cellHeightPx)/2（cellYPaddingPx 实测未初始化, 不可用）;
 *    artInset = iconSize * [ART_INSET_RATIO]（图标盒内图形的透明边距）。
 * 2. computeBigFolderAvaliableWh 宽度同步; isUpdatePreviewSize 只读观测。
 * 3. getBigFolderIconChildCount: (2,2) → 每行 CHILD_COLS 个 x CHILD_ROWS 行;
 *    其它 spanY==2 且 stock 行数为 2 → 行数改 3、列数沿用 stock; stock 网格从带数组
 *    参数的调用学习（部分调用传 null 数组时查缓存）; spanX==1 窄胶囊不改写。
 * 4. getBigFolderIconHGap/VGap: 大文件夹 span 按 (bg*GRID_OCCUPANCY - n*childSize)/(n-1)
 *    重算, childSize = folderIconSizePx * CHILD_ICON_SCALE（实测 158*0.8235≈130）。
 * 5. ClippedFolderIconLayoutRule.c 尾部统一走 rule 自身 getOffsetX/getOffsetY 通用网格
 *    （手机分支原为硬编码 2 列定位, 与样式表列数不匹配）。
 * 6. FolderIcon.z 整体替换: 大文件夹标签 topMargin = (spanY-1)*行距 + rowInset + iconSize
 *    + drawablePadding, 与最后一行邻居标签同高; 小文件夹分支复刻 stock 公式。
 * Final rewrite logic (quantities in formulas derived from measurements in [readGridMetrics]):
 * 1. PreviewBackground.setup tail (big folder branch):
 *    width = spanX*cellWidth + (spanX-1)*gapX - 2*iconInset, offsetX = iconInset;
 *    offsetY = rowInset + artInset, previewSizeY = (spanY-1)*rowSpacing + rowInset + iconSize
 *    - artInset - background top/bottom edges align with top/bottom icon glyph edges.
 *    rowInset = (cellHeight - cellHeightPx)/2 (cellYPaddingPx is uninitialized and unusable in practice);
 *    artInset = iconSize * [ART_INSET_RATIO] (transparent margin of glyph within icon box).
 * 2. computeBigFolderAvaliableWh width sync; isUpdatePreviewSize read-only observation.
 * 3. getBigFolderIconChildCount: (2,2) -> CHILD_COLS per row x CHILD_ROWS rows;
 *    other spanY==2 and stock row count is 2 -> change rows to 3, keep stock cols; stock grid is learned from calls
 *    with array parameters (some calls query cache when passing null array); spanX==1 narrow capsule is not rewritten.
 * 4. getBigFolderIconHGap/VGap: big folder span recalculated via (bg*GRID_OCCUPANCY - n*childSize)/(n-1),
 *    childSize = folderIconSizePx * CHILD_ICON_SCALE (tested 158*0.8235≈130).
 * 5. ClippedFolderIconLayoutRule.c tail uniformly delegates to rule's own getOffsetX/getOffsetY general grid
 *    (phone branch was hardcoded to 2-column positioning, which does not match stylesheet column count).
 * 6. FolderIcon.z complete replacement: big folder label topMargin = (spanY-1)*rowSpacing + rowInset + iconSize
 *    + drawablePadding, level with last-row neighbor labels; small folder branch replicates stock formula.
 *
 * 宿主混淆短名映射（JADX 因与根包名冲突显示为 fXXXXa 等）：
 * - PreviewBackground: o=背景宽, p=offsetX, q=offsetY; spanX/spanY/previewSizeY 为原名
 * - ClippedFolderIconLayoutRule: b=背景宽, i=背景高, d=图标尺寸, j=列数, g=行数
 * Host obfuscated short name mappings (shown as fXXXXa etc. in JADX due to root package name collision):
 * - PreviewBackground: o=bgWidth, p=offsetX, q=offsetY; spanX/spanY/previewSizeY retain original names
 * - ClippedFolderIconLayoutRule: b=bgWidth, i=bgHeight, d=iconSize, j=cols, g=rows
 * - FolderIcon: b=ActivityContext
 *
 * 全部反射句柄在安装期解析（[resolveCoreRefs] 等分组失败时仅跳过对应 Hook）,
 * 运行期只做字段/方法调用; 遥测日志统一走 debug, 开启详细日志才输出。
 * All reflection handles resolved at install time (failing a group like [resolveCoreRefs] only skips the corresponding Hook),
 * runtime only performs field/method invocations; telemetry logs uniformly use debug level, only output when verbose logging is enabled.
 */
@SuppressLint("PrivateApi")
class BigFolderAlignHook : AppHookModule() {

    override fun getModuleName(): String = PreferenceKeys.LAUNCHER_BIG_FOLDER_ALIGN.name

    override fun getTargetPackages(): Array<String> = arrayOf(ScopeKeys.LAUNCHER.packageName)

    /** Horizontal alignment target: true = small folder background circle (folderIconSizePx), false = app icon (iconSizePx). */
    private val alignToSmallFolder = true

    // ── Reflection handles resolved at install time (skipped on group resolution failure) ──

    private var coreReady = false
    private var ruleReady = false
    private var bfcReady = false
    private var folderIconReady = false

    // PreviewBackground: o=bgWidth p=offsetX q=offsetY
    private lateinit var pbWidth: Field
    private lateinit var pbOffsetX: Field
    private lateinit var pbOffsetY: Field
    private lateinit var pbPreviewSizeY: Field
    private lateinit var pbSpanX: Field
    private lateinit var pbSpanY: Field
    private lateinit var pbSetup: Method
    private lateinit var pbComputeWh: Method
    private lateinit var pbIsUpdate: Method

    // ActivityContext / DeviceProfile / InvariantDeviceProfile
    private lateinit var activityContextGetDeviceProfile: Method
    private lateinit var dpInv: Field
    private lateinit var invNumColumns: Field
    private lateinit var invNumRows: Field
    private lateinit var dpPadding: Field
    private lateinit var dpBorderSpace: Field
    private lateinit var dpWidgetPadding: Field
    private lateinit var dpIconSize: Field
    private lateinit var dpFolderIconSize: Field
    private lateinit var dpIconDrawablePadding: Field
    private lateinit var dpFolderIconOffsetY: Field
    private lateinit var dpCellHeight: Field
    private lateinit var dpGetCellLayoutWidth: Method
    private lateinit var dpGetCellLayoutHeight: Method
    private lateinit var dpUpdateIconSize: Method

    // BigFolderConfig
    private lateinit var bfcChildIconScale: Field
    private lateinit var bfcGetChildCount: Method
    private lateinit var bfcGetHGap: Method
    private lateinit var bfcGetVGap: Method

    // ClippedFolderIconLayoutRule: b=bgWidth i=bgHeight d=iconSize j=cols g=rows
    private lateinit var ruleCols: Field
    private lateinit var ruleRows: Field
    private lateinit var ruleIconSize: Field
    private lateinit var ruleBgWidth: Field
    private lateinit var ruleBgHeight: Field
    private lateinit var ruleC: Method
    private lateinit var ruleGetOffsetX: Method
    private lateinit var ruleGetOffsetY: Method
    private lateinit var ruleScaleForItem: Method

    // FolderIcon: b=ActivityContext; label field name drifted, resolved via candidate names + type scanning
    private var folderLabel: Field? = null
    private lateinit var fiActivityContext: Field
    private lateinit var fiInfo: Field
    private lateinit var fiSpanX: Field
    private lateinit var fiSpanY: Field
    private lateinit var fiZ: Method

    @Throws(Throwable::class)
    override fun handleLoadPackage(param: PackageLoadedParam) {
        val classLoader = param.defaultClassLoader
        logger.debug("BigFolderAlign installing")
        if (!resolveCoreRefs(classLoader)) return
        ruleReady = resolveRuleRefs(classLoader)
        bfcReady = resolveBfcRefs(classLoader)
        folderIconReady = resolveFolderIconRefs(classLoader)

        hookSetupGeometry()
        hookChildCountRewrite()
        if (ruleReady) hookChildGridRule()
        if (bfcReady) hookFolderGaps()
        if (folderIconReady) hookFolderLabel()
        hookAvailableWh()
        hookIsUpdatePreviewSize()
        hookDeviceProfileTelemetry()
    }

    // ── Install-time reflection resolution ──

    /** Core group: PreviewBackground / ActivityContext / DeviceProfile / InvariantDeviceProfile. */
    private fun resolveCoreRefs(classLoader: ClassLoader): Boolean {
        return try {
            val pb = classLoader.loadClass("com.android.launcher3.folder.PreviewBackground")
            val activityContextClass = classLoader.loadClass("com.android.launcher3.views.ActivityContext")
            pbSpanX = findField(pb, "spanX")
            pbSpanY = findField(pb, "spanY")
            pbWidth = findField(pb, "o")     // Obfuscated short name: bgWidth
            pbOffsetX = findField(pb, "p")   // Obfuscated short name: offsetX
            pbOffsetY = findField(pb, "q")   // Obfuscated short name: offsetY
            pbPreviewSizeY = findField(pb, "previewSizeY")
            pbSetup = findMethod(
                pb, "setup",
                Context::class.java, activityContextClass, View::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            pbComputeWh = findMethod(
                pb, "computeBigFolderAvaliableWh",
                activityContextClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java
            )
            pbIsUpdate = findMethod(pb, "isUpdatePreviewSize", activityContextClass)

            activityContextGetDeviceProfile = findMethod(activityContextClass, "getDeviceProfile")

            val dp = classLoader.loadClass("com.android.launcher3.DeviceProfile")
            dpInv = findField(dp, "inv")
            invNumColumns = findField(dpInv.type, "numColumns")
            invNumRows = findField(dpInv.type, "numRows")
            dpPadding = findField(dp, "cellLayoutPaddingPx")
            dpBorderSpace = findField(dp, "cellLayoutBorderSpacePx")
            dpWidgetPadding = findField(dp, "widgetPadding")
            dpIconSize = findField(dp, "iconSizePx")
            dpFolderIconSize = findField(dp, "folderIconSizePx")
            dpIconDrawablePadding = findField(dp, "iconDrawablePaddingPx")
            dpFolderIconOffsetY = findField(dp, "folderIconOffsetYPx")
            dpCellHeight = findField(dp, "cellHeightPx")
            dpGetCellLayoutWidth = findMethod(dp, "getCellLayoutWidth")
            dpGetCellLayoutHeight = findMethod(dp, "getCellLayoutHeight")
            dpUpdateIconSize = findMethod(dp, "updateIconSize", Float::class.javaPrimitiveType, Context::class.java)
            coreReady = true
            true
        } catch (t: Throwable) {
            logger.error("resolve core refs failed, BigFolderAlign disabled", t)
            false
        }
    }

    /** Rule group: ClippedFolderIconLayoutRule (child icon grid positioning). */
    private fun resolveRuleRefs(classLoader: ClassLoader): Boolean {
        return try {
            val rule = classLoader.loadClass("com.android.launcher3.folder.ClippedFolderIconLayoutRule")
            ruleCols = findField(rule, "j")          // Obfuscated short name: cols
            ruleRows = findField(rule, "g")          // Obfuscated short name: rows
            ruleIconSize = findField(rule, "d")      // Obfuscated short name: iconSize
            ruleBgWidth = findField(rule, "b")       // Obfuscated short name: bgWidth
            ruleBgHeight = findField(rule, "i")      // Obfuscated short name: bgHeight
            val i = Int::class.javaPrimitiveType
            val f = Float::class.javaPrimitiveType
            ruleC = findMethod(
                rule, "c",
                i, i, FloatArray::class.java, i, i, i, i, i
            )
            ruleGetOffsetX = findMethod(rule, "getOffsetX", i, i, i, f, f, i, i)
            ruleGetOffsetY = findMethod(rule, "getOffsetY", i, i, i, f, f, i, i)
            ruleScaleForItem = findMethod(rule, "scaleForItem", i, Boolean::class.java)
            true
        } catch (t: Throwable) {
            logger.error("resolve rule refs failed, child grid hook skipped", t)
            false
        }
    }

    /** Stylesheet group: BigFolderConfig (sub-grid / spacing convergence point). */
    private fun resolveBfcRefs(classLoader: ClassLoader): Boolean {
        return try {
            val bfc = classLoader.loadClass("com.zui.launcher.folder.bigfolder.BigFolderConfig")
            bfcChildIconScale = findField(bfc, "CHILD_ICON_SCALE")
            bfcGetChildCount = findMethod(
                bfc, "getBigFolderIconChildCount",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, IntArray::class.java
            )
            bfcGetHGap = findMethod(bfc, "getBigFolderIconHGap", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            bfcGetVGap = findMethod(bfc, "getBigFolderIconVGap", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            true
        } catch (t: Throwable) {
            logger.error("resolve BigFolderConfig refs failed, child grid/gap hooks skipped", t)
            false
        }
    }

    /** FolderIcon group: Label field resolved by candidate names, fallback to scanning for BubbleTextView type. */
    private fun resolveFolderIconRefs(classLoader: ClassLoader): Boolean {
        return try {
            val fi = classLoader.loadClass("com.android.launcher3.folder.FolderIcon")
            fiActivityContext = findField(fi, "b")   // Obfuscated short name: ActivityContext
            fiInfo = findField(fi, "mInfo")
            fiSpanX = findField(fiInfo.type, "spanX")
            fiSpanY = findField(fiInfo.type, "spanY")
            folderLabel = listOf("folderBubbleTextView", "e").firstNotNullOfOrNull { name ->
                try {
                    findField(fi, name)
                } catch (_: Throwable) {
                    null
                }
            }
            if (folderLabel == null) {
                val bubbleClass = classLoader.loadClass("com.android.launcher3.BubbleTextView")
                folderLabel = fi.declaredFields
                    .firstOrNull { bubbleClass.isAssignableFrom(it.type) }
                    ?.apply { isAccessible = true }
            }
            if (folderLabel == null) {
                logger.error("FolderIcon label field unresolved, label hook skipped")
                return false
            }
            fiZ = findMethod(fi, "z")
            true
        } catch (t: Throwable) {
            logger.error("resolve FolderIcon refs failed, label hook skipped", t)
            false
        }
    }

    // ── Hook 1: setup tail background geometry rewrite ──

    private fun hookSetupGeometry() {
        hookWithId(pbSetup, "big_folder_align_setup") { chain ->
            chain.proceed()
            try {
                applyAlignedGeometry(chain.thisObject, chain.args.getOrNull(1), chain.args.getOrNull(2))
            } catch (t: Throwable) {
                logger.error("applyAlignedGeometry failed", t)
            }
            null
        }
        logger.debug("hooked PreviewBackground.setup")
    }

    private fun applyAlignedGeometry(pb: Any, activityContext: Any?, folderIconView: Any?) {
        if (!coreReady || activityContext == null) return
        val spanX = pbSpanX.getInt(pb)
        val spanY = pbSpanY.getInt(pb)
        // Same semantics as host BigFolderConfig.isBigFolder
        if (spanX <= 1 && spanY <= 1) return

        val dp = activityContextGetDeviceProfile.invoke(activityContext) ?: return
        val metrics = readGridMetrics(dp) ?: return
        // For static method hooks without context parameter (spacing recalculation)
        cachedCellWidth = metrics.cellWidth
        cachedCellPitch = metrics.cellHeight + metrics.gapY
        cachedRowInset = metrics.rowInset
        cachedIconSizePx = metrics.iconSizePx
        cachedFolderIconSizePx = metrics.folderIconSizePx

        val inset = if (alignToSmallFolder) metrics.insetFolder else metrics.insetIcon
        val newWidth = spanX * metrics.cellWidth + (spanX - 1) * metrics.gapX - 2 * inset
        val oldWidth = pbWidth.getInt(pb)
        val oldOffsetX = pbOffsetX.getInt(pb)
        pbWidth.setInt(pb, newWidth)
        pbOffsetX.setInt(pb, inset)

        // Background top/bottom edges align with top/bottom icon glyph edges (transparent margin artInset in glyph box)
        val artInset = (metrics.iconSizePx * ART_INSET_RATIO).roundToInt()
        val newOffsetY = metrics.rowInset + artInset
        val newPreviewSizeY = (spanY - 1) * (metrics.cellHeight + metrics.gapY) +
            metrics.rowInset + metrics.iconSizePx - artInset - newOffsetY
        val oldOffsetY = pbOffsetY.getInt(pb)
        val oldPreviewSizeY = pbPreviewSizeY.getInt(pb)
        if (newPreviewSizeY > 0) {
            pbOffsetY.setInt(pb, newOffsetY)
            pbPreviewSizeY.setInt(pb, newPreviewSizeY)
        }

        // Observation: label topMargin and FolderIcon's own paddingTop (visual label position = sum of both)
        var labelTopMargin = -1
        var iconPaddingTop = -1
        try {
            val f = folderLabel
            if (folderIconView != null && f != null) {
                labelTopMargin = ((f.get(folderIconView) as View).layoutParams
                        as ViewGroup.MarginLayoutParams).topMargin
            }
            iconPaddingTop = (folderIconView as? View)?.paddingTop ?: -1
        } catch (_: Throwable) {
        }

        logger.debug(
            "BigFolderAlign span=${spanX}x${spanY}" +
                " width $oldWidth->$newWidth offsetX $oldOffsetX->$inset" +
                " offsetY $oldOffsetY->$newOffsetY previewY $oldPreviewSizeY->${pbPreviewSizeY.getInt(pb)}" +
                " artInset=$artInset" +
                " cellW=${metrics.cellWidth} nominalW=${metrics.nominalCellWidth} gap=${metrics.gapX}" +
                " cellH=${metrics.cellHeight} gapY=${metrics.gapY} rowInset=${metrics.rowInset}" +
                " cellHeightPx=${metrics.cellHeightPx}" +
                " folderIcon=${metrics.folderIconSizePx} icon=${metrics.iconSizePx}" +
                " widgetPadL=${metrics.widgetPaddingLeft} widgetPadT=${metrics.widgetPaddingTop}" +
                " bgBottom=${pbOffsetY.getInt(pb) + pbPreviewSizeY.getInt(pb)}" +
                " labelTop=$labelTopMargin iconPadT=$iconPaddingTop"
        )
    }

    // ── Grid metrics ──

    private class GridMetrics(
        val gapX: Int,
        val gapY: Int,
        val nominalCellWidth: Int,
        val cellWidth: Int,
        val cellHeight: Int,
        val cellHeightPx: Int,
        val rowInset: Int,
        val insetFolder: Int,
        val insetIcon: Int,
        val iconSizePx: Int,
        val folderIconSizePx: Int,
        val widgetPaddingLeft: Int,
        val widgetPaddingTop: Int
    )

    private fun readGridMetrics(dp: Any): GridMetrics? {
        return try {
            val inv = dpInv.get(dp)
            val columns = invNumColumns.getInt(inv)
            val rows = invNumRows.getInt(inv)
            val padding = dpPadding.get(dp) as Rect
            val borderSpace = dpBorderSpace.get(dp) as Point
            val widgetPadding = dpWidgetPadding.get(dp) as Rect
            val iconSizePx = dpIconSize.getInt(dp)
            val folderIconSizePx = dpFolderIconSize.getInt(dp)
            // cellHeightPx = row content height (icon + spacing + text), measured 260 vs row spacing 348
            val cellHeightPx = dpCellHeight.getInt(dp)

            val gridWidth = (dpGetCellLayoutWidth.invoke(dp) as Int) - 2 * padding.left
            val nominalCellWidth = gridWidth / columns
            val gapX = borderSpace.x
            val cellWidth = (gridWidth - (columns - 1) * gapX) / columns
            val gridHeight = (dpGetCellLayoutHeight.invoke(dp) as Int) - padding.top - padding.bottom
            val gapY = borderSpace.y
            val cellHeight = (gridHeight - (rows - 1) * gapY) / rows
            // Real vertical inset of icon within row, same origin as host formula max(0,(C-cellHeightPx)/2)
            val rowInset = maxOf(0, (cellHeight - cellHeightPx) / 2)
            GridMetrics(
                gapX = gapX,
                gapY = gapY,
                nominalCellWidth = nominalCellWidth,
                cellWidth = cellWidth,
                cellHeight = cellHeight,
                cellHeightPx = cellHeightPx,
                rowInset = rowInset,
                insetFolder = (cellWidth - folderIconSizePx) / 2,
                insetIcon = (cellWidth - iconSizePx) / 2,
                iconSizePx = iconSizePx,
                folderIconSizePx = folderIconSizePx,
                widgetPaddingLeft = widgetPadding.left,
                widgetPaddingTop = widgetPadding.top
            )
        } catch (t: Throwable) {
            logger.error("readGridMetrics failed", t)
            null
        }
    }

    // ── Hook 2: Sub-grid and spacing ──

    /** Stylesheet convergence point rewrite: single rewrite applies across layout / preview count / click hit / drop capacity. */
    private fun hookChildCountRewrite() {
        hookWithId(bfcGetChildCount, "big_folder_align_child_count") { chain ->
            val result = chain.proceed()
            try {
                val spanX = chain.args[0] as Int
                val spanY = chain.args[1] as Int
                val out = chain.args[2] as IntArray?
                val key = "${spanX}x$spanY"
                if (out != null) {
                    stockGrids[key] = intArrayOf(out[0], out[1])
                }
                val rewrite: IntArray? = when {
                    spanX == TARGET_SPAN_X && spanY == TARGET_SPAN_Y ->
                        intArrayOf(CHILD_COLS, CHILD_ROWS)
                    spanY == TARGET_SPAN_Y && spanX >= 2 -> {
                        val stock = stockGrids[key]
                        if (stock != null && stock[1] == 2) intArrayOf(stock[0], CHILD_ROWS)
                        else null
                    }
                    else -> null
                }
                if (rewrite != null) {
                    out?.set(0, rewrite[0])
                    out?.set(1, rewrite[1])
                    rewrittenGrids[key] = rewrite
                    val newCount = rewrite[0] * rewrite[1]
                    if ((result as Int) != newCount && loggedSpans.add(key)) {
                        logger.debug(
                            "childCount($key) $result -> $newCount (cols=${rewrite[0]} rows=${rewrite[1]})"
                        )
                    }
                    return@hookWithId newCount
                }
            } catch (t: Throwable) {
                logger.error("childCount rewrite failed", t)
            }
            result
        }
        logger.debug("hooked getBigFolderIconChildCount")
    }

    /** c() phone branch was hardcoded to 2-column positioning; rewritten to rule's own getOffsetX/getOffsetY general grid. */
    private fun hookChildGridRule() {
        hookWithId(ruleC, "big_folder_align_child_grid") { chain ->
            chain.proceed()
            try {
                val args = chain.args
                val index = args[0] as Int
                val spanX = args[3] as Int
                val spanY = args[4] as Int
                // ENTER/EXIT(-2/-3) follow animation-dedicated path; keep non-big folders untouched
                if (index < 0 || (spanX <= 1 && spanY <= 1)) return@hookWithId null
                val rule = chain.thisObject
                val cols = ruleCols.getInt(rule)
                val rows = ruleRows.getInt(rule)
                val iconSize = ruleIconSize.getFloat(rule)
                val halfIcon =
                    iconSize * (ruleScaleForItem.invoke(rule, maxOf(args[1] as Int, 3), true) as Float) / 2f
                val centerX =
                    if ((args[5] as Int) == -1) ruleBgWidth.getFloat(rule) / 2f
                    else (args[5] as Int) / 2f
                val centerY =
                    (if ((args[6] as Int) == -1) ruleBgHeight.getFloat(rule) / 2f
                    else (args[6] as Int) / 2f) + (args[7] as Int)
                val out = args[2] as FloatArray
                out[0] = ruleGetOffsetX.invoke(rule, index, cols, rows, centerX, halfIcon, spanX, spanY) as Float
                out[1] = ruleGetOffsetY.invoke(rule, index, cols, rows, centerY, halfIcon, spanX, spanY) as Float
                logger.debug("childGrid idx=$index -> (${out[0]}, ${out[1]})")
            } catch (t: Throwable) {
                logger.error("child grid rewrite failed", t)
            }
            null
        }
        logger.debug("hooked ClippedFolderIconLayoutRule.c")
    }

    /**
     * Stock spacing is calibrated to stock grid, taking too large a proportion after changing row/col count.
     * For all big folder spans (spanX>1 || spanY>1), recalculate by bgSize x GRID_OCCUPANCY, only reducing spacing (lower bound 0);
     * centered layout turns saved space into outer padding. Grid uses rewritten grid ?: stock grid.
     */
    private fun hookFolderGaps() {
        for ((method, isH) in listOf(bfcGetHGap to true, bfcGetVGap to false)) {
            val id = "big_folder_align_${if (isH) "h" else "v"}gap"
            hookWithId(method, id) { chain ->
                val result = chain.proceed()
                try {
                    val spanX = chain.args[0] as Int
                    val spanY = chain.args[1] as Int
                    if (spanX <= 1 && spanY <= 1) return@hookWithId result
                    val spanKey = "${spanX}x$spanY"
                    val grid = rewrittenGrids[spanKey] ?: stockGrids[spanKey]
                        ?: return@hookWithId result
                    if (cachedCellWidth == 0) return@hookWithId result
                    val n = if (isH) grid[0] else grid[1]
                    if (n <= 1) return@hookWithId 0f
                    val artInset = (cachedIconSizePx * ART_INSET_RATIO).roundToInt()
                    val bgAxis: Int = if (isH) {
                        (spanX - 1) * cachedCellWidth + cachedFolderIconSizePx
                    } else {
                        (spanY - 1) * cachedCellPitch + cachedIconSizePx - 2 * artInset
                    }
                    val childSize = cachedFolderIconSizePx * bfcChildIconScale.getFloat(null)
                    val newGap = maxOf(0f, (bgAxis * GRID_OCCUPANCY - n * childSize) / (n - 1))
                    val logKey = "${if (isH) "h" else "v"}Gap$spanKey"
                    if (loggedSpans.add(logKey) && newGap != (result as Float)) {
                        logger.debug(
                            "${(if (isH) "hGap" else "vGap")}($spanX,$spanY) $result -> $newGap (n=$n bg=$bgAxis)"
                        )
                    }
                    return@hookWithId newGap
                } catch (t: Throwable) {
                    logger.error("gap rewrite failed", t)
                    result
                }
            }
        }
        logger.debug("hooked getBigFolderIconHGap/VGap")
    }

    // ── Hook 3: Align label height with neighbor labels (complete replacement of FolderIcon.z()) ──

    private fun hookFolderLabel() {
        val labelField = folderLabel ?: return
        hookWithId(fiZ, "big_folder_align_label") { chain ->
            try {
                val icon = chain.thisObject
                val info = fiInfo.get(icon)
                    ?: return@hookWithId chain.proceed()
                val spanX = fiSpanX.getInt(info)
                val spanY = fiSpanY.getInt(info)
                val activityContext = fiActivityContext.get(icon)
                    ?: return@hookWithId chain.proceed()
                val dp = activityContextGetDeviceProfile.invoke(activityContext)
                    ?: return@hookWithId chain.proceed()
                val iconSizePx = dpIconSize.getInt(dp)
                val drawablePadding = dpIconDrawablePadding.getInt(dp)
                val topMargin: Int
                if (spanX <= 1 && spanY <= 1) {
                    // Small folder branch: replicate stock z() formula
                    topMargin = iconSizePx + drawablePadding
                } else {
                    val metrics = readGridMetrics(dp)
                        ?: return@hookWithId chain.proceed()
                    topMargin = (spanY - 1) * (metrics.cellHeight + metrics.gapY) +
                        metrics.rowInset + iconSizePx + drawablePadding
                }
                val label = labelField.get(icon) as View
                val lp = label.layoutParams as ViewGroup.MarginLayoutParams
                if (lp.topMargin != topMargin) {
                    lp.topMargin = topMargin
                    label.requestLayout()
                    logger.debug("label topMargin -> $topMargin (span=${spanX}x${spanY})")
                }
                null // Completely replaces host z(); on reflection failure catch falls back to stock
            } catch (t: Throwable) {
                logger.error("label align failed, fallback to stock z()", t)
                chain.proceed()
            }
        }
        logger.debug("hooked FolderIcon.z (label alignment)")
    }

    // ── Hook 4: computeBigFolderAvaliableWh width synchronization ──

    private fun hookAvailableWh() {
        hookWithId(pbComputeWh, "big_folder_align_available_wh") { chain ->
            chain.proceed()
            try {
                val args = chain.args
                val wh = args[3] as IntArray
                val holder = args[0] ?: return@hookWithId null
                val dp = activityContextGetDeviceProfile.invoke(holder) ?: return@hookWithId null
                val metrics = readGridMetrics(dp) ?: return@hookWithId null
                val spanX = args[1] as Int
                if (spanX > 1) {
                    val inset = if (alignToSmallFolder) metrics.insetFolder else metrics.insetIcon
                    val old = wh[0]
                    wh[0] = spanX * metrics.cellWidth + (spanX - 1) * metrics.gapX - 2 * inset
                    logger.debug("availableWh spanX=$spanX width $old -> ${wh[0]} (h=${wh[1]})")
                }
            } catch (t: Throwable) {
                logger.error("availableWh adjust failed", t)
            }
            null
        }
        logger.debug("hooked computeBigFolderAvaliableWh")
    }

    // ── Hook 5: isUpdatePreviewSize read-only observation ──

    private fun hookIsUpdatePreviewSize() {
        hookWithId(pbIsUpdate, "big_folder_align_is_update") { chain ->
            val result = chain.proceed()
            try {
                val pb = chain.thisObject
                logger.debug(
                    "isUpdatePreviewSize -> $result span=${pbSpanX.getInt(pb)}x${pbSpanY.getInt(pb)}" +
                        " width=${pbWidth.getInt(pb)} offsetX=${pbOffsetX.getInt(pb)}"
                )
            } catch (_: Throwable) {
            }
            result
        }
        logger.debug("hooked isUpdatePreviewSize")
    }

    // ── Hook 6: DeviceProfile.updateIconSize read-only telemetry ──

    private fun hookDeviceProfileTelemetry() {
        hookWithId(dpUpdateIconSize, "big_folder_align_dp_log") { chain ->
            chain.proceed()
            try {
                val dp = chain.thisObject
                val inv = dpInv.get(dp)
                val padding = dpPadding.get(dp) as Rect
                val borderSpace = dpBorderSpace.get(dp) as Point
                val widgetPadding = dpWidgetPadding.get(dp) as Rect
                logger.debug(
                    "DeviceProfile cols=${invNumColumns.getInt(inv)}" +
                        " cellLayoutW=${dpGetCellLayoutWidth.invoke(dp)}" +
                        " pad=${padding.left}x${padding.top}" +
                        " gap=${borderSpace.x}x${borderSpace.y}" +
                        " iconSize=${dpIconSize.getInt(dp)}" +
                        " folderIcon=${dpFolderIconSize.getInt(dp)}" +
                        " folderOffsetY=${dpFolderIconOffsetY.getInt(dp)}" +
                        " widgetPad=${widgetPadding.left},${widgetPadding.top}"
                )
            } catch (t: Throwable) {
                logger.debug("DeviceProfile telemetry failed: $t")
            }
            null
        }
        logger.debug("hooked DeviceProfile.updateIconSize (telemetry)")
    }

    companion object {
        /** 2x2 big folder child icon grid = CHILD_COLS per row x CHILD_ROWS rows (3x3=9). */
        private const val TARGET_SPAN_X = 2
        private const val TARGET_SPAN_Y = 2
        private const val CHILD_COLS = 3
        private const val CHILD_ROWS = 3

        /**
         * Target ratio of sub-grid occupying background size; spacing recalculated by (bg*ratio - n*childSize)/(n-1);
         * smaller ratio means smaller spacing and larger outer margins (automatically allocated by centered layout).
         */
        private const val GRID_OCCUPANCY = 0.4f

        /** Glyph transparent margin ratio within icon box (measured ~21px in 190px box, glyph ~0.78x box). */
        private const val ART_INSET_RATIO = 0.11f

        /** One-time log deduplication (childCount/gap calls are very frequent, only logged once per key). */
        private val loggedSpans: MutableSet<String> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

        /** Learned stock sub-grid (span -> [cols, rows]) for lookup when out=null. */
        private val stockGrids = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

        /** Rewritten sub-grid span -> [cols, rows], prioritized during spacing recalculation. */
        private val rewrittenGrids = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

        /** Grid metrics cache for spacing recalculation (refreshed on each setup in applyAlignedGeometry, read by static hooks). */
        @Volatile var cachedCellWidth = 0
        @Volatile var cachedCellPitch = 0
        @Volatile var cachedRowInset = 0
        @Volatile var cachedIconSizePx = 0
        @Volatile var cachedFolderIconSizePx = 0
    }
}

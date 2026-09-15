package com.qimian233.ztool.hook.modules.systemui.wallpaper

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import android.view.Surface
import android.view.SurfaceHolder
import com.qimian233.ztool.data.keys.ScopeKeys
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.hook.base.AppHookModule
import io.github.libxposed.api.XposedModuleInterface
import java.io.File

/**
 * Desktop live wallpaper - Proof of concept hook (MediaCodec direct Surface rendering).
 *
 * Hijacks ImageWallpaper.CanvasEngine:
 * - Hook onSurfaceCreated -> obtain Engine's own Surface
 * - Hook drawFrameOnCanvas -> block static bitmap rendering
 * - Use MediaCodec to decode video directly to Engine's Surface (zero copy)
 * - Auto-loop after video playback finishes
 * - Orientation changes handled via CanvasEngine DisplayListener.onDisplayChanged callback
 *   (SystemUI wallpaper's own notification mechanism), with onConfigurationChanged /
 *   onSurfaceChanged fallbacks: reselects video based on new orientation;
 *   stops playback and actively triggers SystemUI redraw when that orientation has no corresponding video,
 *   redisplaying static wallpaper (strict match, no cross-orientation fallback)
 *
 * Video file paths:
 *   /sdcard/Download/ZTool/wallpaper_portrait.mp4  (Portrait)
 *   /sdcard/Download/ZTool/wallpaper_land.mp4      (Landscape)
 *
 * getModuleName() returns PreferenceKeys.DESKTOP_LIVE_WALLPAPER.name,
 * enabled via frontend toggle.
 */
@SuppressLint("PrivateApi")
class DesktopLiveWallpaperHook : AppHookModule() {

    companion object {
        private val SYSTEMUI_PKG = ScopeKeys.SYSTEM_UI.packageName
        private const val ENGINE_CLASS =
            $$"com.android.systemui.wallpapers.ImageWallpaper$CanvasEngine"
        private const val CUSTOM_VIDEO_DIR = "/Download/ZTool"
        private const val VIDEO_PORTRAIT = "wallpaper_portrait.mp4"
        private const val VIDEO_LAND = "wallpaper_land.mp4"
        private const val DECODE_TIMEOUT_US = 10_000L
        // Video display mode preference value (corresponds 1:1 with frontend dropdown options)
        private const val SCALE_MODE_FIT = "fit"
        private const val SCALE_MODE_COVER = "cover"
    }

    // Playback state per Engine instance
    private var codec: MediaCodec? = null
    private var extractor: MediaExtractor? = null
    private var decodeThread: Thread? = null
    @Volatile private var running = false
    private var engineSurface: Surface? = null
    private var reportedShown = false
    // Currently active screen orientation (used to determine whether to reselect video on orientation change)
    private var currentOrientation = Configuration.ORIENTATION_UNDEFINED

    override fun getModuleName(): String = PreferenceKeys.DESKTOP_LIVE_WALLPAPER.name

    override fun getTargetPackages(): Array<String> = arrayOf(SYSTEMUI_PKG)

    override fun handleLoadPackage(param: XposedModuleInterface.PackageLoadedParam) {
        if (param.packageName != SYSTEMUI_PKG) return

        try {
            val engineClass = param.defaultClassLoader.loadClass(ENGINE_CLASS)

            // 1. onSurfaceCreated -> obtain Surface, start playback
            hookWithId(
                engineClass.getDeclaredMethod("onSurfaceCreated", SurfaceHolder::class.java),
                "dynwall_surface_created"
            ) { chain ->
                chain.proceed()
                onSurfaceReady(chain.thisObject)
            }

            // 2. drawFrameOnCanvas -> block static image covering video frame during playback
            hookWithId(
                engineClass.getDeclaredMethod("drawFrameOnCanvas", android.graphics.Bitmap::class.java),
                "dynwall_draw_frame"
            ) {
                if (!running) it.proceed() else null
            }

            // 3. onSurfaceDestroyed -> cleanup
            hookWithId(
                engineClass.getDeclaredMethod("onSurfaceDestroyed", SurfaceHolder::class.java),
                "dynwall_surface_destroyed"
            ) { chain ->
                stopPlayback()
                chain.proceed()
            }

            // 4. onDisplayChanged -> orientation/display change (SystemUI wallpaper's own notification path, always triggered on rotation)
            try {
                hookWithId(
                    engineClass.getDeclaredMethod(
                        "onDisplayChanged", Int::class.javaPrimitiveType
                    ),
                    "dynwall_display_changed"
                ) { chain ->
                    chain.proceed()
                    logger.debug("DesktopLiveWallpaper: onDisplayChanged invoked")
                    refreshOrientationAndPlayback(chain.thisObject)
                }
            } catch (t: Throwable) {
                logger.error("DesktopLiveWallpaper: failed to hook onDisplayChanged", t)
            }

            // 5. onConfigurationChanged -> framework dispatch fallback (some ROM paths)
            // Use getMethod to find inherited public method, compatible with CanvasEngine not overriding it
            try {
                hookWithId(
                    engineClass.getMethod("onConfigurationChanged", Configuration::class.java),
                    "dynwall_config_changed"
                ) { chain ->
                    chain.proceed()
                    refreshOrientationAndPlayback(chain.thisObject)
                }
            } catch (t: Throwable) {
                logger.error("DesktopLiveWallpaper: failed to hook onConfigurationChanged", t)
            }

            // 6. onSurfaceChanged -> Surface dimension change fallback (rotation usually accompanied by width/height swap)
            try {
                hookWithId(
                    engineClass.getDeclaredMethod(
                        "onSurfaceChanged",
                        SurfaceHolder::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    ),
                    "dynwall_surface_changed"
                ) { chain ->
                    chain.proceed()
                    refreshOrientationAndPlayback(chain.thisObject)
                }
            } catch (t: Throwable) {
                logger.error("DesktopLiveWallpaper: failed to hook onSurfaceChanged", t)
            }

            logger.info("DesktopLiveWallpaper: hooks installed")
        } catch (t: Throwable) {
            logger.error("DesktopLiveWallpaper: failed to install hooks", t)
        }
    }

    // ── Surface Ready ──────────────────────────────────────────

    private fun onSurfaceReady(engine: Any) {
        stopPlayback()

        val surface = resolveSurface(engine) ?: run {
            logger.error("DesktopLiveWallpaper: mSurfaceHolder missing or Surface invalid")
            return
        }
        engineSurface = surface

        currentOrientation = detectOrientation(engine)
        val videoPath = videoPathFor(currentOrientation)
        if (videoPath == null) {
            logger.warn(
                "DesktopLiveWallpaper: no video for orientation $currentOrientation, keep static"
            )
            return
        }

        startPlayback(engine, videoPath)
    }

    /**
     * Resolve currently valid Surface in real-time from engine (prevents cache invalidation).
     * Returns null if mSurfaceHolder is missing or Surface is invalid (isValid == false).
     */
    private fun resolveSurface(engine: Any): Surface? {
        return try {
            val holder = engine.javaClass
                .getDeclaredField("mSurfaceHolder").apply { isAccessible = true }
                .get(engine) as? SurfaceHolder ?: return null
            holder.surface?.takeIf { it.isValid }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Detect display orientation currently bound to Engine.
     * Returns ORIENTATION_UNDEFINED on reflection failure, caller treats as portrait.
     */
    private fun detectOrientation(engine: Any): Int {
        return try {
            val displayContext = engine.javaClass.superclass
                ?.getDeclaredMethod("getDisplayContext")?.apply { isAccessible = true }
                ?.invoke(engine) as? android.content.Context
            displayContext?.resources?.configuration?.orientation
                ?: Configuration.ORIENTATION_UNDEFINED
        } catch (_: Exception) {
            Configuration.ORIENTATION_UNDEFINED
        }
    }

    /**
     * Strict matching: only return video path corresponding to current orientation.
     * Returns null when video for that orientation does not exist (no cross-orientation fallback); caller should retain static wallpaper.
     */
    private fun videoPathFor(orientation: Int): String? {
        val baseDir = Environment.getExternalStorageDirectory().path + CUSTOM_VIDEO_DIR
        val fileName = when (orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> VIDEO_LAND
            else -> VIDEO_PORTRAIT // Undefined/unknown orientation treated as portrait
        }
        val path = "$baseDir/$fileName"
        return if (File(path).exists()) path else null
    }

    // ── Playback Control ──────────────────────────────────────────────

    private fun startPlayback(engine: Any, videoPath: String) {
        try {
            val extractor = MediaExtractor().also { it.setDataSource(videoPath) }

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: run {
                logger.error("DesktopLiveWallpaper: no video track")
                extractor.release()
                return
            }

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            extractor.selectTrack(trackIndex)

            val codec = MediaCodec.createDecoderByType(mime)
            // Surface may have been invalidated; verify again before configure to avoid native_configure exception
            val surface = engineSurface
            if (surface == null || !surface.isValid) {
                logger.error("DesktopLiveWallpaper: invalid surface, abort playback")
                releaseCodec()
                return
            }
            codec.configure(format, surface, null, 0)
            applyVideoScalingMode(codec)
            codec.start()

            this.extractor = extractor
            this.codec = codec
            running = true
            reportedShown = false

            decodeThread = Thread({
                decodeLoop(engine)
            }, "DesktopLiveWallpaper-Decode").also { it.start() }

            logger.info("DesktopLiveWallpaper: playback started ${w}x${h}")
        } catch (t: Throwable) {
            logger.error("DesktopLiveWallpaper: startPlayback failed", t)
            releaseCodec()
        }
    }

    /**
     * Sets video display mode on Surface based on user preference (MediaCodec surface output official scaling modes):
     * - fit   -> VIDEO_SCALING_MODE_SCALE_TO_FIT (maintain aspect ratio, full display, may have black borders)
     * - cover -> VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING (proportional cropping fills entire screen, no black borders, no distortion)
     *
     * Preference value is written to xposed_module_config from frontend dropdown.
     * Unknown values are not called (maintaining system default behavior); failures only logged, not affecting playback.
     */
    private fun applyVideoScalingMode(codec: MediaCodec) {
        try {
            val mode = xposed.getRemotePreferences("xposed_module_config")
                .getString(
                    PreferenceKeys.DESKTOP_LIVE_WALLPAPER_SCALE_MODE.name,
                    PreferenceKeys.DESKTOP_LIVE_WALLPAPER_SCALE_MODE.default
                )
            val scalingMode = when (mode) {
                SCALE_MODE_COVER -> MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                SCALE_MODE_FIT -> MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT
                else -> {
                    logger.warn("DesktopLiveWallpaper: unknown scale mode '$mode', keep default")
                    return
                }
            }
            codec.setVideoScalingMode(scalingMode)
            logger.debug("DesktopLiveWallpaper: video scaling mode = $mode ($scalingMode)")
        } catch (t: Throwable) {
            logger.error("DesktopLiveWallpaper: failed to set video scaling mode", t)
        }
    }

    /**
     * Stop playback. Clears engineSurface by default;
     * scenarios where Surface remains valid like orientation change pass clearSurface = false to retain Surface for reuse.
     */
    private fun stopPlayback(clearSurface: Boolean = true) {
        running = false
        decodeThread?.interrupt()
        decodeThread?.join(500)
        decodeThread = null
        releaseCodec()
        if (clearSurface) engineSurface = null
    }

    private fun releaseCodec() {
        try { codec?.stop() } catch (_: Exception) {}
        try { codec?.release() } catch (_: Exception) {}
        codec = null
        try { extractor?.release() } catch (_: Exception) {}
        extractor = null
    }

    // ── Decoding Loop (runs on dedicated thread) ──────────────────────────

    private fun decodeLoop(engine: Any) {
        val extractor = extractor ?: return
        val codec = codec ?: return
        val bufInfo = MediaCodec.BufferInfo()
        var inputEos = false

        // Frame interval control: synchronize playback speed based on video timestamps
        var lastPtsUs = -1L           // presentationTimeUs of previous frame (-1 indicates no previous frame)
        var lastRenderNanos = 0L      // System.nanoTime() when previous frame rendering completed

        try {
            while (running && !Thread.interrupted()) {
                // Feed data
                if (!inputEos) {
                    val inIdx = codec.dequeueInputBuffer(DECODE_TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Get output -> render to Surface
                val outIdx = codec.dequeueOutputBuffer(bufInfo, DECODE_TIMEOUT_US)
                when {
                    outIdx >= 0 -> {
                        val render = bufInfo.size > 0 &&
                            (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0

                        if (render) {
                            // Control playback rate based on frame timestamps
                            val ptsUs = bufInfo.presentationTimeUs
                            if (lastPtsUs >= 0) {
                                val frameGapUs = ptsUs - lastPtsUs
                                if (frameGapUs > 0) {
                                    val nowNanos = System.nanoTime()
                                    val elapsedNanos = nowNanos - lastRenderNanos
                                    val targetNanos = frameGapUs * 1000L // μs → ns
                                    val sleepNanos = targetNanos - elapsedNanos
                                    if (sleepNanos > 500_000L) { // Only sleep if >0.5ms to avoid busy waiting
                                        Thread.sleep(
                                            sleepNanos / 1_000_000L,
                                            (sleepNanos % 1_000_000L).toInt()
                                        )
                                    }
                                }
                            }
                        }

                        codec.releaseOutputBuffer(outIdx, render)

                        if (render) {
                            lastPtsUs = bufInfo.presentationTimeUs
                            lastRenderNanos = System.nanoTime()
                        }

                        if (!reportedShown && render) {
                            reportedShown = true
                            reportEngineShown(engine)
                        }

                        if (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            logger.debug("DesktopLiveWallpaper: looping")
                            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                            codec.flush()
                            inputEos = false
                            lastPtsUs = -1L // Reset after loop, first frame does not wait
                        }
                    }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* ignore */ }
                }
            }
        } catch (_: InterruptedException) {
            // Normal stop signal
        } catch (t: Throwable) {
            if (running) logger.error("DesktopLiveWallpaper: decode error", t)
        }
    }

    // ── Orientation Switch ──────────────────────────────────────────────

    /**
     * Unified entry point for orientation/display changes (shared by onDisplayChanged / onConfigurationChanged / onSurfaceChanged):
     * Re-detects orientation, reselects video based on new orientation if different from current active orientation.
     * - New orientation has corresponding video -> stop old playback and switch (Surface reused, not recreated)
     * - New orientation has no video -> stop playback, fallback to static wallpaper (drawFrameOnCanvas resumes proceed)
     */
    private fun refreshOrientationAndPlayback(engine: Any) {
        val orientation = detectOrientation(engine)
        if (orientation == currentOrientation) return
        currentOrientation = orientation
        // Surface not yet ready (engine just created); wait for onSurfaceCreated to resolve
        if (engineSurface == null) return

        val videoPath = videoPathFor(orientation)
        if (videoPath == null) {
            logger.info(
                "DesktopLiveWallpaper: no video for orientation $orientation, fallback to static"
            )
            stopPlayback(clearSurface = false)
            // Clear remaining last video frame from Surface, redisplay static wallpaper
            redrawStaticWallpaper(engine)
            return
        }

        // Surface may have been invalidated with orientation change (onSurfaceDestroyed not yet called);
        // re-resolve in real time; skip this switch if invalid, wait for onSurfaceCreated / next callback
        engineSurface = resolveSurface(engine)
        if (engineSurface == null) {
            logger.warn(
                "DesktopLiveWallpaper: surface unavailable after rotation, skip switching video"
            )
            return
        }

        logger.info(
            "DesktopLiveWallpaper: orientation changed to $orientation, switching video"
        )
        stopPlayback(clearSurface = false)
        startPlayback(engine, videoPath)
    }

    /**
     * Actively trigger SystemUI redraw when falling back to static wallpaper,
     * clearing the residual last video frame on Surface.
     *
     * CanvasEngine redraw entry point (onSurfaceRedrawNeeded -> mLongExecutor)
     * contains `if (!mDrawn)` check; mDrawn=true after static wallpaper was drawn and would be skipped;
     * therefore reset mDrawn=false first, then invoke drawFrameInternal() directly under mLock synchronization
     * (internally calls drawFrameOnCanvas to draw static bitmap).
     */
    private fun redrawStaticWallpaper(engine: Any) {
        try {
            val engineClass = engine.javaClass
            engineClass.getDeclaredField("mDrawn").apply { isAccessible = true }
                .setBoolean(engine, false)
            val lock = engineClass.getDeclaredField("mLock").apply { isAccessible = true }
                .get(engine) ?: return
            val drawFrameInternal = engineClass.getDeclaredMethod("drawFrameInternal")
                .apply { isAccessible = true }
            synchronized(lock) {
                drawFrameInternal.invoke(engine)
            }
            logger.debug("DesktopLiveWallpaper: static wallpaper redrawn")
        } catch (t: Throwable) {
            logger.error("DesktopLiveWallpaper: redrawStaticWallpaper failed", t)
        }
    }

    // ── Helper ──────────────────────────────────────────────────

    private fun reportEngineShown(engine: Any) {
        try {
            engine.javaClass.superclass
                ?.getDeclaredMethod("reportEngineShown")
                ?.apply { isAccessible = true }
                ?.invoke(engine)
        } catch (_: Exception) { /* non-critical */ }
    }
}

package com.qimian233.ztool.service

import android.content.Intent
import android.service.quicksettings.TileService

/**
 * Media output switcher tile: clicking summons the system media output dialog
 * (the MediaOutputDialog presented by the media card's output switcher entry).
 *
 * Implementation relies entirely on standard Android platform behavior, no Hooks:
 * 1. Registered as a standard TileService; users drag it into place via the Control Center editor;
 * 2. On click, sends an explicit broadcast to SystemUI's MediaOutputDialogReceiver
 *    (verified on physical devices to be reachable by third-party apps); the system side
 *    MediaOutputDialogManager.createAndShow(null, ...) renders the dialog,
 *    functioning properly even in empty states (no active media session).
 *
 * onClick runs on the main thread; the broadcast is fire-and-forget, requiring no extra threads.
 */
class MediaOutputTileService : TileService() {

    override fun onClick() {
        super.onClick()
        sendBroadcast(Intent(ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG).apply {
            setClassName(SYSTEMUI_PACKAGE, RECEIVER_CLASS)
        })
    }

    private companion object {
        const val SYSTEMUI_PACKAGE = "com.android.systemui"
        const val RECEIVER_CLASS = "com.android.systemui.media.dialog.MediaOutputDialogReceiver"
        // AOSP SystemUI public action, consumed statically by MediaOutputDialogReceiver
        const val ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG =
            "com.android.systemui.action.LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG"
    }
}

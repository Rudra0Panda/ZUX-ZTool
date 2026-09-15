package com.qimian233.ztool.dexindex.base

import com.qimian233.ztool.dexindex.indexer.LauncherDexIndexer
import com.qimian233.ztool.dexindex.indexer.MobileDesktopDexIndexer
import com.qimian233.ztool.dexindex.indexer.SystemUiDexIndexer

/**
 * Registry for all offline indexers. Register here when adding new scopes using DexKit.
 */
object DexIndexRegistry {

    val indexers: List<DexIndexer> = listOf(
        LauncherDexIndexer(),
        SystemUiDexIndexer(),
        MobileDesktopDexIndexer(),
    )
}

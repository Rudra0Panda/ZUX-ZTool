package com.qimian233.ztool.dexindex.base

import android.content.Context
import com.google.gson.JsonObject
import org.luckypray.dexkit.DexKitBridge

/**
 * Scope-level offline indexer.
 *
 * One implementation per target scope (package), responsible for precomputing
 * method/field names for all Hook modules using DexKit under that package. **Must not depend on libxposed** (runs within module app process).
 *
 * Implementation conventions:
 * - Each module query within [index] is separately try-caught; single failure does not affect others;
 * - Output JsonObject grouped by `DexIndexConstants.ModuleKeys`, field keys use `DexIndexConstants.Keys`;
 * - **Do not write fallback values**: if query fails, omit that key, letting Hook side fallback to hardcoded defaults.
 */
interface DexIndexer {

    /** Target scope package name (references `ScopeKeys.CONSTANT.packageName`). */
    val scopePackage: String

    /**
     * Executes all queries for this scope using the given bridge.
     * Returns JSON shaped as `{ "<moduleKey>": { "<fieldKey>": "<value>" } }` --
     * **contains modules mapping only**; root wrapper (schemaVersion/apk fingerprint, etc.)
     * is handled by [DexIndexManager] to avoid double nesting.
     */
    fun index(bridge: DexKitBridge, context: Context): JsonObject
}

package com.qimian233.ztool.data.launcher

import com.qimian233.ztool.EnhancedShellExecutor

/**
 * Execution repository for batch uninstall (launcher multi-select entry).
 *
 * Package names collected via the launcher Hook are executed here via Root shell
 * with `pm uninstall --user 0 <pkg>` for silent uninstall; the launcher will
 * automatically clean up corresponding desktop icons upon receiving the PACKAGE_REMOVED broadcast.
 */
class BatchUninstallRepository {

    /** Diagnostic: returns root check result and raw output, facilitating on-site troubleshooting of KernelSU/Magisk differences. */
    fun checkRootAccess(): Pair<Boolean, String> {
        val result = EnhancedShellExecutor.getInstance().checkRootAccess()
        return Pair(
            result.isSuccess,
            "exit=${result.exitCode} out=${result.output.take(160)} err=${result.error.take(160)}"
        )
    }

    fun uninstallPackage(packageName: String): UninstallResult {
        val sanitized = packageName.trim()
        if (!PACKAGE_NAME_REGEX.matches(sanitized)) {
            return UninstallResult(sanitized, false, "invalid package name")
        }
        val result = EnhancedShellExecutor.getInstance()
            .executeRootCommand("pm uninstall --user 0 $sanitized", UNINSTALL_TIMEOUT_SECONDS)
        val success = result.isSuccess
        val message = if (success) {
            ""
        } else {
            (result.error.ifBlank { result.output })
                .lineSequence()
                .firstOrNull { it.isNotBlank() } ?: "unknown error"
        }
        return UninstallResult(sanitized, success, message)
    }

    companion object {
        /** Whitelist character set for package names, preventing injection when concatenating shell commands. */
        private val PACKAGE_NAME_REGEX = Regex("[A-Za-z0-9_.]+")
        private const val UNINSTALL_TIMEOUT_SECONDS = 30
    }
}

data class UninstallResult(
    val packageName: String,
    val success: Boolean,
    val message: String
)

package com.qimian233.ztool.hook.modules.setting

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.qimian233.ztool.data.keys.PreferenceKeys
import com.qimian233.ztool.hook.base.ModuleLog
import io.github.libxposed.api.XposedInterface
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * Lock screen OwnerInfo update core logic (shared class split from OwnerInfoHook).
 * <p>
 * Shared between [OwnerInfoSettingsHook] and OwnerInfoSystemHook: fetches daily quote
 * from API and writes to lock screen OwnerInfo. Constructor injects [xposed] and [logger],
 * both Hooks create instances during their respective callback phases.
 * </p>
 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class OwnerInfoUpdater(
    private val xposed: XposedInterface,
    private val logger: ModuleLog
) {

    private var apiUrl: String? = null
    private var cachedContent = ""

    /**
     * Update OwnerInfo (spawns a new thread to fetch API data, avoiding blocking the caller thread).
     */
    fun updateOwnerInfo(context: Any?, classLoader: ClassLoader) {
        Thread {
            try {
                apiUrl = getString(PreferenceKeys.API_URL.name)
                // Handle potential missing URL protocol scheme
                if (apiUrl != null && apiUrl!!.isNotEmpty()) {
                    if (!apiUrl!!.startsWith("http://") && !apiUrl!!.startsWith("https://") &&
                        !apiUrl!!.startsWith("Https://") && !apiUrl!!.startsWith("Http://")
                    ) {
                        apiUrl = "https://$apiUrl"
                    }
                } else {
                    logger.warn("API_URL configuration is empty, using default value")
                    apiUrl = "https://api.example.com" // Set default URL
                }
                val content = fetchContentFromAPI()
                if (content != cachedContent) {
                    cachedContent = content
                    logger.debug("Fetched new content from API: $content")
                    setOwnerInfoContent(content, context, classLoader)
                } else {
                    logger.debug("Content unchanged, skipping update")
                }
            } catch (e: Exception) {
                logger.error("Error in updateOwnerInfo thread", e)
            }
        }.start()
    }

    private fun fetchContentFromAPI(): String {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(apiUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "OwnerInfoHook/1.0")

            val responseCode = connection.responseCode
            logger.debug("API response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream: InputStream = connection.inputStream
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))

                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }

                val rawResponse = response.toString()
                logger.debug("API raw response: $rawResponse") // Record raw response for debugging

                return parseContentFromJson(rawResponse)
            } else {
                // Read error stream for details
                val errorStream = connection.errorStream
                if (errorStream != null) {
                    val reader = BufferedReader(InputStreamReader(errorStream))
                    val errorResponse = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        errorResponse.append(line)
                    }
                    logger.debug("API error response: $errorResponse")
                }
                logger.debug("HTTP error response: $responseCode")
            }
        } catch (e: Exception) {
            logger.error("Error fetching API data", e)
        } finally {
            connection?.disconnect()
        }
        return "If you see this message, your API is broken, check your settings and Internet connection, then restart com.android.settings"
    }

    private fun parseContentFromJson(jsonString: String): String {
        return try {
            // Match content field using regular expression, handling escape characters
            val regular = getString(PreferenceKeys.REGULAR.name)
            // Skip regex matching if expression is empty or null
            if (regular.isEmpty()) {
                return jsonString
            }
            val pattern = Pattern.compile(regular)
            val matcher = pattern.matcher(jsonString)

            if (matcher.find()) {
                var content = matcher.group(1) ?: return jsonString
                // Handle escape characters (e.g. \" to ")
                content = content
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\/", "/")
                    .replace("\\b", "\b")
                    .replace("\\f", "\u000C")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                content
            } else {
                logger.warn("Content field not found in JSON")
                jsonString
            }
        } catch (e: Exception) {
            logger.error("Error parsing JSON", e)
            jsonString
        }
    }

    /**
     * Set OwnerInfo content (ensures execution on the main thread).
     */
    private fun setOwnerInfoContent(content: String, context: Any?, classLoader: ClassLoader) {
        try {
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    logger.debug("Setting OwnerInfo content: $content")

                    // Method 1: Via LockPatternUtils
                    try {
                        val lockPatternUtils = getObject(context, classLoader)

                        // Enable OwnerInfo first
                        val setEnabled: Method = lockPatternUtils.javaClass
                            .getDeclaredMethod("setOwnerInfoEnabled", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                        setEnabled.invoke(lockPatternUtils, true, 0)
                        // Set OwnerInfo content
                        val setOwnerInfo: Method = lockPatternUtils.javaClass
                            .getDeclaredMethod("setOwnerInfo", String::class.java, Int::class.javaPrimitiveType)
                        setOwnerInfo.invoke(lockPatternUtils, content, 0)

                        logger.debug("Successfully updated OwnerInfo via LockPatternUtils")
                        return@post
                    } catch (t: Throwable) {
                        logger.error("Failed to update via LockPatternUtils", t)
                    }

                    // Method 2: Via ILockSettings service
                    try {
                        val serviceManagerClass = classLoader.loadClass("android.os.ServiceManager")
                        val getServiceMethod: Method =
                            serviceManagerClass.getDeclaredMethod("getService", String::class.java)
                        val lockSettingsService = getServiceMethod.invoke(null, "lock_settings")

                        if (lockSettingsService != null) {
                            // Enable OwnerInfo
                            val setBooleanMethod: Method = lockSettingsService.javaClass
                                .getDeclaredMethod("setBoolean", String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                            setBooleanMethod.invoke(
                                lockSettingsService,
                                "lock_screen_owner_info_enabled", true, 0
                            )
                            // Set content
                            val setStringMethod: Method = lockSettingsService.javaClass
                                .getDeclaredMethod("setString", String::class.java, String::class.java, Int::class.javaPrimitiveType)
                            setStringMethod.invoke(
                                lockSettingsService,
                                "lock_screen_owner_info", content, 0
                            )

                            logger.debug("Successfully updated OwnerInfo via ILockSettings")
                            return@post
                        }
                    } catch (t: Throwable) {
                        logger.error("Failed to update via ILockSettings", t)
                    }

                    // Method 3: Call SettingsProvider directly (fallback)
                    try {
                        if (context is Context) {
                            Settings.Secure.putString(
                                context.contentResolver,
                                "lock_screen_owner_info_enabled", "1"
                            )
                            Settings.Secure.putString(
                                context.contentResolver,
                                "lock_screen_owner_info", content
                            )
                            logger.debug("Successfully updated OwnerInfo via SettingsProvider")
                        }
                    } catch (t: Throwable) {
                        logger.error("Failed to update via SettingsProvider", t)
                    }
                } catch (t: Throwable) {
                    logger.error("Failed to set OwnerInfo content", t)
                }
            }
        } catch (t: Throwable) {
            logger.error("Failed to post to main handler", t)
        }
    }

    private fun getObject(context: Any?, classLoader: ClassLoader): Any {
        return try {
            val lockPatternUtilsClass = classLoader.loadClass(
                "com.android.internal.widget.LockPatternUtils"
            )

            val lockPatternUtils: Any
            if (context is Context) {
                // Create LockPatternUtils instance from Context
                val ctor: Constructor<*> = lockPatternUtilsClass.getDeclaredConstructor(Context::class.java)
                lockPatternUtils = ctor.newInstance(context)
            } else {
                // Use default constructor
                val ctor: Constructor<*> = lockPatternUtilsClass.getDeclaredConstructor()
                lockPatternUtils = ctor.newInstance()
            }
            lockPatternUtils
        } catch (t: Throwable) {
            throw RuntimeException("Failed to create LockPatternUtils", t)
        }
    }

    private fun getString(key: String): String {
        return xposed.getRemotePreferences(PREFS_NAME).getString(key, "")!!
    }

    private companion object {
        const val PREFS_NAME = "xposed_module_config"
    }
}

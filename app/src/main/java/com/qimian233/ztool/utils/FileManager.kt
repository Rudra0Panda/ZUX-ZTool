package com.qimian233.ztool.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android API 33+ file management utility class
 * Supports SAF (Storage Access Framework) and MediaStore methods for file read/write
 */
object FileManager {
    private const val TAG = "FileManager"

    /**
     * Export file using SAF
     * @param context Context
     * @param uri Target directory Uri
     * @param fileName File name to save as
     * @param sourceFile Source file to export
     * @return Success status
     */
    fun exportFileWithSAF(context: Context, uri: Uri?, fileName: String, sourceFile: File?): Boolean {
        if (uri == null || sourceFile == null || !sourceFile.exists()) return false

        val resolver = context.contentResolver
        return try {
            FileInputStream(sourceFile).use { inputStream ->
                val outputStream = resolver.openOutputStream(uri)
                if (outputStream == null) return false
                outputStream.use { out ->
                    val buffer = ByteArray(1024)
                    var length: Int
                    while (inputStream.read(buffer).also { length = it } > 0) {
                        out.write(buffer, 0, length)
                    }
                }
            }
            Log.i(TAG, "File exported to $uri$fileName")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to export file: " + e.message)
            false
        }
    }

    /**
     * Create file and save configuration using SAF
     */
    fun saveConfigWithSAF(context: Context, uri: Uri?, fileName: String, configContent: String?): Boolean {
        if (uri == null) {
            Log.e(TAG, "uri is null")
            return false
        }
        if (configContent == null) {
            Log.e(TAG, "Config content is null")
            return false
        }
        val resolver = context.contentResolver
        return try {
            val outputStream = resolver.openOutputStream(uri)
            if (outputStream != null) {
                outputStream.use { out ->
                    out.write(configContent.toByteArray(StandardCharsets.UTF_8))
                    out.flush()
                    Log.i(TAG, "Config saved to $uri$fileName")
                    true
                }
            } else {
                Log.e(TAG, "Output stream is null")
                false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save file: " + e.message)
            false
        }
    }

    /**
     * Open and read file using SAF
     */
    fun readConfigWithSAF(context: Context, uri: Uri): String? {
        return try {
            val resolver = context.contentResolver
            val inputStream = resolver.openInputStream(uri)
            if (inputStream != null) {
                val reader = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
                val stringBuilder = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    stringBuilder.append(line)
                }
                reader.close()
                inputStream.close()
                stringBuilder.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SAF", "Failed to read file via SAF: " + e.message)
            null
        }
    }

    /**
     * Generate backup file name
     */
    fun generateBackupFileName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val timestamp = sdf.format(Date())
        return "ZTool_Config_Backup_$timestamp.json"
    }
}

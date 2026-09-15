package com.qimian233.ztool.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.qimian233.ztool.R
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Comparator
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * App self log collection service (no Root required, filters logcat by PID)
 */
class LogCollectorService : Service() {
    private var logcatProcess: Process? = null
    private var currentWriter: BufferedWriter? = null
    private var currentFile: File? = null
    private val isRunning = AtomicBoolean(false)
    private var logcatThread: Thread? = null
    private var isRestartMode = false
    private var isForeground = false
    private var mainHandler: Handler? = null
    private var notificationManager: NotificationManager? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate() started")

        mainHandler = Handler(Looper.getMainLooper())
        notificationManager = getSystemService(NotificationManager::class.java)

        createNotificationChannel()
        startForegroundImmediately()

        Log.d(TAG, "Service onCreate() completed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand() started")

        intent?.let {
            isRestartMode = it.getBooleanExtra("is_restart", false)
        }

        if (isRestartMode) {
            Log.d(TAG, "Service started in restart mode")
        }

        if (!isForeground) {
            Log.w(TAG, "Foreground service not started, starting immediately")
            startForegroundImmediately()
        }

        if (!isRunning.get()) {
            isRunning.set(true)
            mainHandler?.postDelayed({
                Log.d(TAG, "Starting log collection")
                startLogCollection()
            }, 100)
        } else {
            Log.d(TAG, "Service is already running")
        }

        Log.d(TAG, "Service onStartCommand() completed")
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy() started")
        stopLogCollection()
        Log.d(TAG, "Log collection service stopped")
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "App task removed, but service continues running")
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun startForegroundImmediately() {
        Log.d(TAG, "Starting foreground service")

        try {
            val notification = createSimpleNotification()
            if (notification != null) {
                startForeground(NOTIFICATION_ID, notification)
                isForeground = true
                Log.d(TAG, "Foreground service started successfully")
            } else {
                Log.e(TAG, "Failed to create notification, cannot start foreground service")
                startFallbackForeground()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission exception starting foreground service", e)
            startFallbackForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            startFallbackForeground()
        }
    }

    private fun createSimpleNotification(): Notification? {
        return try {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            builder.setContentTitle("Log Collection Service")
                .setContentText("Log service is running")
                .setSmallIcon(getNotificationIcon())
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create simple notification", e)
            null
        }
    }

    private fun startFallbackForeground() {
        try {
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            val notification = builder
                .setContentTitle("Log Service")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
            Log.d(TAG, "Fallback foreground service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Fallback foreground service also failed to start", e)
        }
    }

    fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Used to collect app runtime logs"
            channel.setShowBadge(false)
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE

            val manager = notificationManager
            if (manager != null) {
                manager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created successfully")
            } else {
                Log.e(TAG, "NotificationManager is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create notification channel", e)
        }
    }

    private fun getNotificationIcon(): Int {
        return try {
            val icon = R.mipmap.ic_launcher
            if (icon == 0) {
                android.R.drawable.ic_dialog_info
            } else {
                icon
            }
        } catch (e: Exception) {
            android.R.drawable.ic_dialog_info
        }
    }

    private fun startLogCollection() {
        Log.d(TAG, "Preparing to start log collection")

        val existingThread = logcatThread
        if (existingThread != null && existingThread.isAlive) {
            Log.d(TAG, "Stopping existing log collection thread")
            existingThread.interrupt()
            try {
                existingThread.join(1000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for old thread to terminate", e)
            }
        }

        val collectorThread = Thread(LogCollectorRunnable())
        collectorThread.name = "AppLogCollector-Thread"
        collectorThread.priority = Thread.MIN_PRIORITY
        logcatThread = collectorThread
        collectorThread.start()

        Log.d(TAG, "Log collection thread started")
    }

    private fun stopLogCollection() {
        Log.d(TAG, "Stopping log collection")
        isRunning.set(false)

        val thread = logcatThread
        if (thread != null && thread.isAlive) {
            thread.interrupt()
            try {
                thread.join(2000)
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while waiting for log thread to terminate", e)
            }
        }

        val process = logcatProcess
        if (process != null) {
            try {
                process.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop logcat process", e)
            }
            logcatProcess = null
        }

        closeCurrentWriter()

        if (isForeground) {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForeground = false
                Log.d(TAG, "Foreground service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop foreground service", e)
            }
        }

        Log.d(TAG, "Log collection completely stopped")
    }

    private fun buildLogcatCommand(): List<String> {
        val command = ArrayList<String>()
        command.add("logcat")
        command.add("-v")
        command.add("time")
        command.add("--pid=" + android.os.Process.myPid())
        command.add("*:V")
        Log.d(TAG, "Logcat command: $command")
        return command
    }

    private inner class LogCollectorRunnable : Runnable {
        override fun run() {
            Log.d(TAG, "Log collection thread started")

            try {
                val logDir = File(filesDir, LOG_DIR)
                val appLogDir = File(logDir, APP_LOG_SUBDIR)
                if (!appLogDir.exists() && !appLogDir.mkdirs()) {
                    Log.e(TAG, "Failed to create log directory: " + appLogDir.absolutePath)
                    return
                }

                val command = buildLogcatCommand()
                Log.d(TAG, "Executing logcat command: $command")

                val processBuilder = ProcessBuilder(command)
                processBuilder.redirectErrorStream(true)
                val process = processBuilder.start()
                logcatProcess = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))

                val logFile = createNewLogFile(appLogDir)
                currentFile = logFile
                currentWriter = BufferedWriter(FileWriter(logFile, true))

                Log.d(TAG, "Starting to write log file: " + logFile.absolutePath)

                var line: String?
                var lineCount = 0
                var lastStatusLogTime = System.currentTimeMillis()
                var lastFileCheckTime = System.currentTimeMillis()
                val fileCheckInterval = 5000L

                while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                    try {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastFileCheckTime > fileCheckInterval) {
                            val checkedFile = currentFile
                            if (checkedFile != null && !checkedFile.exists()) {
                                Log.w(TAG, "Current log file was deleted, recreating new file")

                                closeCurrentWriter()
                                val recreatedFile = createNewLogFile(appLogDir)
                                currentFile = recreatedFile
                                currentWriter = BufferedWriter(FileWriter(recreatedFile, true))

                                Log.d(TAG, "Created new log file: " + recreatedFile.absolutePath)
                            }
                            lastFileCheckTime = currentTime
                        }

                        line = reader.readLine()
                        if (line != null) {
                            val enhancedLine = enhanceLogLine(line)

                            val activeFile = currentFile
                            if (currentWriter == null || (activeFile != null && !activeFile.exists())) {
                                Log.w(TAG, "Abnormal log file state, reinitializing")
                                closeCurrentWriter()
                                val recreatedFile = createNewLogFile(appLogDir)
                                currentFile = recreatedFile
                                currentWriter = BufferedWriter(FileWriter(recreatedFile, true))
                            }

                            try {
                                currentWriter!!.write(enhancedLine)
                                currentWriter!!.newLine()
                                currentWriter!!.flush()
                            } catch (e: IOException) {
                                if (e.message != null &&
                                    (e.message!!.contains("ENOENT") ||
                                            e.message!!.contains("No such file") ||
                                            e.message!!.contains("Stream closed"))
                                ) {
                                    Log.w(TAG, "Failed to write log, file may have been deleted, recreating: " + e.message)
                                    closeCurrentWriter()
                                    val recreatedFile = createNewLogFile(appLogDir)
                                    currentFile = recreatedFile
                                    currentWriter = BufferedWriter(FileWriter(recreatedFile, true))

                                    currentWriter!!.write(enhancedLine)
                                    currentWriter!!.newLine()
                                    currentWriter!!.flush()
                                } else {
                                    throw e
                                }
                            }

                            lineCount++

                            if (lineCount % 100 == 0 || (currentTime - lastStatusLogTime) > 30000) {
                                Log.d(TAG, "Collected $lineCount lines of logs")
                                lastStatusLogTime = currentTime
                            }

                            val sizedFile = currentFile
                            if (sizedFile != null && sizedFile.length() >= MAX_FILE_SIZE) {
                                Log.d(TAG, "Log file reached size limit, starting rotation")
                                rotateLogFile(appLogDir)
                            }
                        } else {
                            Log.d(TAG, "Logcat stream ended")
                            break
                        }
                    } catch (e: IOException) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Failed to read log stream", e)
                        }
                        break
                    }
                }

                Log.d(TAG, "Log collection completed, collected $lineCount lines in total")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to start log collection", e)
            } finally {
                Log.d(TAG, "Log collection thread finished")
                closeCurrentWriter()
                logcatProcess?.destroy()
            }
        }

        private fun enhanceLogLine(originalLine: String): String {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
                .format(Date())
            return String.format("[%s] %s", timestamp, originalLine)
        }
    }

    private fun createNewLogFile(logDir: File): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            .format(Date())
        val fileName = FILE_PREFIX + timestamp + FILE_SUFFIX
        val newFile = File(logDir, fileName)
        Log.d(TAG, "Created new log file: " + newFile.absolutePath)
        return newFile
    }

    private fun rotateLogFile(logDir: File) {
        closeCurrentWriter()

        val newFile = createNewLogFile(logDir)
        currentFile = newFile
        try {
            currentWriter = BufferedWriter(FileWriter(newFile, true))
            Log.d(TAG, "Log file rotation completed")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to create new log file", e)
            return
        }

        cleanupOldFiles(logDir)
    }

    private fun cleanupOldFiles(logDir: File) {
        val logFiles = logDir.listFiles { _, name ->
            name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)
        }

        if (logFiles != null && logFiles.size > MAX_FILES) {
            Arrays.sort(logFiles, Comparator.comparingLong { obj: File -> obj.lastModified() })

            val filesToDelete = logFiles.size - MAX_FILES
            for (i in 0 until filesToDelete) {
                if (logFiles[i].delete()) {
                    Log.d(TAG, "Deleted old log file: " + logFiles[i].name)
                } else {
                    Log.w(TAG, "Failed to delete old log file: " + logFiles[i].name)
                }
            }
        }
    }

    private fun closeCurrentWriter() {
        val writer = currentWriter
        if (writer != null) {
            try {
                writer.close()
                Log.d(TAG, "Log writer closed")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to close log writer", e)
            }
            currentWriter = null
        }
    }

    companion object {
        private const val TAG = "LogCollectorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "log_collector_channel"
        private const val CHANNEL_NAME = "Log Collection Service"

        private const val MAX_FILE_SIZE = 1024L * 1024L // 1MB
        private const val MAX_FILES = 20
        private const val LOG_DIR = "Log"
        private const val APP_LOG_SUBDIR = "app"
        private const val FILE_PREFIX = "app_log_"
        private const val FILE_SUFFIX = ".log"
    }
}

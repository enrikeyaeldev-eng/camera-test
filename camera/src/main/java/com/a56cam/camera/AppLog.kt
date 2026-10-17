package com.a56cam.camera

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * App-wide logger. Every line goes to logcat under the tag [TAG] and to a rolling
 * log file in the app's private storage, so the owner can share logs without a computer.
 */
object AppLog {
    const val TAG = "A56Cam"
    private const val MAX_BYTES = 1_000_000L

    private val writer = Executors.newSingleThreadExecutor { Thread(it, "A56Cam-log") }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var logDir: File? = null

    fun init(context: Context) {
        logDir = logDir(context).apply { mkdirs() }
    }

    fun logDir(context: Context): File = File(context.filesDir, "logs")

    /** Current log plus the previous rolled file and any crash reports. */
    fun logFiles(context: Context): List<File> =
        logDir(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    fun d(msg: String) = write(Log.DEBUG, msg, null)
    fun i(msg: String) = write(Log.INFO, msg, null)
    fun w(msg: String, t: Throwable? = null) = write(Log.WARN, msg, t)
    fun e(msg: String, t: Throwable? = null) = write(Log.ERROR, msg, t)

    private fun write(level: Int, msg: String, t: Throwable?) {
        when (level) {
            Log.DEBUG -> Log.d(TAG, msg, t)
            Log.INFO -> Log.i(TAG, msg, t)
            Log.WARN -> Log.w(TAG, msg, t)
            else -> Log.e(TAG, msg, t)
        }
        val dir = logDir ?: return
        val levelChar = when (level) { Log.DEBUG -> 'D'; Log.INFO -> 'I'; Log.WARN -> 'W'; else -> 'E' }
        val line = buildString {
            append(synchronized(timeFormat) { timeFormat.format(Date()) })
            append(' ').append(levelChar).append(" [").append(Thread.currentThread().name).append("] ")
            append(msg)
            if (t != null) append('\n').append(Log.getStackTraceString(t))
            append('\n')
        }
        writer.execute {
            try {
                val file = File(dir, "app.log")
                if (file.length() > MAX_BYTES) {
                    file.renameTo(File(dir, "app.log.1"))
                }
                file.appendText(line)
            } catch (_: Exception) {
                // Logging must never crash the app.
            }
        }
    }

    /** Writes synchronously; used by the crash handler when the process is about to die. */
    fun writeCrashSync(text: String): File? {
        val dir = logDir ?: return null
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(dir, "crash-$stamp.txt").apply { writeText(text) }
        } catch (_: Exception) {
            null
        }
    }
}

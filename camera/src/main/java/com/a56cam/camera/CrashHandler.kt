package com.a56cam.camera

import android.os.Build
import android.util.Log

/** Saves the stack trace of any uncaught exception to the log folder, then lets the app die normally. */
object CrashHandler {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = buildString {
                    append("Crash in thread ").append(thread.name).append('\n')
                    append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(" (").append(Build.DEVICE).append("), Android ").append(Build.VERSION.RELEASE)
                    append(" API ").append(Build.VERSION.SDK_INT).append('\n')
                    append(Log.getStackTraceString(throwable))
                }
                Log.e(AppLog.TAG, text)
                AppLog.writeCrashSync(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

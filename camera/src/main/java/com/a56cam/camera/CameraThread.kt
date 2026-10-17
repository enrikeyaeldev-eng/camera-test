package com.a56cam.camera

import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.Executor

/** A dedicated background thread for camera callbacks; camera work never runs on the UI thread. */
class CameraThread(name: String) : AutoCloseable {
    private val thread = HandlerThread(name).apply { start() }
    val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    override fun close() {
        thread.quitSafely()
    }
}

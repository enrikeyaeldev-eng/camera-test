package com.a56cam.app

import android.app.Application
import android.os.Build
import com.a56cam.camera.AppLog
import com.a56cam.camera.CrashHandler

class A56CamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        CrashHandler.install()
        AppLog.i(
            "App start ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE}) on " +
                "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}",
        )
    }
}

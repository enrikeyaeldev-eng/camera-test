package com.a56cam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.a56cam.camera.AppLog

class MainActivity : ComponentActivity() {

    private enum class Screen { CAMERA, SETTINGS, REPORT }

    private val cameraVm: CameraViewModel by viewModels()
    private val reportVm: ReportViewModel by viewModels()
    private val permissionGranted = mutableStateOf(false)
    private val askedForPermission = mutableStateOf(false)
    private val deviceOrientation = mutableIntStateOf(0)

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation != ORIENTATION_UNKNOWN) deviceOrientation.intValue = orientation
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionGranted.value = hasCameraPermission()
        reportVm.refreshCrashCount()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
        if (permissionGranted.value) cameraVm.controller.resume()
    }

    override fun onPause() {
        super.onPause()
        // Rule: cameras and sessions are closed whenever the app leaves the screen.
        cameraVm.controller.pause()
        orientationListener.disable()
        reportVm.cancelRun("The report stopped because the app left the screen (the camera is always released on pause). Keep the app open and run it again.")
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun shareLogs() {
        val zip = Sharing.zip(this, "a56cam-logs-${Sharing.stamp()}.zip", AppLog.logFiles(this))
        Sharing.shareFiles(this, listOf(zip), "A56Cam logs")
    }

    @Composable
    private fun App() {
        var screen by rememberSaveable { mutableStateOf(Screen.CAMERA) }
        val granted by permissionGranted
        val reportState by reportVm.state.collectAsState()
        val cameraState by cameraVm.controller.state.collectAsState()

        BackHandler(enabled = screen != Screen.CAMERA && !reportState.running) {
            screen = if (screen == Screen.REPORT) Screen.SETTINGS else Screen.CAMERA
        }
        // Keep the screen on while the camera is visible or a report is running.
        LaunchedEffect(screen, reportState.running) {
            if (screen == Screen.CAMERA || reportState.running) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        if (!granted) {
            PermissionScreen()
            return
        }
        when (screen) {
            Screen.CAMERA -> CameraScreen(
                vm = cameraVm,
                deviceOrientation = { deviceOrientation.intValue },
                onOpenSettings = { screen = Screen.SETTINGS },
            )
            Screen.SETTINGS -> SettingsScreen(
                settings = cameraVm.settings,
                lens = cameraState.lens,
                version = reportVm.version,
                onBack = { screen = Screen.CAMERA },
                onDeviceReport = { screen = Screen.REPORT },
                onShareLogs = { shareLogs() },
            )
            Screen.REPORT -> DeviceReportScreen(
                vm = reportVm,
                onBack = { screen = Screen.SETTINGS },
                onShareLogs = { shareLogs() },
            )
        }
    }

    @Composable
    private fun PermissionScreen() {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            AppLog.i("Camera permission ${if (ok) "granted" else "denied"}")
            permissionGranted.value = ok
            askedForPermission.value = true
            if (ok) cameraVm.controller.resume()
        }
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("A56Cam", style = MaterialTheme.typography.headlineMedium)
            Text("A56Cam needs the camera permission to show the preview and take photos.")
            val permanentlyDenied = askedForPermission.value &&
                !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            if (permanentlyDenied) {
                Text("Permission was denied. Allow Camera in the app's settings.")
                Button(onClick = {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                }) { Text("Open app settings") }
            } else {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant camera permission") }
            }
        }
    }
}

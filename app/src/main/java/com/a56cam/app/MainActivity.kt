package com.a56cam.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.a56cam.camera.AppLog
import java.io.File

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()
    private val permissionGranted = mutableStateOf(false)
    private val askedForPermission = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionGranted.value = hasCameraPermission()
        vm.refreshCrashCount()
    }

    override fun onPause() {
        super.onPause()
        vm.cancelRun("The report stopped because the app left the screen (the camera is always released on pause). Keep the app open and run it again.")
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    @Composable
    private fun MainScreen() {
        val state by vm.state.collectAsState()
        val granted by permissionGranted
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            AppLog.i("Camera permission ${if (ok) "granted" else "denied"}")
            permissionGranted.value = ok
            askedForPermission.value = true
        }

        LaunchedEffect(state.running) {
            if (state.running) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("A56Cam", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Phase 0: device report", style = MaterialTheme.typography.titleMedium)
            Text("Version ${vm.version}", style = MaterialTheme.typography.bodySmall)
            Text("Native: ${state.nativeInfo}", style = MaterialTheme.typography.bodySmall)

            if (state.crashLogs > 0) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "A previous crash was saved (${state.crashLogs}). Tap Share logs and send the file to Claude.",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (!granted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("The report needs the camera permission. Nothing leaves the phone unless you share it.")
                        val permanentlyDenied = askedForPermission.value &&
                            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                        if (permanentlyDenied) {
                            Text("Permission was denied. Allow Camera in the app's settings.")
                            Button(onClick = { openAppSettings() }) { Text("Open app settings") }
                        } else {
                            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                                Text("Grant camera permission")
                            }
                        }
                    }
                }
            }

            Button(
                onClick = { vm.runReport() },
                enabled = granted && !state.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.running) "Running..." else "Run device report") }
            Text(
                "Takes about 1 to 3 minutes. Point the back camera at a still, detailed scene, keep the phone steady, " +
                    "and keep the app open until it finishes.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.running) {
                CircularProgressIndicator()
            }
            if (state.progress.isNotEmpty()) {
                Text(state.progress.takeLast(6).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            state.result?.let { result ->
                Text(
                    if (result.exportedTo != null) "Saved to ${result.exportedTo}" else
                        "Saved in the app folder only (copy to Documents failed; see logs).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = { Sharing.shareFiles(this@MainActivity, listOf(result.reportJson, result.reportTxt), "A56Cam report") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share report (report.json + report.txt)") }
                OutlinedButton(
                    onClick = {
                        val files = result.runDir.listFiles()?.toList() ?: emptyList()
                        val zip = Sharing.zip(this@MainActivity, "a56cam-${result.runDir.name}.zip", files)
                        Sharing.shareFiles(this@MainActivity, listOf(zip), "A56Cam report with test images")
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Share report + test images (zip)") }
                Text(
                    result.summary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    val logs: List<File> = AppLog.logFiles(this@MainActivity)
                    val zip = Sharing.zip(this@MainActivity, "a56cam-logs-${Sharing.stamp()}.zip", logs)
                    Sharing.shareFiles(this@MainActivity, listOf(zip), "A56Cam logs")
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Share logs") }
        }
    }
}

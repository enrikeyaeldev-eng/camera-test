package com.a56cam.app

import android.hardware.camera2.CameraMetadata
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.a56cam.camera.LensInfo

@Composable
fun SettingsScreen(
    settings: AppSettings,
    lens: LensInfo?,
    version: String,
    onBack: () -> Unit,
    onDeviceReport: () -> Unit,
    onShareLogs: () -> Unit,
) {
    val values by settings.values.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Section("Sharpening") {
            Text(
                "Samsung's High quality sharpening is very strong; Fast looks the same as Off on this phone. " +
                    "Off gives the most natural result for editing in Lightroom.",
                style = MaterialTheme.typography.bodySmall,
            )
            modeOptions(lens?.edgeModes).forEach { (mode, label) ->
                Choice(label, values.edge == mode) { settings.update { it.copy(edge = mode) } }
            }
        }

        Section("Noise reduction") {
            Text(
                "High quality smooths noise strongly but also fine detail; Fast looks the same as Off on this phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            modeOptions(lens?.noiseReductionModes).forEach { (mode, label) ->
                Choice(label, values.noiseReduction == mode) { settings.update { it.copy(noiseReduction = mode) } }
            }
        }

        Section("JPEG quality") {
            listOf(85, 90, 95, 100).forEach { q ->
                Choice("$q", values.jpegQuality == q) { settings.update { it.copy(jpegQuality = q) } }
            }
        }

        Section("Diagnostics") {
            OutlinedButton(onClick = onDeviceReport, modifier = Modifier.fillMaxWidth()) { Text("Device report") }
            OutlinedButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth()) { Text("Share logs") }
        }

        Text("Photos are saved to ${PhotoStore.RELATIVE_PATH}.", style = MaterialTheme.typography.bodySmall)
        Text("A56Cam $version", style = MaterialTheme.typography.bodySmall)
    }
}

/** Camera default plus each Off / Fast / High quality mode the current camera offers. */
private fun modeOptions(available: List<Int>?): List<Pair<Int, String>> {
    val all = listOf(
        CameraMetadata.EDGE_MODE_OFF to "Off",
        CameraMetadata.EDGE_MODE_FAST to "Fast",
        CameraMetadata.EDGE_MODE_HIGH_QUALITY to "High quality",
    ) // NOISE_REDUCTION_MODE_OFF/FAST/HIGH_QUALITY share the values 0/1/2.
    return listOf(AppSettings.CAMERA_DEFAULT to "Camera default") +
        all.filter { available == null || it.first in available }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium)
    content()
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

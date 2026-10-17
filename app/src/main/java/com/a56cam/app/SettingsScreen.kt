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
import androidx.compose.material3.Switch
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
    /** Outcome of the GPU self-test, or null while it runs. */
    gpuStatus: String?,
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

        Section("Look (Stack, Night, HDR)") {
            Text(
                "Bright brightens shadows and midtones, adds a little contrast and colour, and makes indoor light " +
                    "less orange. Photo mode keeps Samsung's own processing.",
                style = MaterialTheme.typography.bodySmall,
            )
            AppSettings.Look.entries.forEach { l -> Choice(l.label, values.look == l) { settings.update { it.copy(look = l) } } }
        }

        Section("Burst modes (Stack, Night, HDR)") {
            Text(
                "Stack merges frames taken 1 stop darker to protect highlights; Night uses up to 1/10 s per frame " +
                    "and drops shaken frames; HDR merges 10 frames taken 2 stops darker, then lifts the shadows.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Stack frames", style = MaterialTheme.typography.labelLarge)
            listOf(4, 8, 12).forEach { n -> Choice("$n", values.stackFrames == n) { settings.update { it.copy(stackFrames = n) } } }
            Text("Night frames", style = MaterialTheme.typography.labelLarge)
            listOf(8, 12, 16).forEach { n -> Choice("$n", values.nightFrames == n) { settings.update { it.copy(nightFrames = n) } } }
            Text("File for Lightroom (burst modes, about 75 MB each)", style = MaterialTheme.typography.labelLarge)
            AppSettings.RawOutput.entries.forEach { r ->
                Choice(r.label, values.rawOutput == r) { settings.update { it.copy(rawOutput = r) } }
            }
            Toggle("Use frames from just before the shutter press", values.preShutter) { on ->
                settings.update { it.copy(preShutter = on) }
            }
            Text(
                "The last 3 preview frames are merged in, so the photo shows the moment you pressed and the burst " +
                    "after the press is shorter. Keeps full-size frames flowing while a burst mode is open (more battery).",
                style = MaterialTheme.typography.bodySmall,
            )
            Toggle("Merge on the GPU", values.gpuMerge) { on -> settings.update { it.copy(gpuMerge = on) } }
            Text(gpuStatus ?: "Checking the GPU…", style = MaterialTheme.typography.bodySmall)
        }

        Section("Shutter and display") {
            Toggle("Volume keys and Bluetooth remotes take photos", values.volumeShutter) { on ->
                settings.update { it.copy(volumeShutter = on) }
            }
            Toggle("Grid", values.grid) { on -> settings.update { it.copy(grid = on) } }
        }

        Section("Diagnostics") {
            Toggle("Debug: save source frames, merge map and stats", values.debug) { on ->
                settings.update { it.copy(debug = on) }
            }
            OutlinedButton(onClick = onDeviceReport, modifier = Modifier.fillMaxWidth()) { Text("Device report") }
            OutlinedButton(onClick = onShareLogs, modifier = Modifier.fillMaxWidth()) { Text("Share logs") }
        }

        Text(
            "Photos are saved to ${PhotoStore.RELATIVE_PATH}; debug files to DCIM/A56Cam/debug and Documents/A56Cam/debug.",
            style = MaterialTheme.typography.bodySmall,
        )
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
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChange(!checked) }, role = Role.Switch)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
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
